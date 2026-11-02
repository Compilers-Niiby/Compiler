package team.niiby.compiler.analyzer

import team.niiby.compiler.lexer.TokenType
import team.niiby.compiler.parser.AssignmentStatement
import team.niiby.compiler.parser.AstNode
import team.niiby.compiler.parser.BinaryExpression
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.CallExpression
import team.niiby.compiler.parser.ClassDeclaration
import team.niiby.compiler.parser.ConstructorDeclaration
import team.niiby.compiler.parser.Expression
import team.niiby.compiler.parser.ExpressionBody
import team.niiby.compiler.parser.ExpressionStatement
import team.niiby.compiler.parser.ForStatement
import team.niiby.compiler.parser.GroupedExpression
import team.niiby.compiler.parser.IfStatement
import team.niiby.compiler.parser.LiteralExpression
import team.niiby.compiler.parser.MemberExpression
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.NameExpression
import team.niiby.compiler.parser.Parameter
import team.niiby.compiler.parser.Program
import team.niiby.compiler.parser.ReturnStatement
import team.niiby.compiler.parser.ThisExpression
import team.niiby.compiler.parser.TypeReference
import team.niiby.compiler.parser.UnaryExpression
import team.niiby.compiler.parser.VariableDeclaration
import team.niiby.compiler.parser.WhileStatement

class Analyzer {
    private val classes = linkedMapOf<String, ClassDeclaration>()
    private val fields = mutableMapOf<String, MutableMap<String, String>>()
    private val activeFields = mutableSetOf<String>()
    private val parents = mapOf("Integer" to "AnyValue", "Real" to "AnyValue", "Boolean" to "AnyValue", "AnyValue" to "Class", "AnyRef" to "Class", "Array" to "AnyRef", "List" to "AnyRef")
    private val builtins = parents.keys + "Class"
    private lateinit var owner: ClassDeclaration

    fun analyze(program: Program): Program {
        classes.clear()
        fields.clear()
        activeFields.clear()
        for (declaration in program.classes) {
            val name = declaration.type.name
            if (name in builtins || classes.putIfAbsent(name, declaration) != null) fail("Duplicate class '$name'", declaration)
            if (declaration.type.arguments.isNotEmpty()) fail("User-defined generic classes are not supported", declaration)
        }
        for (declaration in program.classes) {
            owner = declaration
            declaration.parent?.let { type(it) }
            val visited = mutableSetOf<String>()
            var current: String? = declaration.type.name
            while (current != null) {
                if (!visited.add(current)) fail("Inheritance cycle", declaration)
                current = parent(current)
            }
            val signatures = mutableSetOf<String>()
            val names = mutableSetOf<String>()
            for (member in declaration.members) {
                when (member) {
                    is VariableDeclaration -> if (!names.add(member.name)) fail("Duplicate field '${member.name}'", member)
                    is MethodDeclaration -> {
                        member.parameters.forEach { type(it.type) }
                        member.returnType?.let { type(it) }
                        val signature = member.name + member.parameters.map { type(it.type) }
                        if (!signatures.add(signature)) fail("Duplicate method signature '${member.name}'", member)
                    }
                    is ConstructorDeclaration -> {
                        member.parameters.forEach { type(it.type) }
                        if (!signatures.add("this" + member.parameters.map { type(it.type) })) fail("Duplicate constructor", member)
                    }
                }
            }
        }
        for (declaration in program.classes) {
            owner = declaration
            declaration.members.filterIsInstance<VariableDeclaration>().forEach { field(declaration.type.name, it.name, it) }
            for (member in declaration.members) {
                when (member) {
                    is VariableDeclaration -> Unit
                    is MethodDeclaration -> {
                        val scope = parameters(member.parameters)
                        val expected = member.returnType?.let { type(it) } ?: "Void"
                        val inherited = methods(parent(declaration.type.name), member.name).firstOrNull {
                            it.parameters.map { p -> type(p.type) } == member.parameters.map { p -> type(p.type) }
                        }
                        if (inherited != null && !assignable(expected, inherited.returnType?.let { type(it) } ?: "Void")) fail("Incompatible override return type", member)
                        when (val body = member.body) {
                            is ExpressionBody -> requireType(expression(body.expression, scope), expected, body)
                            is Block -> {
                                block(body, scope, expected)
                                if (expected != "Void" && !returns(body)) fail("Method '${member.name}' may finish without returning $expected", member)
                            }
                        }
                    }
                    is ConstructorDeclaration -> block(member.body, parameters(member.parameters), "Void")
                }
            }
        }
        return program
    }

