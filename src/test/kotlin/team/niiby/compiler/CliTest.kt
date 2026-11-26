package team.niiby.compiler

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Starts the compiler as a separate process, the way it runs from the terminal. */
class CliTest {
    private data class Result(val exitCode: Int, val lines: List<String>)

    private fun compiler(vararg args: String, input: String = ""): Result {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "team.niiby.compiler.MainKt", *args)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "compiler did not finish")
        return Result(process.exitValue(), output.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `program arguments go to the root class constructor`() {
        assertEquals(
            Result(0, listOf(
                "Rectangle area:", "20.0", "Total area of all shapes:", "35.0",
                "Total area without the rectangle:", "15.0", "Total number of sides:", "11",
            )),
            compiler("--run", "examples/shapes.o", "4", "5", "3"),
        )
        assertEquals(
            Result(0, listOf(
                "Array length:", "3", "Sum:", "21", "Best score:", "12",
                "Sum without the first score:", "18", "Last array element:", "12",
            )),
            compiler("--run", "examples/scores.o", "3", "-4", "12"),
        )
    }

    @Test
    fun `without arguments the parameterless constructor is used`() {
        val result = compiler("--run", "examples/shapes.o")
        assertEquals(0, result.exitCode)
        assertEquals(listOf("Rectangle area:", "6.0"), result.lines.take(2))
    }

    @Test
    fun `wrong number or format of program arguments is reported`() {
        assertEquals(Result(1, listOf("Expected 0 or 3 program argument(s)")), compiler("--run", "examples/scores.o", "1", "2"))
        assertEquals(
            Result(1, listOf("Invalid program argument:", "For input string: \"x\"")),
            compiler("--run", "examples/scores.o", "1", "x", "3"),
        )
        assertEquals(Result(1, listOf("Expected 0 program argument(s)")), compiler("--run", "examples/hello-sum.o", "5"))
    }

    @Test
    fun `compiler options are validated before the program runs`() {
        assertEquals(2, compiler("--bogus", "examples/hello-sum.o").exitCode)
        assertEquals(2, compiler("--run").exitCode)
        assertEquals(2, compiler("--generate", "a.o", "out", "extra").exitCode)
        val missing = compiler("--run", "no-such-file.o")
        assertEquals(1, missing.exitCode)
        assertTrue(missing.lines.first().startsWith("I/O error"))
        val semantic = compiler("--run", "examples/semantic-error.o")
        assertEquals(Result(1, listOf("Semantic error: Expected Integer, found Boolean at 4:9")), semantic)
    }

    @Test
    fun `console input still works next to the launcher`() {
        assertEquals("12", compiler("--run", "examples/hello-sum.o", input = "7 5\n").lines.last())
    }
}
