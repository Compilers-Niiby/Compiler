package team.niiby.compiler.generator

import java.lang.classfile.CodeBuilder
import java.lang.constant.MethodTypeDesc
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
    val code: CodeBuilder,
    private val resultType: String,
    parameters: List<Parameter> = emptyList(),
) {
    private data class Local(val type: String, val slot: Int)

    private var locals = parameters.mapIndexed { index, parameter ->
        parameter.name to Local(parameter.type.typeName(), code.parameterSlot(index))
    }.toMap(mutableMapOf())
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
            code.aload(code.receiverSlot())
            expressionAs(assignment.value, field.type)
            code.writeField(typeSymbol(field.owner), field.name, field.type)
        }
    }

    fun returnExpression(value: Expression?) {
        value?.let { expressionAs(it, resultType) }
        code.returnValue(resultType)
    }

    private fun conditional(statement: IfStatement): Boolean {
        val otherwise = code.newLabel()
        expression(statement.condition)
        code.ifeq(otherwise)
        val thenFallsThrough = block(statement.thenBranch)
        val elseBranch = statement.elseBranch
        val end = if (thenFallsThrough && elseBranch != null) code.newLabel().also { code.goto_(it) } else null
        code.labelBinding(otherwise)
        val elseFallsThrough = elseBranch?.let { block(it) } ?: true
        end?.let(code::labelBinding)
        return thenFallsThrough || elseFallsThrough
    }

    private fun whileLoop(statement: WhileStatement) {
        val start = code.newBoundLabel()
        val end = code.newLabel()
        expression(statement.condition)
        code.ifeq(end)
        if (block(statement.body)) code.goto_(start)
        code.labelBinding(end)
    }

    private fun forLoop(statement: ForStatement) {
        val collection = typeOf(statement.iterable)
        val element = elementType(collection)
        val collectionSlot = code.local(collection)
        val indexSlot = code.local("Integer")
        val elementSlot = code.local(element)
        expression(statement.iterable)
        code.store(collection, collectionSlot)
        code.iconst_0()
        code.store("Integer", indexSlot)

        val start = code.newBoundLabel()
        val end = code.newLabel()
        code.load("Integer", indexSlot)
        code.load(collection, collectionSlot)
        builtins.length(collection)
        code.if_icmpge(end)
        code.load(collection, collectionSlot)
        code.load("Integer", indexSlot)
        builtins.get(collection)
        code.store(element, elementSlot)

        val previous = locals.put(statement.variable, Local(element, elementSlot))
        val fallsThrough = block(statement.body)
        if (previous == null) locals.remove(statement.variable) else locals[statement.variable] = previous
        if (fallsThrough) {
            code.iinc(indexSlot, 1)
            code.goto_(start)
        }
        code.labelBinding(end)
    }

    fun expressionAs(expression: Expression, expected: String) {
        expression(expression)
        code.coerce(typeOf(expression), expected)
    }

    fun expression(node: Expression) {
        when (node) {
            is LiteralExpression -> builtins.literal(node)
            is ThisExpression -> code.aload(code.receiverSlot())
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
            val owner = typeSymbol(field.owner)
            code.invokevirtual(owner, getterName(owner, field.name), MethodTypeDesc.of(typeSymbol(field.type)))
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
        val type = typeSymbol(binding.owner)
        val parameters = binding.declaration?.parameters.orEmpty()
        // Argument expressions can contain loops. Keep uninitialized references off their stacks.
        val argumentLocals = arguments.zip(parameters).map { (argument, parameter) ->
            val parameterType = parameter.type.typeName()
            val slot = code.local(parameterType)
            expressionAs(argument, parameterType)
            code.store(parameterType, slot)
            Local(parameterType, slot)
        }
        code.new_(type)
        code.dup()
        for (local in argumentLocals) code.load(local.type, local.slot)
        repeat(classes.markers(binding.declaration)) { code.aconst_null() }
        code.invokespecial(type, "<init>", classes.constructorSignature(binding.declaration))
    }

    private fun invokeMethod(binding: ResolvedMethod, receiver: Expression?, arguments: List<Expression>) {
        val method = binding.declaration
        loadReceiver(receiver)
        for ((argument, parameter) in arguments.zip(method.parameters)) {
            expressionAs(argument, parameter.type.typeName())
        }
        code.invokevirtual(typeSymbol(binding.owner), method.jvmName(), method.jvmSignature())
    }

    private fun loadReceiver(receiver: Expression?) {
        if (receiver == null) code.aload(code.receiverSlot()) else expression(receiver)
    }

    fun fail(node: AstNode, message: String): Nothing = throw GenerationException(message, node.span.start)
}
