package team.niiby.compiler.generator

import java.io.ByteArrayOutputStream
import jasmin.ClassFile
import team.niiby.compiler.parser.Program

class JvmGenerator {
    fun generate(program: Program): Map<String, ByteArray> = buildMap {
        for ((filename, source) in JasminGenerator().generate(program)) {
            val assembler = ClassFile()
            val result = try {
                source.byteInputStream().use { assembler.readJasmin(it, filename, false) }
                check(assembler.errorCount() == 0) { "${assembler.errorCount()} assembly errors" }
                ByteArrayOutputStream().also(assembler::write).toByteArray()
            } catch (error: Exception) {
                throw GenerationException("Jasmin could not assemble $filename: ${error.message}", program.span.start)
                    .also { it.initCause(error) }
            }
            put("${assembler.className}.class", result)
        }
    }
}
