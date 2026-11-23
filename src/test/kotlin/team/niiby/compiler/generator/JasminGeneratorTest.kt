package team.niiby.compiler.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import team.niiby.compiler.analyzer.SemanticException
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.parser.Parser

class JasminGeneratorTest {
    private fun generate(source: String) = JasminGenerator().generate(Parser(Lexer(source).tokenize()).parse())

    @Test
    fun `methods reserve both JVM slots for Real parameters`() {
        val source = generate("class Math is method sum(a : Real, b : Integer) : Real => a + b end").getValue("Math.j")

        assertTrue(".method public sum(DI)D" in source)
        assertTrue(".limit locals 4" in source)
        assertTrue("dload 1" in source)
        assertTrue("iload 3" in source)
        assertTrue("i2d\n    dadd\n    dreturn" in source)
    }

    @Test
    fun `inherited fields and covariant method results retain their JVM signatures`() {
        val generated = generate(
            """
            class Parent is
                var total : 3
                method score : Class => total
            end
            class Child extends Parent is
                method score : Integer => total + 1
            end
            """.trimIndent(),
        )

        assertEquals(setOf("Parent.j", "Child.j"), generated.keys)
        val child = generated.getValue("Child.j")
        assertTrue(".super Parent" in child)
        assertTrue("invokevirtual Parent/\$read\$Parent\$total()I" in child)
        assertTrue(".method public score()I" in child)
        assertTrue(".method public score()Ljava/lang/Object;\n    .synthetic" in child)
        assertTrue("invokevirtual Child/score()I\n    invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;" in child)
    }

    @Test
    fun `constructors with erased list types get distinct descriptors`() {
        val generated = generate(
            """
            class Box is
                this(values : List[Integer]) is end
                this(values : List[Boolean]) is end
            end
            class Use is
                method box : Box => Box(List[Boolean](true))
            end
            """.trimIndent(),
        )

        val box = generated.getValue("Box.j")
        assertTrue(".method public <init>(Ljava/util/ArrayList;)V" in box)
        assertTrue(".method public <init>(Ljava/util/ArrayList;Ljava/lang/String;)V" in box)
        assertTrue("invokespecial Box/<init>(Ljava/util/ArrayList;Ljava/lang/String;)V" in generated.getValue("Use.j"))
    }

    @Test
    fun `O class names that are Jasmin keywords get consistent JVM names`() {
        val generated = generate("class public is end class Child extends public is method make : public => public() end")

        assertTrue(".class public \$public" in generated.getValue("public.j"))
        val child = generated.getValue("Child.j")
        assertTrue(".super \$public" in child)
        assertTrue("make()L\$public;" in child)
        assertTrue("invokespecial \$public/<init>()V" in child)
    }

    @Test
    fun `generation reports semantic errors and missing parent constructors`() {
        assertThrows(SemanticException::class.java) {
            generate("class Invalid is method wrong : Integer => true end")
        }
        val error = assertThrows(GenerationException::class.java) {
            generate("class Parent is this(value : Integer) is end end\nclass Child extends Parent is end")
        }
        assertEquals("Superclass 'Parent' needs a parameterless constructor at 2:1", error.message)
    }
}
