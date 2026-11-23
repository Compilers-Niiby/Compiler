package team.niiby.compiler.generator

import java.lang.classfile.CodeBuilder
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.ClassDesc
import java.lang.constant.MethodTypeDesc

internal fun CodeBuilder.local(type: String): Int = allocateLocal(typeKind(type))

internal fun CodeBuilder.load(type: String, slot: Int) = loadLocal(typeKind(type), slot)

internal fun CodeBuilder.store(type: String, slot: Int) = storeLocal(typeKind(type), slot)

internal fun CodeBuilder.discard(type: String) {
    when (typeKind(type).slotSize()) {
        1 -> pop()
        2 -> pop2()
    }
}

internal fun CodeBuilder.readField(owner: ClassDesc, name: String, type: String) =
    getfield(owner, name, typeSymbol(type))

internal fun CodeBuilder.writeField(owner: ClassDesc, name: String, type: String) =
    putfield(owner, name, typeSymbol(type))

internal fun CodeBuilder.returnValue(type: String) = return_(typeKind(type))

internal fun CodeBuilder.box(type: String) {
    val wrapper = boxedClass(type) ?: return
    invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, typeSymbol(type)))
}

internal fun CodeBuilder.coerce(actual: String, expected: String) {
    if (typeSymbol(expected).isClassOrInterface) box(actual)
}

internal fun CodeBuilder.failAtRuntime(message: String) {
    val exception = ClassDesc.of("java.lang.IllegalStateException")
    new_(exception)
    dup()
    ldc(constantPool().stringEntry(message))
    invokespecial(exception, "<init>", MethodTypeDesc.of(CD_void, CD_String))
    athrow()
}
