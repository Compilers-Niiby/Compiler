package team.niiby.compiler.lexer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LexerTest {
    @Test
    fun `empty source produces only eof`() {
        assertTypes("", TokenType.EOF)
    }

    @Test
    fun `recognizes every core keyword`() {
        assertTypes(
            "class extends is end var method this while loop if then else return",
            TokenType.CLASS,
            TokenType.EXTENDS,
            TokenType.IS,
            TokenType.END,
            TokenType.VAR,
            TokenType.METHOD,
            TokenType.THIS,
            TokenType.WHILE,
            TokenType.LOOP,
            TokenType.IF,
            TokenType.THEN,
            TokenType.ELSE,
            TokenType.RETURN,
            TokenType.EOF,
        )
    }

    @Test
    fun `recognizes extension keywords`() {
        assertTypes("for item in items", TokenType.FOR, TokenType.IDENTIFIER, TokenType.IN, TokenType.IDENTIFIER, TokenType.EOF)
    }

    @Test
    fun `keywords are case sensitive`() {
        assertTypes("Class TRUE", TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.EOF)
    }

    @Test
    fun `recognizes boolean literals`() {
        assertTypes("true false", TokenType.BOOLEAN_LITERAL, TokenType.BOOLEAN_LITERAL, TokenType.EOF)
    }

    @Test
    fun `recognizes unicode identifiers`() {
        assertTypes("result_2 значение", TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.EOF)
    }

    @Test
    fun `recognizes integer and real literals`() {
        assertTypes("0 42 3.14 6e2 1.5E-3", TokenType.INTEGER_LITERAL, TokenType.INTEGER_LITERAL, TokenType.REAL_LITERAL, TokenType.REAL_LITERAL, TokenType.REAL_LITERAL, TokenType.EOF)
    }

    @Test
    fun `dot after integer starts member access`() {
        val tokens = Lexer("2.Plus(3)").tokenize()
        assertEquals(listOf("2", ".", "Plus", "(", "3", ")", ""), tokens.map { it.lexeme })
        assertEquals(listOf(TokenType.INTEGER_LITERAL, TokenType.DOT, TokenType.IDENTIFIER, TokenType.LEFT_PAREN, TokenType.INTEGER_LITERAL, TokenType.RIGHT_PAREN, TokenType.EOF), tokens.map { it.type })
    }

    @Test
    fun `recognizes all delimiters`() {
        assertTypes("()[], :.", TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN, TokenType.LEFT_BRACKET, TokenType.RIGHT_BRACKET, TokenType.COMMA, TokenType.COLON, TokenType.DOT, TokenType.EOF)
    }

    @Test
    fun `recognizes assignment and short method body`() {
        assertTypes(":= =>", TokenType.ASSIGN, TokenType.FAT_ARROW, TokenType.EOF)
    }

    @Test
    fun `uses maximal munch for comparison operators`() {
        assertTypes("< <= > >= ==", TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.EQUAL_EQUAL, TokenType.EOF)
    }

    @Test
    fun `recognizes arithmetic operators`() {
        assertTypes("+ - * /", TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.EOF)
    }

    @Test
    fun `skips line comments`() {
        assertTypes("var // a comment\nanswer", TokenType.VAR, TokenType.IDENTIFIER, TokenType.EOF)
    }

    @Test
    fun `skips multiline block comments and tracks position`() {
        val token = Lexer("/* first\nsecond */\nclass").tokenize().first()
        assertEquals(TokenType.CLASS, token.type)
        assertEquals(3, token.line)
        assertEquals(1, token.column)
    }

    @Test
    fun `tracks crlf as a single newline`() {
        val token = Lexer("var x : 0\r\nreturn x").tokenize().first { it.type == TokenType.RETURN }
        assertEquals(2, token.line)
        assertEquals(1, token.column)
    }

    @Test
    fun `tokenizes a complete class`() {
        assertTypes(
            "class Counter is var value : 0 this is value := value + 1 end end",
            TokenType.CLASS,
            TokenType.IDENTIFIER,
            TokenType.IS,
            TokenType.VAR,
            TokenType.IDENTIFIER,
            TokenType.COLON,
            TokenType.INTEGER_LITERAL,
            TokenType.THIS,
            TokenType.IS,
            TokenType.IDENTIFIER,
            TokenType.ASSIGN,
            TokenType.IDENTIFIER,
            TokenType.PLUS,
            TokenType.INTEGER_LITERAL,
            TokenType.END,
            TokenType.END,
            TokenType.EOF,
        )
    }

    @Test
    fun `reports unexpected characters with location`() {
        val error = assertThrows(LexerException::class.java) { Lexer("var x : @").tokenize() }
        assertEquals(1, error.position.line)
        assertEquals(9, error.position.column)
    }

    @Test
    fun `reports unterminated block comments`() {
        val error = assertThrows(LexerException::class.java) { Lexer("\n/* missing end").tokenize() }
        assertEquals(2, error.position.line)
        assertEquals(1, error.position.column)
    }

    @Test
    fun `reports malformed exponents`() {
        val error = assertThrows(LexerException::class.java) { Lexer("12e+").tokenize() }
        assertEquals(1, error.position.line)
        assertEquals(3, error.position.column)
    }

    private fun assertTypes(source: String, vararg expected: TokenType) {
        assertEquals(expected.toList(), Lexer(source).tokenize().map { it.type })
    }
}