    private fun parameters(parameters: List<Parameter>): MutableMap<String, String> {
        val scope = mutableMapOf<String, String>()
        for (parameter in parameters) {
            if (scope.putIfAbsent(parameter.name, type(parameter.type)) != null) fail("Duplicate parameter '${parameter.name}'", parameter)
        }
        return scope
    }

    private fun block(block: Block, outer: Map<String, String>, expected: String) {
        val scope = outer.toMutableMap()
        val declared = mutableSetOf<String>()
        for (statement in block.statements) {
            when (statement) {
                is Block -> block(statement, scope, expected)
                is VariableDeclaration -> {
                    val value = expression(statement.initializer, scope)
                    if (value == "Void") fail("A variable needs a value", statement)
                    if (!declared.add(statement.name) || statement.name in outer) fail("Duplicate local '${statement.name}'", statement)
                    scope[statement.name] = value
                }
                is AssignmentStatement -> {
                    val target = when (val target = statement.target) {
                        is NameExpression -> scope[target.name] ?: field(owner.type.name, target.name, target)
                        is MemberExpression -> {
                            if (target.receiver !is ThisExpression) fail("Fields of another object are read-only", target)
                            field(owner.type.name, target.name, target)
                        }
                        else -> fail("Invalid assignment target", target)
                    }
                    requireType(expression(statement.value, scope), target, statement)
                }
                is ReturnStatement -> requireType(statement.value?.let { expression(it, scope) } ?: "Void", expected, statement)
                is ExpressionStatement -> expression(statement.expression, scope)
                is IfStatement -> {
                    requireType(expression(statement.condition, scope), "Boolean", statement.condition)
                    block(statement.thenBranch, scope, expected)
                    statement.elseBranch?.let { block(it, scope, expected) }
                }
                is WhileStatement -> {
                    requireType(expression(statement.condition, scope), "Boolean", statement.condition)
                    block(statement.body, scope, expected)
                }
                is ForStatement -> {
                    val iterable = expression(statement.iterable, scope)
                    if (!iterable.startsWith("Array[") && !iterable.startsWith("List[")) fail("For requires an Array or List", statement)
                    block(statement.body, scope + (statement.variable to iterable.substringAfter('[').dropLast(1)), expected)
                }
            }
        }
    }

    private fun expression(node: Expression, scope: Map<String, String>): String = when (node) {
        is LiteralExpression -> when (node.kind) {
            TokenType.INTEGER_LITERAL -> "Integer"
            TokenType.REAL_LITERAL -> "Real"
            else -> "Boolean"
        }
        is ThisExpression -> owner.type.name
        is GroupedExpression -> expression(node.expression, scope)
        is NameExpression -> scope[node.name] ?: when {
            node.name in classes || node.name in builtins -> construct(node.name, node.typeArguments.map { type(it) }, emptyList(), node)
            hasField(owner.type.name, node.name) -> field(owner.type.name, node.name, node)
            else -> call(owner.type.name, node.name, emptyList(), node)
        }
        is MemberExpression -> {
            val receiver = if (node.receiver is NameExpression && node.receiver.name in listOf("Integer", "Real") && node.name in listOf("Min", "Max", "Epsilon")) node.receiver.name else expression(node.receiver, scope)
            if (hasField(receiver, node.name)) field(receiver, node.name, node) else call(receiver, node.name, emptyList(), node)
        }
        is CallExpression -> {
            val arguments = node.arguments.map { expression(it, scope) }
            when (val callee = node.callee) {
                is NameExpression -> if (callee.name in classes || callee.name in builtins) {
                    construct(callee.name, callee.typeArguments.map { type(it) }, arguments, node)
                } else {
                    if (callee.name in scope || hasField(owner.type.name, callee.name)) fail("Variable '${callee.name}' is not callable", callee)
                    call(owner.type.name, callee.name, arguments, node)
                }
                is MemberExpression -> call(expression(callee.receiver, scope), callee.name, arguments, node)
                else -> fail("Expression is not callable", callee)
            }
        }
        is UnaryExpression -> expression(node.operand, scope).also { if (it !in listOf("Integer", "Real")) fail("Unary arithmetic requires a number", node) }
        is BinaryExpression -> {
            val left = expression(node.left, scope)
            val right = expression(node.right, scope)
            if (node.operator == TokenType.EQUAL_EQUAL && left == "Boolean" && right == "Boolean") "Boolean" else {
                if (left !in listOf("Integer", "Real") || right !in listOf("Integer", "Real")) fail("Operator requires numeric operands", node)
                if (node.operator in listOf(TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.EQUAL_EQUAL)) "Boolean"
                else if (left == "Real" || right == "Real") "Real" else "Integer"
            }
        }
    }

