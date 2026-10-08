package team.niiby.compiler.parser

import team.niiby.compiler.lexer.SourcePosition

class ParserException(
    message: String,
    val position: SourcePosition,
) : RuntimeException("$message at ${position.line}:${position.column}")
