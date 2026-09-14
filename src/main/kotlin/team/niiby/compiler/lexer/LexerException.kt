package team.niiby.compiler.lexer

class LexerException(
    message: String,
    val position: SourcePosition,
) : RuntimeException("$message at ${position.line}:${position.column}")