    private fun construct(name: String, types: List<String>, arguments: List<String>, node: AstNode): String {
        if (name in listOf("Array", "List")) {
            if (types.size != 1) fail("$name requires one element type", node)
            val valid = if (name == "Array") arguments == listOf("Integer") else arguments.isEmpty() || arguments == listOf(types[0]) || arguments == listOf(types[0], "Integer")
            if (!valid) fail("Invalid $name constructor arguments", node)
            return "$name[${types[0]}]"
        }
        if (types.isNotEmpty()) fail("$name does not take type arguments", node)
        if (name in listOf("Integer", "Real", "Boolean")) {
            val allowed = if (name == "Boolean") listOf("Boolean") else listOf("Integer", "Real")
            if (arguments.size != 1 || arguments[0] !in allowed) fail("Invalid $name constructor arguments", node)
        } else if (name in classes) {
            val constructors = classes.getValue(name).members.filterIsInstance<ConstructorDeclaration>()
            if (constructors.isEmpty()) {
                if (arguments.isNotEmpty()) fail("No matching constructor for $name", node)
            } else select(constructors.map { it.parameters.map { p -> type(p.type) } }, arguments, node)
        } else fail("Cannot construct $name", node)
        return name
    }

    private fun call(receiver: String, name: String, arguments: List<String>, node: AstNode): String {
        if (receiver in listOf("Integer", "Real")) {
            if (name in listOf("Plus", "Minus", "Mult", "Div", "Rem", "Less", "LessEqual", "Greater", "GreaterEqual", "Equal")) {
                if (arguments.size != 1 || arguments[0] !in listOf("Integer", "Real") || name == "Rem" && arguments[0] != "Integer") fail("Invalid arguments for $name", node)
                return if (name in listOf("Less", "LessEqual", "Greater", "GreaterEqual", "Equal")) "Boolean" else if (receiver == "Real" || arguments[0] == "Real") "Real" else "Integer"
            }
            if (arguments.isEmpty()) when (name) {
                "UnaryMinus" -> return receiver
                "toReal" -> if (receiver == "Integer") return "Real"
                "toInteger" -> if (receiver == "Real") return "Integer"
                "toBoolean" -> if (receiver == "Integer") return "Boolean"
            }
        }
        if (receiver == "Boolean") {
            if (name in listOf("And", "Or", "Xor") && arguments == listOf("Boolean")) return "Boolean"
            if (name == "Not" && arguments.isEmpty()) return "Boolean"
            if (name == "toInteger" && arguments.isEmpty()) return "Integer"
        }
        if (receiver.startsWith("Array[") || receiver.startsWith("List[")) {
            val element = receiver.substringAfter('[').dropLast(1)
            if (receiver.startsWith("Array[")) when (name) {
                "Length" -> if (arguments.isEmpty()) return "Integer"
                "get" -> if (arguments == listOf("Integer")) return element
                "set" -> if (arguments.size == 2 && arguments[0] == "Integer" && assignable(arguments[1], element)) return "Void"
                "toList" -> if (arguments.isEmpty()) return "List[$element]"
            } else when (name) {
                "head" -> if (arguments.isEmpty()) return element
                "tail" -> if (arguments.isEmpty()) return receiver
                "append" -> if (arguments.size == 1 && assignable(arguments[0], element)) return receiver
            }
        }
        val candidates = methods(receiver, name)
        val selected = select(candidates.map { it.parameters.map { p -> type(p.type) } }, arguments, node)
        return candidates[selected].returnType?.let { type(it) } ?: "Void"
    }

