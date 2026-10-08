package team.niiby.compiler.parser

import team.niiby.compiler.lexer.SourceSpan
import team.niiby.compiler.lexer.Token
import team.niiby.compiler.lexer.TokenType

class Parser(private val tokens: List<Token>) {
    private var index = 0

    init {
        require(tokens.isNotEmpty() && tokens.last().type == TokenType.EOF) {
            "The token stream must end with EOF"
        }
        require(tokens.dropLast(1).none { it.type == TokenType.EOF }) {
            "EOF must appear only at the end of the token stream"
        }
    }

    fun parse(): Program {
        val classes = mutableListOf<ClassDeclaration>()
        do {
            classes.add(parseClass())
        } while (!check(TokenType.EOF))
        return Program(classes, SourceSpan(classes.first().span.start, classes.last().span.end))
    }

    private fun parseClass(): ClassDeclaration {
        val start = consume(TokenType.CLASS, "'class'")
        val type = parseType()
        val parent = if (match(TokenType.EXTENDS)) parseType() else null
        consume(TokenType.IS, "'is' before class members")
        val members = mutableListOf<MemberDeclaration>()
        while (!check(TokenType.END)) {
            members.add(
                when (peek().type) {
                    TokenType.VAR -> parseVariable()
                    TokenType.METHOD -> parseMethod()
                    TokenType.THIS -> parseConstructor()
                    TokenType.EOF -> fail("Expected 'end' to close the class")
                    else -> fail("Expected a field, method, constructor, or 'end'")
                },
            )
        }
        val end = consume(TokenType.END, "'end' after class members")
        return ClassDeclaration(type, parent, members, SourceSpan(start.span.start, end.span.end))
    }

    private fun parseType(): TypeReference {
        val name = consume(TokenType.IDENTIFIER, "a type name")
        val arguments = parseTypeArguments()
        return TypeReference(name.lexeme, arguments, SourceSpan(name.span.start, previous().span.end))
    }

    private fun parseTypeArguments(): List<TypeReference> {
        if (!match(TokenType.LEFT_BRACKET)) return emptyList()
        val arguments = mutableListOf<TypeReference>()
        do {
            arguments.add(parseType())
        } while (match(TokenType.COMMA))
        consume(TokenType.RIGHT_BRACKET, "']' after type arguments")
        return arguments
    }

    private fun parseVariable(): VariableDeclaration {
        val start = consume(TokenType.VAR, "'var'")
        val name = consume(TokenType.IDENTIFIER, "a variable name")
        consume(TokenType.COLON, "':' before a variable initializer")
        val initializer = parseExpression()
        return VariableDeclaration(name.lexeme, initializer, SourceSpan(start.span.start, initializer.span.end))
    }

    private fun parseMethod(): MethodDeclaration {
        val start = consume(TokenType.METHOD, "'method'")
        val name = consume(TokenType.IDENTIFIER, "a method name")
        val parameters = parseParameters()
        val returnType = if (match(TokenType.COLON)) parseType() else null
        val body: MethodBody = when {
            match(TokenType.IS) -> {
                val block = parseBlock()
                consume(TokenType.END, "'end' after the method body")
                block
            }
            match(TokenType.FAT_ARROW) -> {
                val arrow = previous()
                val expression = parseExpression()
                ExpressionBody(expression, SourceSpan(arrow.span.start, expression.span.end))
            }
            else -> fail("Expected 'is' or '=>' before the method body")
        }
        return MethodDeclaration(
            name.lexeme,
            parameters,
            returnType,
            body,
            SourceSpan(start.span.start, previous().span.end),
        )
    }

    private fun parseConstructor(): ConstructorDeclaration {
        val start = consume(TokenType.THIS, "'this'")
        val parameters = parseParameters()
        consume(TokenType.IS, "'is' before the constructor body")
        val body = parseBlock()
        val end = consume(TokenType.END, "'end' after the constructor body")
        return ConstructorDeclaration(parameters, body, SourceSpan(start.span.start, end.span.end))
    }

