package team.niiby.compiler.analyzer

import team.niiby.compiler.lexer.TokenType
import team.niiby.compiler.parser.AssignmentStatement
import team.niiby.compiler.parser.BinaryExpression
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.CallExpression
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
import team.niiby.compiler.parser.Program
import team.niiby.compiler.parser.ReturnStatement
import team.niiby.compiler.parser.Statement
import team.niiby.compiler.parser.UnaryExpression
import team.niiby.compiler.parser.VariableDeclaration
import team.niiby.compiler.parser.WhileStatement

class Optimizer {
    fun optimize(program: Program): Program = program.copy(classes = program.classes.map { declaration ->
        declaration.copy(members = declaration.members.map { member ->
            when (member) {
                is VariableDeclaration -> member.copy(initializer = expression(member.initializer))
                is ConstructorDeclaration -> member.copy(body = block(member.body))
                is MethodDeclaration -> member.copy(body = when (val body = member.body) {
                    is Block -> block(body)
                    is ExpressionBody -> body.copy(expression = expression(body.expression))
                })
            }
        })
    })

    private fun block(node: Block): Block {
        val statements = mutableListOf<Statement>()
        for (statement in node.statements) {
            val optimized: Statement? = when (statement) {
                is VariableDeclaration -> statement.copy(initializer = expression(statement.initializer))
                is AssignmentStatement -> statement.copy(target = expression(statement.target), value = expression(statement.value))
                is ReturnStatement -> statement.copy(value = statement.value?.let { expression(it) })
                is ExpressionStatement -> statement.copy(expression = expression(statement.expression))
                is Block -> block(statement)
                is ForStatement -> statement.copy(iterable = expression(statement.iterable), body = block(statement.body))
                is WhileStatement -> {
                    val condition = expression(statement.condition)
                    if (boolean(condition) == false) null else statement.copy(condition = condition, body = block(statement.body))
                }
                is IfStatement -> {
                    val condition = expression(statement.condition)
                    when (boolean(condition)) {
                        true -> block(statement.thenBranch)
                        false -> statement.elseBranch?.let { block(it) }
                        null -> statement.copy(condition = condition, thenBranch = block(statement.thenBranch), elseBranch = statement.elseBranch?.let { block(it) })
                    }
                }
            }
            if (optimized != null) {
                statements.add(optimized)
                if (terminates(optimized)) break
            }
        }
        return node.copy(statements = statements)
    }

    private fun terminates(node: Statement): Boolean = when (node) {
        is ReturnStatement -> true
        is Block -> node.statements.any { terminates(it) }
        is IfStatement -> node.elseBranch != null && terminates(node.thenBranch) && terminates(node.elseBranch)
        is WhileStatement -> boolean(node.condition) == true
        else -> false
    }

    private fun boolean(node: Expression): Boolean? = (node as? LiteralExpression)?.takeIf { it.kind == TokenType.BOOLEAN_LITERAL }?.lexeme?.toBooleanStrictOrNull()

    private fun expression(node: Expression): Expression = when (node) {
        is GroupedExpression -> expression(node.expression).let { if (it is LiteralExpression) it.copy(span = node.span) else node.copy(expression = it) }
        is UnaryExpression -> {
            val operand = expression(node.operand)
            val literal = operand as? LiteralExpression
            val folded = if (literal?.kind == TokenType.INTEGER_LITERAL) {
                literal.lexeme.toIntOrNull()?.let { value ->
                    if (node.operator == TokenType.PLUS) value.toString() else if (value != Int.MIN_VALUE) (-value).toString() else null
                }
            } else if (literal?.kind == TokenType.REAL_LITERAL) {
                literal.lexeme.toDoubleOrNull()?.let { if (node.operator == TokenType.MINUS) -it else it }?.takeIf { it.isFinite() }?.toString()
            } else null
            if (folded == null) node.copy(operand = operand) else LiteralExpression(folded, literal!!.kind, node.span)
        }
        is BinaryExpression -> fold(node.copy(left = expression(node.left), right = expression(node.right)))
        is MemberExpression -> node.copy(receiver = expression(node.receiver))
        is CallExpression -> {
            val call = node.copy(callee = expression(node.callee), arguments = node.arguments.map { expression(it) })
            val member = call.callee as? MemberExpression
            val operator = when (member?.name) {
                "Plus" -> TokenType.PLUS
                "Minus" -> TokenType.MINUS
                "Mult" -> TokenType.STAR
                "Div" -> TokenType.SLASH
                "Less" -> TokenType.LESS
                "LessEqual" -> TokenType.LESS_EQUAL
                "Greater" -> TokenType.GREATER
                "GreaterEqual" -> TokenType.GREATER_EQUAL
                "Equal" -> TokenType.EQUAL_EQUAL
                else -> null
            }
            if (operator != null && call.arguments.size == 1 && member!!.receiver is LiteralExpression) {
                val folded = fold(BinaryExpression(member.receiver, operator, call.arguments.single(), node.span))
                if (folded is LiteralExpression) folded else call
            } else call
        }
        else -> node
    }

    private fun fold(node: BinaryExpression): Expression {
        val left = node.left as? LiteralExpression ?: return node
        val right = node.right as? LiteralExpression ?: return node
        if (left.kind == TokenType.BOOLEAN_LITERAL || right.kind == TokenType.BOOLEAN_LITERAL) {
            return if (left.kind == right.kind && node.operator == TokenType.EQUAL_EQUAL) LiteralExpression((left.lexeme == right.lexeme).toString(), TokenType.BOOLEAN_LITERAL, node.span) else node
        }
        if (left.kind == TokenType.INTEGER_LITERAL && left.lexeme.toIntOrNull() == null || right.kind == TokenType.INTEGER_LITERAL && right.lexeme.toIntOrNull() == null) return node
        val a = left.lexeme.toDoubleOrNull() ?: return node
        val b = right.lexeme.toDoubleOrNull() ?: return node
        if (!a.isFinite() || !b.isFinite()) return node
        val comparison = when (node.operator) {
            TokenType.LESS -> a < b
            TokenType.LESS_EQUAL -> a <= b
            TokenType.GREATER -> a > b
            TokenType.GREATER_EQUAL -> a >= b
            TokenType.EQUAL_EQUAL -> a == b
            else -> null
        }
        if (comparison != null) return LiteralExpression(comparison.toString(), TokenType.BOOLEAN_LITERAL, node.span)
        if (node.operator == TokenType.SLASH && b == 0.0) return node
        if (left.kind == TokenType.INTEGER_LITERAL && right.kind == TokenType.INTEGER_LITERAL) {
            val x = left.lexeme.toIntOrNull()?.toLong() ?: return node
            val y = right.lexeme.toIntOrNull()?.toLong() ?: return node
            val result = when (node.operator) {
                TokenType.PLUS -> x + y
                TokenType.MINUS -> x - y
                TokenType.STAR -> x * y
                TokenType.SLASH -> x / y
                else -> return node
            }
            if (result !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return node
            return LiteralExpression(result.toString(), TokenType.INTEGER_LITERAL, node.span)
        }
        val result = when (node.operator) {
            TokenType.PLUS -> a + b
            TokenType.MINUS -> a - b
            TokenType.STAR -> a * b
            TokenType.SLASH -> a / b
            else -> return node
        }
        return if (result.isFinite()) LiteralExpression(result.toString(), TokenType.REAL_LITERAL, node.span) else node
    }
}