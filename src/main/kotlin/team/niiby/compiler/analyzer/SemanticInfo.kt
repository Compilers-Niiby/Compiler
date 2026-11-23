package team.niiby.compiler.analyzer

import java.util.IdentityHashMap
import team.niiby.compiler.parser.AstNode
import team.niiby.compiler.parser.ConstructorDeclaration
import team.niiby.compiler.parser.Expression
import team.niiby.compiler.parser.MethodDeclaration

internal class SemanticInfo {
    val types = IdentityHashMap<Expression, String>()
    val fields = IdentityHashMap<AstNode, ResolvedField>()
    val methods = IdentityHashMap<AstNode, ResolvedMethod>()
    val constructors = IdentityHashMap<AstNode, ResolvedConstructor>()
}

internal data class ResolvedField(val owner: String, val name: String, val type: String)
internal data class ResolvedMethod(val owner: String, val declaration: MethodDeclaration)
internal data class ResolvedConstructor(val owner: String, val declaration: ConstructorDeclaration?)
