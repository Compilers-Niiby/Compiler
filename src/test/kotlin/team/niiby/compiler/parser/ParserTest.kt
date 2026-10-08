package team.niiby.compiler.parser

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.lexer.SourcePosition
import team.niiby.compiler.lexer.TokenType

class ParserTest {
    @Test
    fun `token input requires exactly one final eof`() {
        val tokens = Lexer("class A is end").tokenize()
        assertThrows(IllegalArgumentException::class.java) { Parser(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Parser(tokens.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { Parser(tokens + tokens.last()) }
    }

    @Test
    fun `extended repository example remains valid`() {
        val source = Files.readString(Path.of("examples/syntax.o"))
        assertEquals(listOf("Counter", "Batch"), parse(source).classes.map { it.type.name })
    }

    @Test
    fun `parses empty classes and consumes multiple declarations`() {
        val program = parse("class First is end class Second is end")

        assertEquals(listOf("First", "Second"), program.classes.map { it.type.name })
        program.classes.forEach {
            assertNull(it.parent)
            assertEquals(emptyList<MemberDeclaration>(), it.members)
        }
    }

    @Test
    fun `parses inheritance and nested generic types`() {
        val declaration = parse("class Box[T] extends Parent[Map[String, List[Integer]]] is end").classes.single()

        assertEquals("Box", declaration.type.name)
        assertEquals("T", declaration.type.arguments.single().name)
        val parent = declaration.parent!!
        assertEquals("Parent", parent.name)
        val map = parent.arguments.single()
        assertEquals("Map", map.name)
        assertEquals("String", map.arguments[0].name)
        assertEquals("List", map.arguments[1].name)
        assertEquals("Integer", map.arguments[1].arguments.single().name)
    }

    @Test
    fun `method parameter lists may be omitted empty or contain multiple parameters`() {
        val members = parse(
            """
            class Example is
                method omitted is end
                method empty() : Integer => 0
                method combine(left: Integer, right: List[Integer]) : List[Integer] is
                    return right
                end
            end
            """.trimIndent(),
        ).classes.single().members.map { it as MethodDeclaration }

        assertEquals(emptyList<Parameter>(), members[0].parameters)
        assertNull(members[0].returnType)
        assertEquals(emptyList<Parameter>(), members[1].parameters)
        assertEquals("Integer", members[1].returnType!!.name)
        assertEquals(listOf("left", "right"), members[2].parameters.map { it.name })
        assertEquals("Integer", members[2].parameters[0].type.name)
        assertEquals("List", members[2].parameters[1].type.name)
        assertEquals("Integer", members[2].parameters[1].type.arguments.single().name)
        assertEquals("List", members[2].returnType!!.name)
        assertEquals("right", (((members[2].body as Block).statements.single() as ReturnStatement).value as NameExpression).name)
    }

    @Test
    fun `constructor parameter lists may be omitted empty or contain multiple parameters`() {
        val constructors = parse(
            "class Example is this is end this() is end this(x: Integer, y: Boolean) is end end",
        ).classes.single().members.map { it as ConstructorDeclaration }

        assertEquals(emptyList<Parameter>(), constructors[0].parameters)
        assertEquals(emptyList<Parameter>(), constructors[1].parameters)
        assertEquals(listOf("x", "y"), constructors[2].parameters.map { it.name })
        assertEquals(listOf("Integer", "Boolean"), constructors[2].parameters.map { it.type.name })
        constructors.forEach { assertEquals(emptyList<Statement>(), it.body.statements) }
    }

    @Test
    fun `field and local declarations retain their initializers`() {
        val declaration = parse(
            "class Counter is var value : 0 this is var limit : value + 5 end end",
        ).classes.single()

        val field = declaration.members[0] as VariableDeclaration
        assertEquals("value", field.name)
        assertLiteral(field.initializer, "0", TokenType.INTEGER_LITERAL)
        val local = (declaration.members[1] as ConstructorDeclaration).body.statements.single() as VariableDeclaration
        assertEquals("limit", local.name)
        val initializer = local.initializer as BinaryExpression
        assertEquals("value", (initializer.left as NameExpression).name)
        assertEquals(TokenType.PLUS, initializer.operator)
        assertLiteral(initializer.right, "5", TokenType.INTEGER_LITERAL)
    }

    @Test
    fun `parses full and expression method bodies`() {
        val methods = parse(
            "class Counter is method full is return 1 end method short => 2 * 3 end",
        ).classes.single().members.map { it as MethodDeclaration }

        assertLiteral(((methods[0].body as Block).statements.single() as ReturnStatement).value!!, "1", TokenType.INTEGER_LITERAL)
        val expression = (methods[1].body as ExpressionBody).expression as BinaryExpression
        assertEquals(TokenType.STAR, expression.operator)
        assertLiteral(expression.left, "2", TokenType.INTEGER_LITERAL)
        assertLiteral(expression.right, "3", TokenType.INTEGER_LITERAL)
    }

    @Test
    fun `nested control flow keeps each body and else branch separate`() {
        val statements = body(
            """
            while active loop
                if ready then
                    for item in items loop
                        item.Visit()
                    end
                else
                    active := false
                end
            end
            return
            """.trimIndent(),
        )

        assertEquals(2, statements.size)
        val loop = statements[0] as WhileStatement
        assertEquals("active", (loop.condition as NameExpression).name)
        val conditional = loop.body.statements.single() as IfStatement
        assertEquals("ready", (conditional.condition as NameExpression).name)
        val iteration = conditional.thenBranch.statements.single() as ForStatement
        assertEquals("item", iteration.variable)
        assertEquals("items", (iteration.iterable as NameExpression).name)
        val call = (iteration.body.statements.single() as ExpressionStatement).expression as CallExpression
        assertEquals("Visit", (call.callee as MemberExpression).name)
        val assignment = conditional.elseBranch!!.statements.single() as AssignmentStatement
        assertEquals("active", (assignment.target as NameExpression).name)
        assertLiteral(assignment.value, "false", TokenType.BOOLEAN_LITERAL)
        assertNull((statements[1] as ReturnStatement).value)
    }

    @Test
    fun `if may omit else and nearest if owns its else`() {
        val outer = body("if a then if b then return else return 1 end end").single() as IfStatement

        assertNull(outer.elseBranch)
        val inner = outer.thenBranch.statements.single() as IfStatement
        assertNull((inner.thenBranch.statements.single() as ReturnStatement).value)
        assertLiteral((inner.elseBranch!!.statements.single() as ReturnStatement).value!!, "1", TokenType.INTEGER_LITERAL)
    }

    @Test
    fun `return values follow tokens rather than line boundaries`() {
        val statements = body("return\nvar x : 1\nreturn\nx\nreturn")

        assertEquals(4, statements.size)
        assertNull((statements[0] as ReturnStatement).value)
        assertEquals("x", (statements[1] as VariableDeclaration).name)
        assertEquals("x", ((statements[2] as ReturnStatement).value as NameExpression).name)
        assertNull((statements[3] as ReturnStatement).value)
    }

    @Test
    fun `return recognizes every expression starting token`() {
        val values = body("return 1 return 1.5 return true return name return this return (name) return +1 return -1")
            .map { (it as ReturnStatement).value!! }

        assertLiteral(values[0], "1", TokenType.INTEGER_LITERAL)
        assertLiteral(values[1], "1.5", TokenType.REAL_LITERAL)
        assertLiteral(values[2], "true", TokenType.BOOLEAN_LITERAL)
        assertEquals("name", (values[3] as NameExpression).name)
        assertEquals(ThisExpression::class.java, values[4].javaClass)
        assertEquals(GroupedExpression::class.java, values[5].javaClass)
        assertEquals(TokenType.PLUS, (values[6] as UnaryExpression).operator)
        assertEquals(TokenType.MINUS, (values[7] as UnaryExpression).operator)
    }

    @Test
    fun `parses constructor calls with nested generic arguments`() {
        val plain = expression("Counter()") as CallExpression
        assertEquals("Counter", (plain.callee as NameExpression).name)
        assertEquals(emptyList<Expression>(), plain.arguments)
        val generic = expression("Box[Map[String, List[Integer]]](1, true)") as CallExpression
        val name = generic.callee as NameExpression
        assertEquals("Box", name.name)
        assertEquals("Map", name.typeArguments.single().name)
        assertEquals("Integer", name.typeArguments.single().arguments[1].arguments.single().name)
        assertLiteral(generic.arguments[0], "1", TokenType.INTEGER_LITERAL)
        assertLiteral(generic.arguments[1], "true", TokenType.BOOLEAN_LITERAL)
    }

    @Test
    fun `member access and calls may chain from this names and literals`() {
        val call = expression("this.factory().Build(2.Plus(3)).value") as MemberExpression
        assertEquals("value", call.name)
        val build = call.receiver as CallExpression
        val buildMember = build.callee as MemberExpression
        assertEquals("Build", buildMember.name)
        val factory = buildMember.receiver as CallExpression
        val factoryMember = factory.callee as MemberExpression
        assertEquals("factory", factoryMember.name)
        assertEquals(ThisExpression::class.java, factoryMember.receiver.javaClass)
        val plus = build.arguments.single() as CallExpression
        val plusMember = plus.callee as MemberExpression
        assertEquals("Plus", plusMember.name)
        assertLiteral(plusMember.receiver, "2", TokenType.INTEGER_LITERAL)
        assertLiteral(plus.arguments.single(), "3", TokenType.INTEGER_LITERAL)
    }

    @Test
    fun `expression statements and assignments preserve their targets`() {
        val statements = body("Run() value := 1 this.value := 2 factory().value := 3")

        assertEquals(CallExpression::class.java, (statements[0] as ExpressionStatement).expression.javaClass)
        assertEquals("value", ((statements[1] as AssignmentStatement).target as NameExpression).name)
        val thisTarget = (statements[2] as AssignmentStatement).target as MemberExpression
        assertEquals(ThisExpression::class.java, thisTarget.receiver.javaClass)
        val callTarget = (statements[3] as AssignmentStatement).target as MemberExpression
        assertEquals(CallExpression::class.java, callTarget.receiver.javaClass)
    }

    @Test
    fun `binary precedence orders arithmetic comparisons and equality`() {
        val equality = expression("a == b < c + d * e") as BinaryExpression
        assertEquals(TokenType.EQUAL_EQUAL, equality.operator)
        assertEquals("a", (equality.left as NameExpression).name)
        val comparison = equality.right as BinaryExpression
        assertEquals(TokenType.LESS, comparison.operator)
        assertEquals("b", (comparison.left as NameExpression).name)
        val addition = comparison.right as BinaryExpression
        assertEquals(TokenType.PLUS, addition.operator)
        assertEquals("c", (addition.left as NameExpression).name)
        assertEquals(TokenType.STAR, (addition.right as BinaryExpression).operator)
    }

    @Test
    fun `all binary operator groups associate to the left`() {
        val cases = listOf(
            "a - b - c" to TokenType.MINUS,
            "a / b / c" to TokenType.SLASH,
            "a <= b >= c" to TokenType.GREATER_EQUAL,
            "a == b == c" to TokenType.EQUAL_EQUAL,
        )
        cases.forEach { (source, operator) ->
            val outer = expression(source) as BinaryExpression
            assertEquals(operator, outer.operator, source)
            assertEquals("c", (outer.right as NameExpression).name, source)
            val inner = outer.left as BinaryExpression
            assertEquals("a", (inner.left as NameExpression).name, source)
            assertEquals("b", (inner.right as NameExpression).name, source)
        }
        assertEquals(TokenType.GREATER, (expression("a > b") as BinaryExpression).operator)
    }

    @Test
    fun `unary operators bind before multiplication and parentheses override precedence`() {
        val multiplication = expression("-+2 * (3 + 4)") as BinaryExpression
        assertEquals(TokenType.STAR, multiplication.operator)
        val negative = multiplication.left as UnaryExpression
        assertEquals(TokenType.MINUS, negative.operator)
        val positive = negative.operand as UnaryExpression
        assertEquals(TokenType.PLUS, positive.operator)
        assertLiteral(positive.operand, "2", TokenType.INTEGER_LITERAL)
        val grouped = multiplication.right as GroupedExpression
        assertEquals(TokenType.PLUS, (grouped.expression as BinaryExpression).operator)
    }

    @Test
    fun `literals preserve their exact lexemes and kinds`() {
        assertLiteral(expression("42"), "42", TokenType.INTEGER_LITERAL)
        assertLiteral(expression("1.5E-3"), "1.5E-3", TokenType.REAL_LITERAL)
        assertLiteral(expression("true"), "true", TokenType.BOOLEAN_LITERAL)
        assertLiteral(expression("false"), "false", TokenType.BOOLEAN_LITERAL)
    }

    @Test
    fun `source spans cover declarations types parameters and expressions`() {
        val source = "\nclass Box[T] extends Parent[T] is\nmethod get(x: List[T]) : Integer => -(x.Get(1) + 2)\nend\n"
        val program = parse(source)
        val declaration = program.classes.single()
        val method = declaration.members.single() as MethodDeclaration
        assertSpanText(source, program, source.trim())
        assertSpanText(source, declaration, source.trim())
        assertSpanText(source, declaration.type, "Box[T]")
        assertSpanText(source, declaration.parent!!, "Parent[T]")
        assertSpanText(source, method, "method get(x: List[T]) : Integer => -(x.Get(1) + 2)")
        assertSpanText(source, method.parameters.single(), "x: List[T]")
        assertSpanText(source, method.parameters.single().type, "List[T]")
        assertSpanText(source, method.returnType!!, "Integer")
        assertSpanText(source, method.body, "=> -(x.Get(1) + 2)")
        val unary = (method.body as ExpressionBody).expression as UnaryExpression
        assertSpanText(source, unary, "-(x.Get(1) + 2)")
        val grouped = unary.operand as GroupedExpression
        assertSpanText(source, grouped, "(x.Get(1) + 2)")
        val addition = grouped.expression as BinaryExpression
        assertSpanText(source, addition, "x.Get(1) + 2")
        val call = addition.left as CallExpression
        assertSpanText(source, call, "x.Get(1)")
        assertSpanText(source, call.callee, "x.Get")
    }

    @Test
    fun `block spans cover statements and empty blocks anchor at their terminator`() {
        val source = "class A is method run is if true then else end return end this is end end"
        val declaration = parse(source).classes.single()
        val block = (declaration.members[0] as MethodDeclaration).body as Block
        assertSpanText(source, block, "if true then else end return")
        val conditional = block.statements[0] as IfStatement
        val elseOffset = source.indexOf("else")
        val ifEndOffset = source.indexOf("end")
        assertEquals(position(source, elseOffset), conditional.thenBranch.span.start)
        assertEquals(conditional.thenBranch.span.start, conditional.thenBranch.span.end)
        assertEquals(position(source, ifEndOffset), conditional.elseBranch!!.span.start)
        assertEquals(conditional.elseBranch!!.span.start, conditional.elseBranch!!.span.end)
        val constructor = declaration.members[1] as ConstructorDeclaration
        val constructorEndOffset = source.indexOf("end", source.indexOf("this"))
        assertEquals(position(source, constructorEndOffset), constructor.body.span.start)
        assertEquals(constructor.body.span.start, constructor.body.span.end)
    }

    @Test
    fun `spans retain CRLF offsets and line columns`() {
        val source = "class A is\r\n  method f is\r\n    return 1\r\n  end\r\nend"
        val program = parse(source)
        val method = program.classes.single().members.single() as MethodDeclaration
        val block = method.body as Block
        assertEquals(SourcePosition(0, 1, 1), program.span.start)
        assertEquals(SourcePosition(source.length, 5, 4), program.span.end)
        assertEquals(SourcePosition(source.indexOf("method"), 2, 3), method.span.start)
        assertEquals(SourcePosition(source.indexOf("return"), 3, 5), block.span.start)
        assertEquals(SourcePosition(source.indexOf("1") + 1, 3, 13), block.span.end)
    }

    @Test
    fun `comments may appear between any tokens`() {
        val program = parse(
            "// start\nclass /* name */ Counter is var value /* initializer */ : 1 method get => value // last member\nend /* done */",
        )
        val declaration = program.classes.single()
        assertEquals("Counter", declaration.type.name)
        assertEquals(2, declaration.members.size)
        val method = declaration.members[1] as MethodDeclaration
        val value = (method.body as ExpressionBody).expression as NameExpression
        assertEquals("value", value.name)
    }

    @Test
    fun `parses the repository demo`() {
        val program = parse(Files.readString(Path.of("examples/demo.o")))
        val declaration = program.classes.single()
        assertEquals("Counter", declaration.type.name)
        assertEquals(3, declaration.members.size)
        val constructor = declaration.members[1] as ConstructorDeclaration
        assertEquals(WhileStatement::class.java, constructor.body.statements[1].javaClass)
        assertEquals("doubled", (declaration.members[2] as MethodDeclaration).name)
    }

    @Test
    fun `empty programs fail at eof`() {
        assertErrorAt("", 0)
        assertErrorAt(" // comment\r\n", 13)
    }

    @Test
    fun `missing end is reported at eof for every block form`() {
        listOf(
            "class A is",
            "class A is this is",
            "class A is method f is",
            "class A is method f is while true loop",
            "class A is method f is if true then",
            "class A is method f is for x in items loop",
        ).forEach { source -> assertErrorAt(source, source.length) }
    }

    @Test
    fun `malformed parameter lists report the offending token`() {
        assertErrorOn("class A is method f(a: Integer b: Boolean) is end end", "b:")
        assertErrorOn("class A is method f(a Integer) is end end", "Integer")
        assertErrorOn("class A is method f(a: Integer,) is end end", ")")
        assertErrorOn("class A is this(a: Integer b: Boolean) is end end", "b:")
    }

    @Test
    fun `malformed generic lists report the offending token`() {
        assertErrorOn("class A[] is end", "]")
        assertErrorOn("class A[T,] is end", "]")
        assertErrorOn("class A[T U] is end", "U")
        assertErrorOn("class A[T is end", "is")
        assertErrorOn("class A is method f => Box[Integer,]() end", "]")
        assertErrorOn("class A is method f => items[0] end", "0")
    }

    @Test
    fun `malformed call lists report the offending token`() {
        assertErrorOn("class A is method f => Run(1 2) end", "2")
        assertErrorOn("class A is method f => Run(1,) end", ")")
        assertErrorOn("class A is method f => Run(,1) end", ",")
        assertErrorOn("class A is method f => Run(1 end", "end")
    }

    @Test
    fun `missing separators and required keywords report the offending token`() {
        assertErrorOn("class A end", "end")
        assertErrorOn("class A is var x 1 end", "1")
        assertErrorOn("class A is method f Integer => 1 end", "Integer")
        assertErrorOn("class A is method f is while true return end end", "return")
        assertErrorOn("class A is method f is if true return end end", "return")
        assertErrorOn("class A is method f is for x items loop end end end", "items")
    }

    @Test
    fun `stray else and class members inside a method are rejected`() {
        assertErrorOn("class A is method f is else end end", "else")
        assertErrorOn("class A is method f is method g is end end end", "method g")
        assertErrorOn("class A is else end", "else")
    }

    @Test
    fun `missing expressions report the token that cannot start an expression`() {
        assertErrorOn("class A is var x : end", "end")
        assertErrorOn("class A is method f => end", "end")
        assertErrorOn("class A is method f => 1 + end", "end")
        assertErrorOn("class A is method f is x := end end", "end")
        assertErrorOn("class A is method f is if then end end end", "then")
        assertErrorOn("class A is method f => value. end", "end")
    }

    @Test
    fun `invalid assignment targets are rejected`() {
        listOf("1", "Call()", "a + b", "(a)", "this", "Box[Integer]").forEach { target ->
            val source = "class A is method f is $target := 2 end end"
            assertErrorAt(source, source.indexOf(target, source.indexOf("method")))
        }
    }

    @Test
    fun `unexpected input after the final class is rejected`() {
        assertErrorOn("class A is end value", "value")
        assertErrorAt("class A is end end", 15)
        assertErrorAt("class A is end\r\n  value", 18)
    }

    private fun parse(source: String): Program = Parser(Lexer(source).tokenize()).parse()

    private fun body(source: String): List<Statement> {
        val method = parse("class Test is method run is $source end end").classes.single().members.single() as MethodDeclaration
        return (method.body as Block).statements
    }

    private fun expression(source: String): Expression {
        val method = parse("class Test is method value => $source end").classes.single().members.single() as MethodDeclaration
        return (method.body as ExpressionBody).expression
    }

    private fun assertLiteral(expression: Expression, lexeme: String, kind: TokenType) {
        val literal = expression as LiteralExpression
        assertEquals(lexeme, literal.lexeme)
        assertEquals(kind, literal.kind)
    }

    private fun assertSpanText(source: String, node: AstNode, expected: String) {
        assertEquals(expected, source.substring(node.span.start.offset, node.span.end.offset))
        assertEquals(position(source, node.span.start.offset), node.span.start)
        assertEquals(position(source, node.span.end.offset), node.span.end)
    }

    private fun assertErrorOn(source: String, token: String) {
        val offset = source.indexOf(token)
        check(offset >= 0) { "Expected error token '$token' is absent from test input" }
        assertErrorAt(source, offset)
    }

    private fun assertErrorAt(source: String, offset: Int) {
        val error = assertThrows(ParserException::class.java, { parse(source) }, source)
        assertEquals(position(source, offset), error.position, source)
    }

    private fun position(source: String, offset: Int): SourcePosition {
        val prefix = source.substring(0, offset).replace("\r\n", "\n").replace('\r', '\n')
        return SourcePosition(offset, prefix.count { it == '\n' } + 1, prefix.length - prefix.lastIndexOf('\n'))
    }
}
