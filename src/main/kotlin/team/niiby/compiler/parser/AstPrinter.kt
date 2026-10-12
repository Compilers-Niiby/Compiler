package team.niiby.compiler.parser

/** A readable outline for inspecting the parser's result from the command line. */
class AstPrinter {
    fun print(program: Program): String = buildString {
        appendNode(program, 0)
    }

    private fun StringBuilder.appendNode(node: AstNode, depth: Int) {
        val label = when (node) {
            is Program -> "Program"
            is TypeReference -> "Type ${node.name}"
            is ClassDeclaration -> "Class ${node.type.name}"
            is VariableDeclaration -> "Variable ${node.name}"
            is Parameter -> "Parameter ${node.name}"
            is MethodDeclaration -> "Method ${node.name}"
            is ConstructorDeclaration -> "Constructor"
            is Block -> "Block"
            is ExpressionBody -> "Expression body"
            is AssignmentStatement -> "Assignment"
            is WhileStatement -> "While"
            is ForStatement -> "For ${node.variable}"
            is IfStatement -> "If"
            is ReturnStatement -> "Return"
            is ExpressionStatement -> "Expression statement"
            is NameExpression -> "Name ${node.name}"
            is ThisExpression -> "This"
            is LiteralExpression -> "Literal ${node.lexeme}"
            is MemberExpression -> "Member ${node.name}"
            is CallExpression -> "Call"
            is UnaryExpression -> "Unary ${node.operator}"
            is BinaryExpression -> "Binary ${node.operator}"
            is GroupedExpression -> "Group"
        }
        append("  ".repeat(depth))
        append(label)
        append(" @ ${node.span.start.line}:${node.span.start.column}\n")

        val children: List<AstNode> = when (node) {
            is Program -> node.classes
            is TypeReference -> node.arguments
            is ClassDeclaration -> listOf(node.type) + listOfNotNull(node.parent) + node.members
            is VariableDeclaration -> listOf(node.initializer)
            is Parameter -> listOf(node.type)
            is MethodDeclaration -> node.parameters + listOfNotNull(node.returnType) + node.body
            is ConstructorDeclaration -> node.parameters + node.body
            is Block -> node.statements
            is ExpressionBody -> listOf(node.expression)
            is AssignmentStatement -> listOf(node.target, node.value)
            is WhileStatement -> listOf(node.condition, node.body)
            is ForStatement -> listOf(node.iterable, node.body)
            is IfStatement -> listOf(node.condition, node.thenBranch) + listOfNotNull(node.elseBranch)
            is ReturnStatement -> listOfNotNull(node.value)
            is ExpressionStatement -> listOf(node.expression)
            is NameExpression -> node.typeArguments
            is ThisExpression, is LiteralExpression -> emptyList()
            is MemberExpression -> listOf(node.receiver)
            is CallExpression -> listOf(node.callee) + node.arguments
            is UnaryExpression -> listOf(node.operand)
            is BinaryExpression -> listOf(node.left, node.right)
            is GroupedExpression -> listOf(node.expression)
        }
        children.forEach { appendNode(it, depth + 1) }
    }
}
