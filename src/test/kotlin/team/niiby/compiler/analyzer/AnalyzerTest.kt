package team.niiby.compiler.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.parser.AstPrinter
import team.niiby.compiler.parser.Parser

class AnalyzerTest {
    private fun parse(source: String) = Parser(Lexer(source).tokenize()).parse()

    private fun check(body: String) = Analyzer().analyze(parse("class Example is $body end"))

    @Test
    fun `checking preserves the original tree`() {
        val program = parse("class Example is method value : Integer => 2.Plus(3) end")
        val before = AstPrinter().print(program)
        assertSame(program, Analyzer().analyze(program))
        assertEquals(before, AstPrinter().print(program))
    }

    @Test
    fun `unknown names and use before local declaration fail`() {
        assertThrows(SemanticException::class.java) { check("method run is var x : y var y : 1 end") }
        assertThrows(SemanticException::class.java) { check("method run is missing() end") }
        assertThrows(SemanticException::class.java) { check("method run(p : Missing) is end") }
    }

    @Test
    fun `conditions assignments and returns have compatible types`() {
        for (body in listOf(
            "method run is if 1 then end end",
            "method run is var x : 1 x := true end",
            "method run : Integer => true",
            "method run : Integer is return end",
            "method run : Integer is if true then return 1 end end",
            "method run is return 1 end",
        )) assertThrows(SemanticException::class.java) { check(body) }
    }

    @Test
    fun `overloads and implicit zero argument calls resolve`() {
        check("method add(x : Integer) : Integer => x method add(x : Boolean) : Boolean => x method value : Integer => add(2) method result : Integer => value")
        assertThrows(SemanticException::class.java) { check("method f(x : Integer) is end method g is f(true) end") }
        assertThrows(SemanticException::class.java) { check("method f is end method f is end") }
    }

    @Test
    fun `inheritance permits subtype values and rejects cycles`() {
        Analyzer().analyze(parse("class Base is end class Child extends Base is end class Use is method id(x : Base) : Base => x method run : Base => id(Child()) end"))
        assertThrows(SemanticException::class.java) { Analyzer().analyze(parse("class A extends B is end class B extends A is end")) }
    }

    @Test
    fun `collections use their element types`() {
        check("method limits : Integer => Integer.Min method precision : Real => Real.Epsilon")
        check("method run : Integer is var a : Array[Integer](4) a.set(0, 3) return a.get(0) end")
        assertThrows(SemanticException::class.java) { check("method run is var a : Array[Integer](4) a.set(0, true) end") }
    }

    @Test
    fun `fields of other objects cannot be assigned`() {
        assertThrows(SemanticException::class.java) { check("var x : 1 method run(other : Example) is other.x := 2 end") }
        check("var x : 1 method run is this.x := 2 end")
    }

    @Test
    fun `duplicates cyclic initializers and invalid constructors fail`() {
        for (body in listOf("var x : x", "var x : 1 var x : 2", "method run(x : Integer, x : Integer) is end", "method run is var x : Integer(true) end")) {
            assertThrows(SemanticException::class.java) { check(body) }
        }
    }

    @Test
    fun `dead branches are checked before optimization`() {
        assertThrows(SemanticException::class.java) { check("method run is if false then unknown() end end") }
    }
}