package team.niiby.compiler.lexer

enum class TokenType {
    // Words and literals
    IDENTIFIER,
    INTEGER_LITERAL,
    REAL_LITERAL,
    BOOLEAN_LITERAL,

    // Core O keywords
    CLASS,
    EXTENDS,
    IS,
    END,
    VAR,
    METHOD,
    THIS,
    WHILE,
    LOOP,
    IF,
    THEN,
    ELSE,
    RETURN,

    // Team Niiby language extension
    FOR,
    IN,

    // Delimiters
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COMMA,
    COLON,
    DOT,

    // Assignment, short method body, and infix operators
    ASSIGN,
    FAT_ARROW,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    LESS,
    GREATER,
    LESS_EQUAL,
    GREATER_EQUAL,
    EQUAL_EQUAL,

    EOF,
}