    private fun parseParameters(): List<Parameter> {
        if (!match(TokenType.LEFT_PAREN)) return emptyList()
        val parameters = mutableListOf<Parameter>()
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                val name = consume(TokenType.IDENTIFIER, "a parameter name")
                consume(TokenType.COLON, "':' before a parameter type")
                val type = parseType()
                parameters.add(Parameter(name.lexeme, type, SourceSpan(name.span.start, type.span.end)))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RIGHT_PAREN, "')' after parameters")
        return parameters
    }

    private fun parseBlock(stopAtElse: Boolean = false): Block {
        val start = peek().span.start
        val statements = mutableListOf<Statement>()
        while (!check(TokenType.END) && !(stopAtElse && check(TokenType.ELSE))) {
            if (check(TokenType.EOF)) fail("Expected 'end' to close the block")
            statements.add(parseStatement())
        }
        val end = statements.lastOrNull()?.span?.end ?: start
        return Block(statements, SourceSpan(start, end))
    }

    private fun parseStatement(): Statement = when (peek().type) {
        TokenType.VAR -> parseVariable()
        TokenType.WHILE -> parseWhile()
        TokenType.FOR -> parseFor()
        TokenType.IF -> parseIf()
        TokenType.RETURN -> parseReturn()
        else -> parseExpressionStatement()
    }

    private fun parseWhile(): WhileStatement {
        val start = consume(TokenType.WHILE, "'while'")
        val condition = parseExpression()
        consume(TokenType.LOOP, "'loop' after a while condition")
        val body = parseBlock()
        val end = consume(TokenType.END, "'end' after the while body")
        return WhileStatement(condition, body, SourceSpan(start.span.start, end.span.end))
    }

    private fun parseFor(): ForStatement {
        val start = consume(TokenType.FOR, "'for'")
        val variable = consume(TokenType.IDENTIFIER, "a for-loop variable")
        consume(TokenType.IN, "'in' after the for-loop variable")
        val iterable = parseExpression()
        consume(TokenType.LOOP, "'loop' after the iterable")
        val body = parseBlock()
        val end = consume(TokenType.END, "'end' after the for body")
        return ForStatement(variable.lexeme, iterable, body, SourceSpan(start.span.start, end.span.end))
    }

    private fun parseIf(): IfStatement {
        val start = consume(TokenType.IF, "'if'")
        val condition = parseExpression()
        consume(TokenType.THEN, "'then' after an if condition")
        val thenBranch = parseBlock(stopAtElse = true)
        val elseBranch = if (match(TokenType.ELSE)) parseBlock() else null
        val end = consume(TokenType.END, "'end' after the if branches")
        return IfStatement(condition, thenBranch, elseBranch, SourceSpan(start.span.start, end.span.end))
    }

    private fun parseReturn(): ReturnStatement {
        val start = consume(TokenType.RETURN, "'return'")
        val value = if (canStartExpression(peek().type)) parseExpression() else null
        return ReturnStatement(value, SourceSpan(start.span.start, value?.span?.end ?: start.span.end))
    }

    private fun parseExpressionStatement(): Statement {
        val expression = parseExpression()
        if (!match(TokenType.ASSIGN)) return ExpressionStatement(expression, expression.span)
        if (expression !is MemberExpression &&
            !(expression is NameExpression && expression.typeArguments.isEmpty())
        ) {
            throw ParserException("Expected a variable or member as the assignment target", expression.span.start)
        }
        val value = parseExpression()
        return AssignmentStatement(expression, value, SourceSpan(expression.span.start, value.span.end))
    }

    private fun parseExpression(): Expression = parseEquality()

    private fun parseEquality(): Expression = parseBinary(::parseComparison, TokenType.EQUAL_EQUAL)

    private fun parseComparison(): Expression = parseBinary(
        ::parseAddition,
        TokenType.LESS,
        TokenType.GREATER,
        TokenType.LESS_EQUAL,
        TokenType.GREATER_EQUAL,
    )

    private fun parseAddition(): Expression = parseBinary(::parseMultiplication, TokenType.PLUS, TokenType.MINUS)

    private fun parseMultiplication(): Expression = parseBinary(::parseUnary, TokenType.STAR, TokenType.SLASH)

    private fun parseBinary(operand: () -> Expression, vararg operators: TokenType): Expression {
        var expression = operand()
        while (peek().type in operators) {
            val operator = advance()
            val right = operand()
            expression = BinaryExpression(
                expression,
                operator.type,
                right,
                SourceSpan(expression.span.start, right.span.end),
            )
        }
        return expression
    }

    private fun parseUnary(): Expression {
        if (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val operator = advance()
            val operand = parseUnary()
            return UnaryExpression(operator.type, operand, SourceSpan(operator.span.start, operand.span.end))
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expression {
        var expression = parsePrimary()
        while (true) {
            expression = when {
                match(TokenType.DOT) -> {
                    val name = consume(TokenType.IDENTIFIER, "a member name after '.'")
                    MemberExpression(expression, name.lexeme, SourceSpan(expression.span.start, name.span.end))
                }
                match(TokenType.LEFT_PAREN) -> {
                    val arguments = mutableListOf<Expression>()
                    if (!check(TokenType.RIGHT_PAREN)) {
                        do {
                            arguments.add(parseExpression())
                        } while (match(TokenType.COMMA))
                    }
                    val end = consume(TokenType.RIGHT_PAREN, "')' after arguments")
                    CallExpression(expression, arguments, SourceSpan(expression.span.start, end.span.end))
                }
                else -> return expression
            }
        }
    }

    private fun parsePrimary(): Expression = when (peek().type) {
        TokenType.INTEGER_LITERAL, TokenType.REAL_LITERAL, TokenType.BOOLEAN_LITERAL -> {
            val literal = advance()
            LiteralExpression(literal.lexeme, literal.type, literal.span)
        }
        TokenType.IDENTIFIER -> {
            val name = advance()
            val arguments = parseTypeArguments()
            NameExpression(name.lexeme, arguments, SourceSpan(name.span.start, previous().span.end))
        }
        TokenType.THIS -> ThisExpression(advance().span)
        TokenType.LEFT_PAREN -> {
            val start = advance()
            val expression = parseExpression()
            val end = consume(TokenType.RIGHT_PAREN, "')' after the expression")
            GroupedExpression(expression, SourceSpan(start.span.start, end.span.end))
        }
        else -> fail("Expected an expression")
    }

    private fun canStartExpression(type: TokenType): Boolean = when (type) {
        TokenType.INTEGER_LITERAL, TokenType.REAL_LITERAL, TokenType.BOOLEAN_LITERAL,
        TokenType.IDENTIFIER, TokenType.THIS, TokenType.LEFT_PAREN, TokenType.PLUS, TokenType.MINUS,
        -> true
        else -> false
    }

    private fun peek(): Token = tokens[index]

    private fun previous(): Token = tokens[index - 1]

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun advance(): Token = tokens[index++]

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun consume(type: TokenType, expected: String): Token {
        if (check(type)) return advance()
        fail("Expected $expected")
    }

    private fun fail(message: String): Nothing {
        val token = peek()
        val found = if (token.type == TokenType.EOF) "end of input" else "'${token.lexeme}'"
        throw ParserException("$message, found $found", token.span.start)
    }
}
