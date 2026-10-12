package team.niiby.compiler.parser

import team.niiby.compiler.lexer.SourceSpan
import team.niiby.compiler.lexer.TokenType

sealed interface AstNode {
    val span: SourceSpan
}

data class Program(
    val classes: List<ClassDeclaration>,
    override val span: SourceSpan,
) : AstNode

data class TypeReference(
    val name: String,
    val arguments: List<TypeReference>,
    override val span: SourceSpan,
) : AstNode

data class ClassDeclaration(
    val type: TypeReference,
    val parent: TypeReference?,
    val members: List<MemberDeclaration>,
    override val span: SourceSpan,
) : AstNode

sealed interface MemberDeclaration : AstNode
sealed interface Statement : AstNode
sealed interface Expression : AstNode
sealed interface MethodBody : AstNode

data class VariableDeclaration(
    val name: String,
    val initializer: Expression,
    override val span: SourceSpan,
) : MemberDeclaration, Statement

data class Parameter(
    val name: String,
    val type: TypeReference,
    override val span: SourceSpan,
) : AstNode

data class MethodDeclaration(
    val name: String,
    val parameters: List<Parameter>,
    val returnType: TypeReference?,
    val body: MethodBody,
    override val span: SourceSpan,
) : MemberDeclaration

data class ConstructorDeclaration(
    val parameters: List<Parameter>,
    val body: Block,
    override val span: SourceSpan,
) : MemberDeclaration

data class Block(
    val statements: List<Statement>,
    override val span: SourceSpan,
) : MethodBody

data class ExpressionBody(
    val expression: Expression,
    override val span: SourceSpan,
) : MethodBody

data class AssignmentStatement(
    val target: Expression,
    val value: Expression,
    override val span: SourceSpan,
) : Statement

data class WhileStatement(
    val condition: Expression,
    val body: Block,
    override val span: SourceSpan,
) : Statement

data class ForStatement(
    val variable: String,
    val iterable: Expression,
    val body: Block,
    override val span: SourceSpan,
) : Statement

data class IfStatement(
    val condition: Expression,
    val thenBranch: Block,
    val elseBranch: Block?,
    override val span: SourceSpan,
) : Statement

data class ReturnStatement(
    val value: Expression?,
    override val span: SourceSpan,
) : Statement

data class ExpressionStatement(
    val expression: Expression,
    override val span: SourceSpan,
) : Statement

data class NameExpression(
    val name: String,
    val typeArguments: List<TypeReference>,
    override val span: SourceSpan,
) : Expression

data class ThisExpression(override val span: SourceSpan) : Expression

data class LiteralExpression(
    val lexeme: String,
    val kind: TokenType,
    override val span: SourceSpan,
) : Expression

data class MemberExpression(
    val receiver: Expression,
    val name: String,
    override val span: SourceSpan,
) : Expression

data class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    override val span: SourceSpan,
) : Expression

data class UnaryExpression(
    val operator: TokenType,
    val operand: Expression,
    override val span: SourceSpan,
) : Expression

data class BinaryExpression(
    val left: Expression,
    val operator: TokenType,
    val right: Expression,
    override val span: SourceSpan,
) : Expression

data class GroupedExpression(
    val expression: Expression,
    override val span: SourceSpan,
) : Expression