    private fun select(signatures: List<List<String>>, arguments: List<String>, node: AstNode): Int {
        val matches = signatures.indices.filter { i -> signatures[i].size == arguments.size && arguments.indices.all { assignable(arguments[it], signatures[i][it]) } }
        val best = matches.filter { i -> matches.all { j -> signatures[i].indices.all { assignable(signatures[i][it], signatures[j][it]) } } }
        if (best.size != 1) fail(if (matches.isEmpty()) "No matching call signature" else "Ambiguous call", node)
        return best.single()
    }

    private fun methods(name: String?, method: String): List<MethodDeclaration> {
        if (name == null) return emptyList()
        val own = classes[name]?.members?.filterIsInstance<MethodDeclaration>()?.filter { it.name == method } ?: emptyList()
        val signatures = own.map { it.parameters.map { p -> type(p.type) } }
        return own + methods(parent(name), method).filter { it.parameters.map { p -> type(p.type) } !in signatures }
    }

    private fun hasField(name: String?, field: String): Boolean = name != null && (
        classes[name]?.members?.filterIsInstance<VariableDeclaration>()?.any { it.name == field } == true ||
            name in listOf("Integer", "Real") && field in listOf("Min", "Max") || name == "Real" && field == "Epsilon" || hasField(parent(name), field)
        )

    private fun field(name: String, field: String, node: AstNode): String {
        if (name in listOf("Integer", "Real") && field in listOf("Min", "Max") || name == "Real" && field == "Epsilon") return name
        fields[name]?.get(field)?.let { return it }
        val declaration = classes[name]?.members?.filterIsInstance<VariableDeclaration>()?.firstOrNull { it.name == field }
        if (declaration == null) return parent(name)?.let { field(it, field, node) } ?: fail("Unknown variable or field '$field'", node)
        val key = "$name.$field"
        if (!activeFields.add(key)) fail("Cyclic field initialization '$key'", declaration)
        val previous = owner
        owner = classes.getValue(name)
        val result = expression(declaration.initializer, emptyMap())
        owner = previous
        if (result == "Void") fail("A field needs a value", declaration)
        activeFields.remove(key)
        fields.getOrPut(name) { mutableMapOf() }[field] = result
        return result
    }

    private fun type(node: TypeReference): String {
        if (node.name !in builtins && node.name !in classes) fail("Unknown type '${node.name}'", node)
        if (node.name in listOf("Array", "List")) {
            if (node.arguments.size != 1) fail("${node.name} requires one element type", node)
            return "${node.name}[${type(node.arguments.single())}]"
        }
        if (node.arguments.isNotEmpty()) fail("Unexpected type arguments", node)
        return node.name
    }

    private fun parent(name: String): String? = classes[name]?.parent?.name ?: if (name in classes) "Class" else parents[name]

    private fun assignable(actual: String, expected: String): Boolean {
        var current: String? = actual
        while (current != null) {
            if (current == expected) return true
            current = if (current.startsWith("Array[") || current.startsWith("List[")) "AnyRef" else parent(current)
        }
        return false
    }

    private fun requireType(actual: String, expected: String, node: AstNode) {
        if (!assignable(actual, expected)) fail("Expected $expected, found $actual", node)
    }

    private fun returns(block: Block): Boolean = block.statements.any { statement ->
        statement is ReturnStatement || statement is IfStatement && statement.elseBranch != null && returns(statement.thenBranch) && returns(statement.elseBranch)
    }

    private fun fail(message: String, node: AstNode): Nothing = throw SemanticException(message, node.span.start)
}