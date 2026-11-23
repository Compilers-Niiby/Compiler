package team.niiby.compiler.generator

import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_FINAL
import java.lang.classfile.ClassFile.ACC_PRIVATE
import java.lang.classfile.ClassFile.ACC_STATIC
import java.lang.classfile.ClassFile.ACC_SUPER
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.ConstantDescs.MTD_void
import java.lang.constant.MethodTypeDesc
import team.niiby.compiler.analyzer.ConsoleCall
import team.niiby.compiler.parser.Expression

internal object JvmConsole {
    private val inputClass = ClassDesc.of("\$OConsole")
    private val system = ClassDesc.of("java.lang.System")
    private val inputStream = ClassDesc.of("java.io.InputStream")
    private val printStream = ClassDesc.of("java.io.PrintStream")
    private val scanner = ClassDesc.of("java.util.Scanner")
    private val readSignature = MethodTypeDesc.of(CD_int)

    val inputFilename = "${inputClass.displayName()}.class"

    fun emitCall(method: MethodGenerator, call: ConsoleCall, arguments: List<Expression>) {
        val code = method.code
        when (call) {
            ConsoleCall.READ_INTEGER -> code.invokestatic(inputClass, "readInteger", readSignature)
            ConsoleCall.PRINTLN -> {
                val argument = arguments.single()
                code.getstatic(system, "out", printStream)
                method.expression(argument)
                code.invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, typeSymbol(method.typeOf(argument))))
            }
        }
    }

    fun generateInputClass(classFile: ClassFile): ByteArray = classFile.build(inputClass) { builder ->
        builder.withVersion(ClassFile.JAVA_25_VERSION, 0)
        builder.withFlags(ACC_FINAL or ACC_SUPER)
        builder.withSuperclass(CD_Object)
        builder.withField("input", scanner, ACC_PRIVATE or ACC_STATIC or ACC_FINAL)

        // All callers share one scanner so buffered input remains available for the next read.
        builder.withMethodBody("<clinit>", MTD_void, ACC_STATIC) { code ->
            code.new_(scanner)
            code.dup()
            code.getstatic(system, "in", inputStream)
            code.invokespecial(scanner, "<init>", MethodTypeDesc.of(CD_void, inputStream))
            code.putstatic(inputClass, "input", scanner)
            code.return_()
        }
        builder.withMethodBody("readInteger", readSignature, ACC_STATIC) { code ->
            code.getstatic(inputClass, "input", scanner)
            code.invokevirtual(scanner, "nextInt", readSignature)
            code.ireturn()
        }
    }
}
