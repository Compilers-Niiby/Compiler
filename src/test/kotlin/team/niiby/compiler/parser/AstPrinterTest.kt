package team.niiby.compiler.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import team.niiby.compiler.lexer.Lexer

class AstPrinterTest {
    @Test
    fun `outline shows expression nesting and original source positions`() {
        val source = """
            class Example is
                method value => 1 + 2 * 3
            end
        """.trimIndent()
        val program = Parser(Lexer(source).tokenize()).parse()

        assertEquals(
            """
                Program @ 1:1
                  Class Example @ 1:1
                    Type Example @ 1:7
                    Method value @ 2:5
                      Expression body @ 2:18
                        Binary PLUS @ 2:21
                          Literal 1 @ 2:21
                          Binary STAR @ 2:25
                            Literal 2 @ 2:25
                            Literal 3 @ 2:29
            """.trimIndent() + "\n",
            AstPrinter().print(program),
        )
    }
}
