package team.niiby.compiler.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import team.niiby.compiler.analyzer.SemanticException
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.parser.Parser

class JvmGeneratorTest {
    private fun generate(source: String) = JvmGenerator().generate(Parser(Lexer(source).tokenize()).parse())

    private class GeneratedClasses(private val files: Map<String, ByteArray>) : ClassLoader(getPlatformClassLoader()) {
        override fun findClass(name: String): Class<*> {
            val bytes = files["$name.class"] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    @Test
    fun `mixed Real and Integer parameters preserve JVM slots`() {
        val files = generate("class Numbers is method sum(a : Real, b : Integer) : Real => a + b end")
        assertEquals(setOf("Numbers.class"), files.keys)

        val type = GeneratedClasses(files).loadClass("Numbers")
        val result = type.getMethod("sum", Double::class.java, Int::class.java)
            .invoke(type.getConstructor().newInstance(), 1.5, 2)
        assertEquals(3.5, result)
    }

    @Test
    fun `inherited fields and covariant method results keep virtual dispatch`() {
        val classes = GeneratedClasses(
            generate(
                """
                class Parent is
                    var total : baseValue
                    var baseValue : 3
                    method score : Class => total
                end
                class Child extends Parent is
                    method score : Integer => total + 1
                end
                """.trimIndent(),
            ),
        )

        val parent = classes.loadClass("Parent")
        val child = classes.loadClass("Child")
        val instance = child.getConstructor().newInstance()
        assertTrue(child.declaredMethods.any { it.name == "score" && it.isBridge && it.isSynthetic })
        assertEquals(4, parent.getMethod("score").invoke(instance))
    }

    @Test
    fun `branch frames merge references to generated classes`() {
        val classes = GeneratedClasses(
            generate(
                """
                class Root is method value : Integer => 0 end
                class Left extends Root is method value : Integer => 1 end
                class Right extends Root is method value : Integer => 2 end
                class Choice is
                    method choose(flag : Boolean) : Integer is
                        var object : Root()
                        if flag then
                            object := Left()
                        else
                            object := Right()
                        end
                        return object.value
                    end
                end
                """.trimIndent(),
            ),
        )

        val choice = classes.loadClass("Choice")
        val choose = choice.getMethod("choose", Boolean::class.java)
        val instance = choice.getConstructor().newInstance()
        assertEquals(1, choose.invoke(instance, true))
        assertEquals(2, choose.invoke(instance, false))
    }

    @Test
    fun `arrays and erased list constructors execute with the declared element types`() {
        val classes = GeneratedClasses(
            generate(
                """
                class Box is
                    var value : 0
                    this(values : List[Integer]) is value := values.head end
                    this(values : List[Boolean]) is value := values.head.toInteger end
                    method read : Integer => value
                end
                class Use is
                    method run : Integer is
                        var values : Array[Boolean](2)
                        values.set(0, true)
                        var sum : 0
                        for flag in values.toList loop
                            sum := sum + flag.toInteger
                        end
                        return Box(List[Integer](4)).read + Box(List[Boolean](true)).read + sum
                    end
                end
                """.trimIndent(),
            ),
        )

        val use = classes.loadClass("Use")
        assertEquals(6, use.getMethod("run").invoke(use.getConstructor().newInstance()))
    }

    @Test
    fun `class files keep source names and can call methods named after Object methods`() {
        val files = generate(
            """
            class public is
                method notify is return end
                method value : Integer is notify() return 42 end
            end
            class Use extends public is method run : Integer => public().value end
            """.trimIndent(),
        )

        assertEquals(setOf("public.class", "Use.class"), files.keys)
        val use = GeneratedClasses(files).loadClass("Use")
        assertEquals("public", use.superclass.name)
        assertEquals(42, use.getMethod("run").invoke(use.getConstructor().newInstance()))
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
