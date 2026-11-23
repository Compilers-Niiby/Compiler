package team.niiby.compiler.generator

import java.lang.classfile.TypeKind
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Boolean
import java.lang.constant.ConstantDescs.CD_Double
import java.lang.constant.ConstantDescs.CD_Integer
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_boolean
import java.lang.constant.ConstantDescs.CD_double
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Parameter
import team.niiby.compiler.parser.TypeReference

internal val ARRAY_LIST: ClassDesc = ClassDesc.of("java.util.ArrayList")

internal fun TypeReference.typeName(): String = if (arguments.isEmpty()) name else "${name}[${arguments.single().typeName()}]"

internal fun typeSymbol(type: String): ClassDesc = when {
    type == "Integer" -> CD_int
    type == "Boolean" -> CD_boolean
    type == "Real" -> CD_double
    type == "Void" -> CD_void
    type in setOf("Class", "AnyRef", "AnyValue") -> CD_Object
    type.startsWith("Array[") -> typeSymbol(elementType(type)).arrayType()
    type.startsWith("List[") -> ARRAY_LIST
    else -> ClassDesc.ofInternalName(type)
}

internal fun elementType(type: String): String = type.substringAfter('[').dropLast(1)

internal fun typeKind(type: String): TypeKind = TypeKind.from(typeSymbol(type))

internal fun boxedClass(type: String): ClassDesc? = when (type) {
    "Integer" -> CD_Integer
    "Boolean" -> CD_Boolean
    "Real" -> CD_Double
    else -> null
}

internal fun parameterTypes(parameters: List<Parameter>): List<ClassDesc> = parameters.map { typeSymbol(it.type.typeName()) }

internal fun MethodDeclaration.resultType(): String = returnType?.typeName() ?: "Void"

internal fun MethodDeclaration.jvmSignature(result: String = resultType()): MethodTypeDesc =
    MethodTypeDesc.of(typeSymbol(result), parameterTypes(parameters))

internal fun MethodDeclaration.jvmName(): String {
    if (parameters.isEmpty() && resultType() == "Void" && name in setOf("notify", "notifyAll", "wait")) return "\$$name"
    fun erases(type: TypeReference): Boolean = type.name in setOf("List", "Class", "AnyValue", "AnyRef") || type.arguments.any(::erases)
    // Keep overloads distinct even when two O types have the same JVM descriptor.
    if (parameters.none { erases(it.type) }) return name
    val signature = parameters.joinToString("\$") { it.type.typeName().replace('[', '$').replace(']', '$') }
    return "$name\$$signature"
}
