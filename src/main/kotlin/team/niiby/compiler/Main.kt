package team.niiby.compiler

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
        println("Usage: compiler [--tokens|--parse] [source-file]")
        println("Without a file, reads standard input. The default mode is --tokens.")
        return
    }

    val parse = args.firstOrNull() == "--parse"
    val files = if (args.firstOrNull() in listOf("--parse", "--tokens")) args.drop(1) else args.toList()
    if (files.size > 1 || files.any { it.startsWith("--") }) {
        System.err.println("Usage: compiler [--tokens|--parse] [source-file]")
        exitProcess(2)
    }

    try {
        val source = if (files.isEmpty()) {
            System.`in`.bufferedReader().readText()
        } else {
            Path.of(files.single()).readText()
        }
        val tokens = Lexer(source).tokenize()
        if (parse) print(AstPrinter().print(Parser(tokens).parse())) else printTokens(tokens)
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
