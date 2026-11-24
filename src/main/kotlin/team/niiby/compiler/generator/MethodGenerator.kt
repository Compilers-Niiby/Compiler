package team.niiby.compiler.generator

import team.niiby.compiler.analyzer.ResolvedConstructor
import team.niiby.compiler.analyzer.ResolvedField
import team.niiby.compiler.analyzer.ResolvedMethod
import team.niiby.compiler.analyzer.SemanticInfo
import team.niiby.compiler.lexer.TokenType
import team.niiby.compiler.parser.AssignmentStatement
import team.niiby.compiler.parser.AstNode
import team.niiby.compiler.parser.BinaryExpression
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.CallExpression
import team.niiby.compiler.parser.Expression
import team.niiby.compiler.parser.ExpressionStatement
import team.niiby.compiler.parser.ForStatement
import team.niiby.compiler.parser.GroupedExpression
import team.niiby.compiler.parser.IfStatement
import team.niiby.compiler.parser.LiteralExpression
import team.niiby.compiler.parser.MemberExpression
import team.niiby.compiler.parser.NameExpression
import team.niiby.compiler.parser.Parameter
import team.niiby.compiler.parser.ReturnStatement
import team.niiby.compiler.parser.Statement
import team.niiby.compiler.parser.ThisExpression
import team.niiby.compiler.parser.UnaryExpression
import team.niiby.compiler.parser.VariableDeclaration
import team.niiby.compiler.parser.WhileStatement

