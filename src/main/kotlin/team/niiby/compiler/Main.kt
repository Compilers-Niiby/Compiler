package team.niiby.compiler

import team.niiby.compiler.analyzer.Analyzer
import team.niiby.compiler.analyzer.Optimizer
import team.niiby.compiler.analyzer.SemanticException
import team.niiby.compiler.generator.GenerationException
import team.niiby.compiler.generator.JasminGenerator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.readText
import kotlin.system.exitProcess
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.lexer.LexerException
import team.niiby.compiler.lexer.Token
import team.niiby.compiler.parser.AstPrinter
import team.niiby.compiler.parser.Parser
import team.niiby.compiler.parser.ParserException

private const val USAGE = "Usage: compiler [--tokens|--parse|--analyze|--optimize] [source-file]\n" +
    "       compiler --generate [source-file] [output-directory]"
private val MODES = setOf("--tokens", "--parse", "--analyze", "--optimize", "--generate")

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println(USAGE)
        println("Without a file, reads standard input. The default mode is --tokens.")
        println("--generate writes one Jasmin file per class to target/jasmin by default.")
        return
    }

    val option = args.firstOrNull()?.takeIf { it in MODES }
    val mode = option ?: "--tokens"
    val files = if (option == null) args.toList() else args.drop(1)
    val maxFiles = if (mode == "--generate") 2 else 1
    if (files.size > maxFiles || files.any { it.startsWith("--") }) {
        System.err.println(USAGE)
        exitProcess(2)
    }

    try {
        val source = if (files.isEmpty()) {
            System.`in`.bufferedReader().readText()
        } else {
            Path.of(files.first()).readText()
        }
        val tokens = Lexer(source).tokenize()
        if (mode != "--tokens") {
            val program = Parser(tokens).parse()
            if (mode == "--generate") {
                val output = Path.of(files.getOrNull(1) ?: "target/jasmin")
                writeJasmin(output, JasminGenerator().generate(program))
            } else {
                if (mode != "--parse") Analyzer().analyze(program)
                print(AstPrinter().print(if (mode == "--optimize") Optimizer().optimize(program) else program))
            }
        } else printTokens(tokens)
    } catch (error: SemanticException) {
        System.err.println("Semantic error: ${error.message}")
        exitProcess(1)
    } catch (error: LexerException) {
        System.err.println("Lexical error: ${error.message}")
        exitProcess(1)
    } catch (error: ParserException) {
        System.err.println("Syntax error: ${error.message}")
        exitProcess(1)
    } catch (error: GenerationException) {
        System.err.println("Generation error: ${error.message}")
        exitProcess(1)
    } catch (error: IOException) {
        val kind = if (mode == "--generate") "I/O" else "Input"
        System.err.println("$kind error: ${error.message}")
        exitProcess(1)
    } catch (error: InvalidPathException) {
        System.err.println("Input error: ${error.message}")
        exitProcess(1)
    }
}

private fun writeJasmin(output: Path, generated: Map<String, String>) {
    Files.createDirectories(output)
    val staging = Files.createTempDirectory(output, ".jasmin-")
    try {
        for ((name, jasmin) in generated) Files.writeString(staging.resolve(name), jasmin, CREATE_NEW, WRITE)
        for (name in generated.keys) Files.move(staging.resolve(name), output.resolve(name), REPLACE_EXISTING)
    } finally {
        for (name in generated.keys) Files.deleteIfExists(staging.resolve(name))
        Files.deleteIfExists(staging)
    }
}

private fun printTokens(tokens: List<Token>) {
    println("%-20s %-18s %s".format("TYPE", "POSITION", "LEXEME"))
    println("-".repeat(62))
    for (token in tokens) {
        val displayedLexeme = token.lexeme
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        println(
            "%-20s %-18s %s".format(
                token.type,
                "${token.line}:${token.column}",
                if (displayedLexeme.isEmpty()) "<empty>" else displayedLexeme,
            ),
        )
    }
}
