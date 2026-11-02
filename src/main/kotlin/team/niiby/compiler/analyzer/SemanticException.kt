package team.niiby.compiler.analyzer

import team.niiby.compiler.lexer.SourcePosition

class SemanticException(
    message: String,
    val position: SourcePosition,
) : RuntimeException("$message at ${position.line}:${position.column}")