internal class MethodGenerator(
    private val semantics: SemanticInfo,
    private val classes: ClassGenerator,
    private val owner: String,
    private val resultType: String,
    parameters: List<Parameter> = emptyList(),
) {
    private data class Local(val type: String, val slot: Int)

    val code = MethodCode().apply { local(owner) }
    private var locals = parameters.associateTo(mutableMapOf()) { parameter ->
        val type = parameter.type.typeName()
        parameter.name to Local(type, code.local(type))
    }
    private val builtins = BuiltinGenerator(this)

    fun typeOf(expression: Expression): String = semantics.types.getValue(expression)

    // A false result means that every path through the block has already returned.
    fun block(block: Block): Boolean {
        val outer = locals
        locals = outer.toMutableMap()
        try {
            for (statement in block.statements) {
                if (!statement(statement)) return false
            }
            return true
        } finally {
            locals = outer
        }
    }

    private fun statement(statement: Statement): Boolean {
        when (statement) {
            is Block -> return block(statement)
            is VariableDeclaration -> {
                val type = typeOf(statement.initializer)
                expression(statement.initializer)
                val slot = code.local(type)
                code.store(type, slot)
                locals[statement.name] = Local(type, slot)
            }
            is AssignmentStatement -> assignment(statement)
            is ReturnStatement -> {
                returnExpression(statement.value)
                return false
            }
            is ExpressionStatement -> {
                expression(statement.expression)
                code.discard(typeOf(statement.expression))
            }
            is IfStatement -> return conditional(statement)
            is WhileStatement -> whileLoop(statement)
            is ForStatement -> forLoop(statement)
        }
        return true
    }

    private fun assignment(assignment: AssignmentStatement) {
        val target = assignment.target
        val field = semantics.fields[target]
        if (field == null) {
            val local = locals.getValue((target as NameExpression).name)
            expressionAs(assignment.value, local.type)
            code.store(local.type, local.slot)
        } else {
            code.load(owner, 0)
            expressionAs(assignment.value, field.type)
            code.writeField(field.owner, field.name, field.type)
        }
    }

    fun returnExpression(value: Expression?) {
        value?.let { expressionAs(it, resultType) }
        code.returnValue(resultType)
    }

    private fun conditional(statement: IfStatement): Boolean {
        val otherwise = code.label()
        expression(statement.condition)
        code.wideBranch("ifne", otherwise, 1)
        val thenFallsThrough = block(statement.thenBranch)
        val elseBranch = statement.elseBranch
        val end = if (thenFallsThrough && elseBranch != null) code.label().also { code.jump(it) } else null
        code.place(otherwise)
        val elseFallsThrough = elseBranch?.let { block(it) } ?: true
        end?.let { code.place(it) }
        return thenFallsThrough || elseFallsThrough
    }

    private fun whileLoop(statement: WhileStatement) {
        val start = code.label()
        val end = code.label()
        code.place(start)
        expression(statement.condition)
        code.wideBranch("ifne", end, 1)
        if (block(statement.body)) code.jump(start)
        code.place(end)
    }

    private fun forLoop(statement: ForStatement) {
        val collection = typeOf(statement.iterable)
        val element = elementType(collection)
        val elementSlot = code.local(element)
        expression(statement.iterable)
        builtins.forEachElement(collection) {
            code.store(element, elementSlot)
            val previous = locals.put(statement.variable, Local(element, elementSlot))
            val fallsThrough = block(statement.body)
            if (previous == null) locals.remove(statement.variable) else locals[statement.variable] = previous
            fallsThrough
        }
    }

    fun expressionAs(expression: Expression, expected: String) {
        expression(expression)
        code.coerce(typeOf(expression), expected)
    }

    fun expression(node: Expression) {
        when (node) {
            is LiteralExpression -> builtins.literal(node)
            is ThisExpression -> code.load(owner, 0)
            is GroupedExpression -> expression(node.expression)
            is UnaryExpression -> builtins.unary(node)
            is BinaryExpression -> builtins.binary(node.operator, node.left, node.right)
            is NameExpression -> {
                val local = locals[node.name]
                if (local == null) access(node, null, node.name) else code.load(local.type, local.slot)
            }
            is MemberExpression -> access(node, node.receiver, node.name)
            is CallExpression -> when (val callee = node.callee) {
                is NameExpression -> invoke(node, null, callee.name, node.arguments)
                is MemberExpression -> invoke(node, callee.receiver, callee.name, node.arguments)
                else -> error("Unchecked call target")
            }
        }
    }

    private fun access(node: Expression, receiver: Expression?, name: String) {
        val field = semantics.fields[node]
        if (field == null) invoke(node, receiver, name, emptyList()) else readField(field, receiver)
    }

    private fun readField(field: ResolvedField, receiver: Expression?) {
        if (field.owner == "Integer" || field.owner == "Real") {
            // A bare Integer/Real qualifier is a type name; an instance expression may have effects.
            if (receiver != null && semantics.types.containsKey(receiver)) {
                expression(receiver)
                code.discard(typeOf(receiver))
            }
            builtins.constant(field.owner, field.name)
        } else {
            loadReceiver(receiver)
            code.emit(
                "invokevirtual ${jvmClassName(field.owner)}/${getterName(field.owner, field.name)}()${descriptor(field.type)}",
                pops = 1, pushes = slots(field.type),
            )
        }
    }

    private fun invoke(node: Expression, receiver: Expression?, name: String, arguments: List<Expression>) {
        val constructor = semantics.constructors[node]
        val method = semantics.methods[node]
        val consoleCall = semantics.consoleCalls[node]
        when {
            constructor != null -> construct(constructor, arguments)
            method != null -> invokeMethod(method, receiver, arguments)
            consoleCall != null -> JvmConsole.emitCall(this, consoleCall, arguments)
            receiver == null -> builtins.construct(typeOf(node), arguments)
            else -> builtins.call(receiver, name, arguments)
        }
    }

    private fun construct(binding: ResolvedConstructor, arguments: List<Expression>) {
        val type = jvmClassName(binding.owner)
        val parameters = binding.declaration?.parameters.orEmpty()
        // Argument expressions can contain loops. Keep uninitialized references off their stacks.
        val argumentLocals = arguments.zip(parameters).map { (argument, parameter) ->
            val parameterType = parameter.type.typeName()
            val slot = code.local(parameterType)
            expressionAs(argument, parameterType)
            code.store(parameterType, slot)
            Local(parameterType, slot)
        }
        code.emit("new $type", pushes = 1)
        code.emit("dup", pops = 1, pushes = 2)
        for (local in argumentLocals) code.load(local.type, local.slot)
        val argumentSlots = argumentLocals.sumOf { slots(it.type) }
        val markers = classes.markers(binding.declaration)
        repeat(markers) { code.emit("aconst_null", pushes = 1) }
        code.emit("invokespecial $type/<init>${classes.constructorDescriptor(binding.declaration)}", pops = 1 + argumentSlots + markers)
    }

    private fun invokeMethod(binding: ResolvedMethod, receiver: Expression?, arguments: List<Expression>) {
        val method = binding.declaration
        val result = method.resultType()
        loadReceiver(receiver)
        var argumentSlots = 0
        for ((argument, parameter) in arguments.zip(method.parameters)) {
            val type = parameter.type.typeName()
            expressionAs(argument, type)
            argumentSlots += slots(type)
        }
        code.emit(
            "invokevirtual ${jvmClassName(binding.owner)}/${method.jvmSignature()}",
            pops = 1 + argumentSlots, pushes = slots(result),
        )
    }

    private fun loadReceiver(receiver: Expression?) {
        if (receiver == null) code.load(owner, 0) else expression(receiver)
    }

    fun fail(node: AstNode, message: String): Nothing = throw GenerationException(message, node.span.start)
}
