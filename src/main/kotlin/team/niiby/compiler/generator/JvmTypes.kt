package team.niiby.compiler.generator

import team.niiby.compiler.analyzer.STRING_VALUE_TYPE
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Parameter
import team.niiby.compiler.parser.TypeReference

private val jasminKeywords = setOf(
    "is", "from", "method", "to", "using", "tableswitch", "lookupswitch", "default",
    "public", "private", "protected", "static", "final", "synchronized", "volatile", "transient",
    "native", "interface", "abstract", "strictfp", "annotation", "enum",
)

private data class PrimitiveType(val descriptor: String, val opcodePrefix: String, val slots: Int, val wrapper: String? = null)

private val primitiveTypes = mapOf(
    "Integer" to PrimitiveType("I", "i", 1, "java/lang/Integer"),
    "Boolean" to PrimitiveType("Z", "i", 1, "java/lang/Boolean"),
    "Real" to PrimitiveType("D", "d", 2, "java/lang/Double"),
    "Void" to PrimitiveType("V", "a", 0),
)

internal fun jvmClassName(name: String): String = if (name in jasminKeywords) "\$" + name else name

internal fun TypeReference.typeName(): String = if (arguments.isEmpty()) name else "${name}[${arguments.single().typeName()}]"

internal fun descriptor(type: String): String = primitiveTypes[type]?.descriptor ?: when {
    type == STRING_VALUE_TYPE -> "Ljava/lang/String;"
    type in setOf("Class", "AnyRef", "AnyValue") -> "Ljava/lang/Object;"
    type.startsWith("Array[") -> "[${descriptor(elementType(type))}"
    type.startsWith("List[") -> "Ljava/util/ArrayList;"
    else -> "L${jvmClassName(type)};"
}

internal fun elementType(type: String): String = type.substringAfter('[').dropLast(1)

internal fun slots(type: String): Int = primitiveTypes[type]?.slots ?: 1

internal fun instructionPrefix(type: String): String = primitiveTypes[type]?.opcodePrefix ?: "a"

internal fun boxedClass(type: String): String? = primitiveTypes[type]?.wrapper

internal fun referenceName(type: String): String = descriptor(type).let {
    if (it.startsWith("L")) it.substring(1, it.lastIndex) else it
}

internal fun parameterDescriptor(parameters: List<Parameter>): String =
    parameters.joinToString("") { descriptor(it.type.typeName()) }

internal fun MethodDeclaration.resultType(): String = returnType?.typeName() ?: "Void"

internal fun MethodDeclaration.jvmSignature(result: String = resultType()): String =
    "${jvmName()}(${parameterDescriptor(parameters)})${descriptor(result)}"

internal fun MethodDeclaration.jvmName(): String {
    if (parameters.isEmpty() && resultType() == "Void" && name in setOf("notify", "notifyAll", "wait")) return "\$$name"
    fun erases(type: TypeReference): Boolean = type.name in setOf("List", "Class", "AnyValue", "AnyRef") || type.arguments.any(::erases)
    // Keep overloads distinct even when two O types have the same JVM descriptor.
    if (parameters.none { erases(it.type) }) return name
    val signature = parameters.joinToString("\$") { it.type.typeName().replace('[', '$').replace(']', '$') }
    return "$name\$$signature"
}
