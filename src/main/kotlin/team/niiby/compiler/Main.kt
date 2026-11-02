package team.niiby.compiler

import team.niiby.compiler.analyzer.Analyzer
import team.niiby.compiler.analyzer.Optimizer
import team.niiby.compiler.analyzer.SemanticException
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.lexer.LexerException
import team.niiby.compiler.lexer.Token
import team.niiby.compiler.parser.AstPrinter
import team.niiby.compiler.parser.Parser
import team.niiby.compiler.parser.ParserException

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println("Usage: compiler [--tokens|--parse|--analyze|--optimize] [source-file]")
        println("Without a file, reads standard input. The default mode is --tokens.")
        return
    }

    val mode = args.firstOrNull() ?: "--tokens"
    val files = if (args.firstOrNull() in listOf("--parse", "--tokens", "--analyze", "--optimize")) args.drop(1) else args.toList()
    if (files.size > 1 || files.any { it.startsWith("--") }) {
        System.err.println("Usage: compiler [--tokens|--parse|--analyze|--optimize] [source-file]")
        exitProcess(2)
    }

    try {
        val source = if (files.isEmpty()) {
            System.`in`.bufferedReader().readText()
        } else {
            Path.of(files.single()).readText()
        }
        val tokens = Lexer(source).tokenize()
        if (mode in listOf("--parse", "--analyze", "--optimize")) {
            val program = Parser(tokens).parse()
            if (mode != "--parse") Analyzer().analyze(program)
            print(AstPrinter().print(if (mode == "--optimize") Optimizer().optimize(program) else program))
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
    } catch (error: IOException) {
        System.err.println("Input error: ${error.message}")
        exitProcess(1)
    } catch (error: InvalidPathException) {
        System.err.println("Input error: ${error.message}")
        exitProcess(1)
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
