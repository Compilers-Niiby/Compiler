package team.niiby.compiler.generator

import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Parameter
import team.niiby.compiler.parser.TypeReference

private val jasminKeywords = setOf(
    "is", "from", "method", "to", "using", "tableswitch", "lookupswitch", "default",
    "public", "private", "protected", "static", "final", "synchronized", "volatile", "transient",
    "native", "interface", "abstract", "strictfp", "annotation", "enum",
)

internal fun jvmClassName(name: String): String = if (name in jasminKeywords) "\$" + name else name

internal fun TypeReference.typeName(): String = if (arguments.isEmpty()) name else "${name}[${arguments.single().typeName()}]"

internal fun descriptor(type: String): String = when {
    type == "Integer" -> "I"
    type == "Boolean" -> "Z"
    type == "Real" -> "D"
    type == "Void" -> "V"
    type in setOf("Class", "AnyRef", "AnyValue") -> "Ljava/lang/Object;"
    type.startsWith("Array[") -> "[${descriptor(elementType(type))}"
    type.startsWith("List[") -> "Ljava/util/ArrayList;"
    else -> "L${jvmClassName(type)};"
}

internal fun elementType(type: String): String = type.substringAfter('[').dropLast(1)

internal fun slots(type: String): Int = when (type) {
    "Real" -> 2
    "Void" -> 0
    else -> 1
}

internal fun instructionPrefix(type: String): String = when (type) {
    "Integer", "Boolean" -> "i"
    "Real" -> "d"
    else -> "a"
}

internal fun boxedClass(type: String): String? = when (type) {
    "Integer" -> "java/lang/Integer"
    "Boolean" -> "java/lang/Boolean"
    "Real" -> "java/lang/Double"
    else -> null
}

internal fun referenceName(type: String): String = descriptor(type).let {
    if (it.startsWith("L")) it.substring(1, it.lastIndex) else it
}

internal fun parameterDescriptor(parameters: List<Parameter>): String =
    parameters.joinToString("") { descriptor(it.type.typeName()) }

internal fun MethodDeclaration.resultType(): String = returnType?.typeName() ?: "Void"

internal fun MethodDeclaration.jvmName(): String {
    if (parameters.isEmpty() && resultType() == "Void" && name in setOf("notify", "notifyAll", "wait")) return "\$$name"
    fun erases(type: TypeReference): Boolean = type.name in setOf("List", "Class", "AnyValue", "AnyRef") || type.arguments.any(::erases)
    // Keep overloads distinct even when two O types have the same JVM descriptor.
    if (parameters.none { erases(it.type) }) return name
    val signature = parameters.joinToString("\$") { it.type.typeName().replace('[', '$').replace(']', '$') }
    return "$name\$$signature"
}
