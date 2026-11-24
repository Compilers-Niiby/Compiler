package team.niiby.compiler.generator

import team.niiby.compiler.analyzer.ConsoleCall
import team.niiby.compiler.parser.Expression

internal object JvmConsole {
    private const val INPUT_CLASS = "\$OConsole"
    const val inputFilename = "$INPUT_CLASS.j"

    fun emitCall(method: MethodGenerator, call: ConsoleCall, arguments: List<Expression>) {
        val code = method.code
        when (call) {
            ConsoleCall.READ_INTEGER -> code.emit("invokestatic $INPUT_CLASS/readInteger()I", pushes = 1)
            ConsoleCall.PRINTLN -> {
                val argument = arguments.single()
                val type = method.typeOf(argument)
                code.emit("getstatic java/lang/System/out Ljava/io/PrintStream;", pushes = 1)
                method.expression(argument)
                code.emit("invokevirtual java/io/PrintStream/println(${descriptor(type)})V", pops = 1 + slots(type))
            }
        }
    }

    // All callers share one scanner so buffered input remains available for the next read.
    fun generateInputClass(): String = """
        .class final $INPUT_CLASS
        .super java/lang/Object
        .field private static final "input" Ljava/util/Scanner;

        .method static <clinit>()V
            .limit stack 3
            .limit locals 0
            new java/util/Scanner
            dup
            getstatic java/lang/System/in Ljava/io/InputStream;
            invokespecial java/util/Scanner/<init>(Ljava/io/InputStream;)V
            putstatic $INPUT_CLASS/input Ljava/util/Scanner;
            return
        .end method

        .method static readInteger()I
            .limit stack 1
            .limit locals 0
            getstatic $INPUT_CLASS/input Ljava/util/Scanner;
            invokevirtual java/util/Scanner/nextInt()I
            ireturn
        .end method
    """.trimIndent() + "\n"
}
