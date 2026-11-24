package team.niiby.compiler

import team.niiby.compiler.analyzer.Analyzer
import team.niiby.compiler.analyzer.Optimizer
import team.niiby.compiler.analyzer.SemanticException
import team.niiby.compiler.generator.GenerationException
import team.niiby.compiler.generator.JasminGenerator
import team.niiby.compiler.generator.JvmGenerator
import team.niiby.compiler.generator.jvmClassName
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
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Parser
import team.niiby.compiler.parser.ParserException
import team.niiby.compiler.parser.Program

private const val USAGE = "Usage: compiler [--tokens|--parse|--analyze|--optimize] [source-file]\n" +
    "       compiler (--generate|--jasmin) [source-file] [output-directory]\n" +
    "       compiler --run source-file"
private val MODES = setOf("--tokens", "--parse", "--analyze", "--optimize", "--generate", "--jasmin", "--run")
private const val DEFAULT_CLASS_OUTPUT = "target/generated-classes"
private const val DEFAULT_JASMIN_OUTPUT = "target/jasmin"

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println(USAGE)
        println("Most modes can read O code from standard input. The default mode is --tokens.")
        println("--generate assembles JVM class files with Jasmin. The default output is $DEFAULT_CLASS_OUTPUT.")
        println("--jasmin writes the assembly source to $DEFAULT_JASMIN_OUTPUT by default.")
        return
    }

    val option = args.firstOrNull()?.takeIf { it in MODES }
    val mode = option ?: "--tokens"
    val files = if (option == null) args.toList() else args.drop(1)
    val writesOutput = mode == "--generate" || mode == "--jasmin"
    val maxFiles = if (writesOutput) 2 else 1
    if (files.size > maxFiles || files.any { it.startsWith("--") } || mode == "--run" && files.isEmpty()) {
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
            when (mode) {
                "--generate" -> {
                    val output = Path.of(files.getOrNull(1) ?: DEFAULT_CLASS_OUTPUT)
                    writeGeneratedFiles(output, JvmGenerator().generate(program))
                }
                "--jasmin" -> {
                    val output = Path.of(files.getOrNull(1) ?: DEFAULT_JASMIN_OUTPUT)
                    val sources = JasminGenerator().generate(program).mapValues { (_, source) -> source.toByteArray() }
                    writeGeneratedFiles(output, sources)
                }
                "--run" -> {
                    val status = runProgram(program)
                    if (status != 0) exitProcess(status)
                }
                else -> {
                    if (mode != "--parse") Analyzer().analyze(program)
                    print(AstPrinter().print(if (mode == "--optimize") Optimizer().optimize(program) else program))
                }
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
        val kind = if (writesOutput || mode == "--run") "I/O" else "Input"
        System.err.println("$kind error: ${error.message}")
        exitProcess(1)
    } catch (error: InvalidPathException) {
        System.err.println("Input error: ${error.message}")
        exitProcess(1)
    }
}

private fun runProgram(program: Program): Int {
    val generated = JvmGenerator().generate(program)
    val entryPoints = program.classes.filter { declaration ->
        declaration.members.any { it is MethodDeclaration && it.name == "main" && it.parameters.isEmpty() && it.returnType == null }
    }
    val entryPoint = when (entryPoints.size) {
        0 -> throw GenerationException("Expected a parameterless method main without a return type", program.span.start)
        1 -> jvmClassName(entryPoints.single().type.name)
        else -> {
            val names = entryPoints.joinToString(", ") { it.type.name }
            throw GenerationException("Multiple classes declare main: $names", program.span.start)
        }
    }
    val output = Files.createTempDirectory("o-run-")
    try {
        writeGeneratedFiles(output, generated)
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        return ProcessBuilder(java.toString(), "-cp", output.toString(), entryPoint).inheritIO().start().waitFor()
    } finally {
        for (name in generated.keys) Files.deleteIfExists(output.resolve(name))
        Files.deleteIfExists(output)
    }
}

private fun writeGeneratedFiles(output: Path, generated: Map<String, ByteArray>) {
    Files.createDirectories(output)
    val staging = Files.createTempDirectory(output, ".compiler-")
    try {
        for ((name, bytes) in generated) Files.write(staging.resolve(name), bytes, CREATE_NEW, WRITE)
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
