package team.niiby.compiler.lexer

data class SourcePosition(
    val offset: Int,
    val line: Int,
    val column: Int,
)

data class SourceSpan(
    val start: SourcePosition,
    val end: SourcePosition,
)

data class Token(
    val type: TokenType,
    val lexeme: String,
    val span: SourceSpan,
) {
    val line: Int get() = span.start.line
    val column: Int get() = span.start.column
}
