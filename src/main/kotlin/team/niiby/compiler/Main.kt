package team.niiby.compiler

import java.nio.file.Path
import kotlin.io.path.readText
import team.niiby.compiler.lexer.Lexer
import team.niiby.compiler.lexer.LexerException

fun main(args: Array<String>) {
    val source = when (args.size) {
        0 -> generateSequence(::readLine).joinToString("\n")
        1 -> Path.of(args[0]).readText()
        else -> {
            System.err.println("Usage: lexer [source-file]")
            return
        }
    }

    try {
        val tokens = Lexer(source).tokenize()
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
    } catch (error: LexerException) {
        System.err.println("Lexical error: ${error.message}")
        kotlin.system.exitProcess(1)
    }
}
