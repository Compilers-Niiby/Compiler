package team.niiby.compiler.lexer

internal fun escapedStringCharacter(character: Char): Char? = when (character) {
    '"', '\\' -> character
    'n' -> '\n'
    'r' -> '\r'
    't' -> '\t'
    else -> null
}

internal fun decodeStringLiteral(lexeme: String): String = buildString {
    var index = 1
    while (index < lexeme.lastIndex) {
        val character = lexeme[index++]
        append(if (character == '\\') checkNotNull(escapedStringCharacter(lexeme[index++])) else character)
    }
}
