package team.niiby.compiler.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.parser.AstPrinter
import team.niiby.compiler.parser.BinaryExpression
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.ExpressionBody
import team.niiby.compiler.parser.LiteralExpression
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Parser
import team.niiby.compiler.parser.Program
import team.niiby.compiler.parser.ReturnStatement

class OptimizerTest {
    private fun program(body: String) = Parser(Lexer("class Example is $body end").tokenize()).parse()

    private fun optimized(body: String): Program {
        val original = program(body)
        Analyzer().analyze(original)
        return Optimizer().optimize(original).also { Analyzer().analyze(it) }
    }

    @Test
    fun `constant expressions and standard method calls fold`() {
        val result = optimized("method value : Integer => 2.Plus(3.Mult(4))")
        val body = (result.classes.single().members.single() as MethodDeclaration).body as ExpressionBody
        assertEquals("14", (body.expression as LiteralExpression).lexeme)
    }

    @Test
    fun `constant branches preserve a block scope`() {
        val result = optimized("method run is if true then var x : 1 else var x : 2 end var x : 3 end")
        val block = (result.classes.single().members.single() as MethodDeclaration).body as Block
        assertTrue(block.statements[0] is Block)
        assertEquals(2, block.statements.size)
    }

    @Test
    fun `false loops and code after returns disappear`() {
        val result = optimized("method run : Integer is while false loop var x : 2 end return 1 var y : 3 end")
        val block = (result.classes.single().members.single() as MethodDeclaration).body as Block
        assertEquals(1, block.statements.size)
        assertTrue(block.statements.single() is ReturnStatement)
    }

    @Test
    fun `division by zero and overflow remain expressions`() {
        for (value in listOf("1 / 0", "2147483647 + 1")) {
            val result = optimized("method value : Integer => $value")
            val body = (result.classes.single().members.single() as MethodDeclaration).body as ExpressionBody
            assertTrue(body.expression is BinaryExpression)
        }
    }

    @Test
    fun `optimization preserves source spans and original tree`() {
        val original = program("method value : Integer => 2 + 3")
        val before = AstPrinter().print(original)
        val result = Optimizer().optimize(original)
        val old = ((original.classes.single().members.single() as MethodDeclaration).body as ExpressionBody).expression
        val new = ((result.classes.single().members.single() as MethodDeclaration).body as ExpressionBody).expression
        assertEquals(old.span, new.span)
        assertEquals(before, AstPrinter().print(original))
        assertEquals(result, Optimizer().optimize(result))
    }
}