package team.niiby.compiler.analyzer

import java.util.IdentityHashMap
import team.niiby.compiler.parser.AstNode
import team.niiby.compiler.parser.ConstructorDeclaration
import team.niiby.compiler.parser.Expression
import team.niiby.compiler.parser.MethodDeclaration

// A qualified name keeps string literal types distinct from names declared in O source.
internal const val STRING_VALUE_TYPE = "java.lang.String"

internal class SemanticInfo {
    val types = IdentityHashMap<Expression, String>()
    val fields = IdentityHashMap<AstNode, ResolvedField>()
    val methods = IdentityHashMap<AstNode, ResolvedMethod>()
    val constructors = IdentityHashMap<AstNode, ResolvedConstructor>()
    val consoleCalls = IdentityHashMap<AstNode, ConsoleCall>()
}

internal enum class ConsoleCall { READ_INTEGER, PRINTLN }

internal data class ResolvedField(val owner: String, val name: String, val type: String)
internal data class ResolvedMethod(val owner: String, val declaration: MethodDeclaration)
internal data class ResolvedConstructor(val owner: String, val declaration: ConstructorDeclaration?)
