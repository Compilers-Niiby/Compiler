package team.niiby.compiler.lexer

class Lexer(private val source: String) {
    private var index = 0
    private var line = 1
    private var column = 1

    fun tokenize(): List<Token> = buildList {
        while (true) {
            skipTrivia()
            if (isAtEnd()) {
                val position = position()
                add(Token(TokenType.EOF, "", SourceSpan(position, position)))
                break
            }

            val start = position()
            val token = when {
                current().isIdentifierStart() -> scanIdentifier(start)
                current().isDigit() -> scanNumber(start)
                else -> scanSymbol(start)
            }
            add(token)
        }
    }

    private fun skipTrivia() {
        while (!isAtEnd()) {
            when {
                current().isWhitespace() -> advance()
                current() == '/' && peek() == '/' -> skipLineComment()
                current() == '/' && peek() == '*' -> skipBlockComment()
                else -> return
            }
        }
    }

    private fun skipLineComment() {
        advance()
        advance()
        while (!isAtEnd() && current() != '\n' && current() != '\r') {
            advance()
        }
    }

    private fun skipBlockComment() {
        val start = position()
        advance()
        advance()
        while (!isAtEnd()) {
            if (current() == '*' && peek() == '/') {
                advance()
                advance()
                return
            }
            advance()
        }
        throw LexerException("Unterminated block comment", start)
    }

    private fun scanIdentifier(start: SourcePosition): Token {
        advance()
        while (!isAtEnd() && current().isIdentifierPart()) {
            advance()
        }

        val lexeme = source.substring(start.offset, index)
        val type = KEYWORDS[lexeme] ?: TokenType.IDENTIFIER
        return token(type, lexeme, start)
    }

    private fun scanNumber(start: SourcePosition): Token {
        while (!isAtEnd() && current().isDigit()) {
            advance()
        }

        var isReal = false
        if (!isAtEnd() && current() == '.' && peek()?.isDigit() == true) {
            isReal = true
            advance()
            while (!isAtEnd() && current().isDigit()) {
                advance()
            }
        }

        if (!isAtEnd() && (current() == 'e' || current() == 'E')) {
            isReal = true
            val exponentStart = position()
            advance()
            if (!isAtEnd() && (current() == '+' || current() == '-')) {
                advance()
            }
            if (isAtEnd() || !current().isDigit()) {
                throw LexerException("Exponent must contain at least one digit", exponentStart)
            }
            while (!isAtEnd() && current().isDigit()) {
                advance()
            }
        }

        val lexeme = source.substring(start.offset, index)
        val type = if (isReal) TokenType.REAL_LITERAL else TokenType.INTEGER_LITERAL
        return token(type, lexeme, start)
    }

    private fun scanSymbol(start: SourcePosition): Token {
        val twoCharacterType = when (source.substring(index, minOf(index + 2, source.length))) {
            ":=" -> TokenType.ASSIGN
            "=>" -> TokenType.FAT_ARROW
            "<=" -> TokenType.LESS_EQUAL
            ">=" -> TokenType.GREATER_EQUAL
            "==" -> TokenType.EQUAL_EQUAL
            else -> null
        }
        if (twoCharacterType != null) {
            advance()
            advance()
            return token(twoCharacterType, source.substring(start.offset, index), start)
        }

        val type = SINGLE_CHARACTER_TOKENS[current()]
            ?: throw LexerException("Unexpected character '${current()}'", start)
        advance()
        return token(type, source.substring(start.offset, index), start)
    }

    private fun token(type: TokenType, lexeme: String, start: SourcePosition) =
        Token(type, lexeme, SourceSpan(start, position()))

    private fun current(): Char = source[index]

    private fun peek(distance: Int = 1): Char? = source.getOrNull(index + distance)

    private fun isAtEnd(): Boolean = index >= source.length

    private fun position() = SourcePosition(index, line, column)

    private fun advance() {
        if (isAtEnd()) return

        when (source[index]) {
            '\r' -> {
                index++
                if (!isAtEnd() && source[index] == '\n') index++
                line++
                column = 1
            }
            '\n' -> {
                index++
                line++
                column = 1
            }
            else -> {
                index++
                column++
            }
        }
    }

    private fun Char.isIdentifierStart() = this == '_' || isLetter()

    private fun Char.isIdentifierPart() = this == '_' || isLetterOrDigit()

    private companion object {
        val KEYWORDS = mapOf(
            "class" to TokenType.CLASS,
            "extends" to TokenType.EXTENDS,
            "is" to TokenType.IS,
            "end" to TokenType.END,
            "var" to TokenType.VAR,
            "method" to TokenType.METHOD,
            "this" to TokenType.THIS,
            "while" to TokenType.WHILE,
            "loop" to TokenType.LOOP,
            "if" to TokenType.IF,
            "then" to TokenType.THEN,
            "else" to TokenType.ELSE,
            "return" to TokenType.RETURN,
            "true" to TokenType.BOOLEAN_LITERAL,
            "false" to TokenType.BOOLEAN_LITERAL,
            "for" to TokenType.FOR,
            "in" to TokenType.IN,
        )

        val SINGLE_CHARACTER_TOKENS = mapOf(
            '(' to TokenType.LEFT_PAREN,
            ')' to TokenType.RIGHT_PAREN,
            '[' to TokenType.LEFT_BRACKET,
            ']' to TokenType.RIGHT_BRACKET,
            ',' to TokenType.COMMA,
            ':' to TokenType.COLON,
            '.' to TokenType.DOT,
            '+' to TokenType.PLUS,
            '-' to TokenType.MINUS,
            '*' to TokenType.STAR,
            '/' to TokenType.SLASH,
            '<' to TokenType.LESS,
            '>' to TokenType.GREATER,
        )
    }
}
