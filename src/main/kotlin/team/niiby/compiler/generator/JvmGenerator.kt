package team.niiby.compiler.generator

import java.lang.classfile.ClassBuilder
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_BRIDGE
import java.lang.classfile.ClassFile.ACC_FINAL
import java.lang.classfile.ClassFile.ACC_PRIVATE
import java.lang.classfile.ClassFile.ACC_PROTECTED
import java.lang.classfile.ClassFile.ACC_PUBLIC
import java.lang.classfile.ClassFile.ACC_SUPER
import java.lang.classfile.ClassFile.ACC_SYNTHETIC
import java.lang.classfile.ClassHierarchyResolver
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.ConstantDescs.MTD_void
import java.lang.constant.MethodTypeDesc
import team.niiby.compiler.analyzer.Analyzer
import team.niiby.compiler.analyzer.SemanticInfo
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.ClassDeclaration
import team.niiby.compiler.parser.ConstructorDeclaration
import team.niiby.compiler.parser.ExpressionBody
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Program
import team.niiby.compiler.parser.VariableDeclaration

class JvmGenerator {
    /** Returns one JVM class file per O class, keyed by filename. */
    fun generate(program: Program): Map<String, ByteArray> =
        ClassGenerator(program, Analyzer().resolve(program)).generate()
}

internal fun getterName(owner: ClassDesc, field: String) = "\$read\$${owner.displayName()}\$$field"
private fun initializerName(field: String) = "\$init\$$field"
private fun stateName(field: String) = "\$state\$$field"

internal class ClassGenerator(program: Program, private val semantics: SemanticInfo) {
    private val classes = program.classes.associateBy { it.type.name }
    private val superClasses = classes.values.associate { typeSymbol(it.type.name) to superClass(it) }
    private val constructorMarkers = buildMap {
        for (declaration in program.classes) {
            val groups = declaration.members.filterIsInstance<ConstructorDeclaration>().groupBy { parameterTypes(it.parameters) }
            for (constructors in groups.values) {
                constructors.forEachIndexed { index, constructor -> put(constructor, index) }
            }
        }
    }
    private val classFile = ClassFile.of(
        ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.of(emptyList(), superClasses).orElse(ClassHierarchyResolver.defaultResolver()),
        ),
    )

    fun generate(): Map<String, ByteArray> = classes.values.associate { declaration ->
        "${declaration.type.name}.class" to generateClass(declaration)
    }

    // Hidden null String parameters distinguish constructors with identical erased signatures.
    fun markers(constructor: ConstructorDeclaration?) = constructorMarkers[constructor] ?: 0

    fun constructorSignature(constructor: ConstructorDeclaration?): MethodTypeDesc {
        val parameters = parameterTypes(constructor?.parameters.orEmpty())
        return MethodTypeDesc.of(CD_void, parameters + List(markers(constructor)) { CD_String })
    }

    private fun superClass(declaration: ClassDeclaration): ClassDesc {
        if (declaration.type.name == "Void") {
            throw GenerationException("The name 'Void' is reserved for methods without a result", declaration.span.start)
        }
        val parent = declaration.parent ?: return CD_Object
        if (parent.name in setOf("Class", "AnyRef", "AnyValue")) return CD_Object
        val parentDeclaration = classes[parent.name]
            ?: throw GenerationException("Cannot extend built-in type '${parent.name}' on the JVM", parent.span.start)
        val constructors = parentDeclaration.members.filterIsInstance<ConstructorDeclaration>()
        if (constructors.isNotEmpty() && constructors.none { it.parameters.isEmpty() }) {
            throw GenerationException("Superclass '${parent.name}' needs a parameterless constructor", declaration.span.start)
        }
        return typeSymbol(parent.name)
    }

    private fun generateClass(declaration: ClassDeclaration): ByteArray {
        val owner = typeSymbol(declaration.type.name)
        val parent = superClasses.getValue(owner)
        val parentDeclaration = classes[declaration.parent?.name]
        return try {
            classFile.build(owner) { builder ->
                builder.withVersion(ClassFile.JAVA_25_VERSION, 0)
                builder.withFlags(ACC_PUBLIC or ACC_SUPER)
                builder.withSuperclass(parent)

                val fields = declaration.members.filterIsInstance<VariableDeclaration>()
                for (field in fields) {
                    builder.withField(field.name, typeSymbol(semantics.types.getValue(field.initializer)), ACC_PROTECTED)
                    builder.withField(stateName(field.name), CD_int, ACC_PRIVATE)
                }

                val constructors = declaration.members.filterIsInstance<ConstructorDeclaration>()
                if (constructors.isEmpty()) generateConstructor(builder, owner, parent, fields, null)
                for (constructor in constructors) generateConstructor(builder, owner, parent, fields, constructor)
                for (field in fields) generateField(builder, owner, field)
                for (method in declaration.members.filterIsInstance<MethodDeclaration>()) {
                    generateMethod(builder, method)
                    for (result in bridgeResults(parentDeclaration, method)) generateBridge(builder, owner, method, result)
                }
            }
        } catch (error: IllegalArgumentException) {
            throw GenerationException("Cannot generate ${declaration.type.name}: ${error.message}", declaration.span.start)
                .also { it.initCause(error) }
        }
    }

    private fun generateConstructor(
        builder: ClassBuilder,
        owner: ClassDesc,
        parent: ClassDesc,
        fields: List<VariableDeclaration>,
        constructor: ConstructorDeclaration?,
    ) {
        builder.withMethodBody("<init>", constructorSignature(constructor), ACC_PUBLIC) { code ->
            val method = MethodGenerator(semantics, this, code, "Void", constructor?.parameters.orEmpty())
            val receiver = code.receiverSlot()
            code.aload(receiver)
            code.invokespecial(parent, "<init>", MTD_void)
            for (field in fields) {
                code.aload(receiver)
                code.invokespecial(owner, initializerName(field.name), MTD_void)
            }
            if (constructor == null || method.block(constructor.body)) code.return_()
        }
    }

    private fun generateField(builder: ClassBuilder, owner: ClassDesc, field: VariableDeclaration) {
        val type = semantics.types.getValue(field.initializer)
        val state = stateName(field.name)
        builder.withMethodBody(initializerName(field.name), MTD_void, ACC_PRIVATE) { code ->
            val method = MethodGenerator(semantics, this, code, "Void")
            val receiver = code.receiverSlot()
            // Constructors initiate fields in source order. Accessors also initialize dependencies on demand.
            val checkCycle = code.newLabel()
            val initialize = code.newLabel()
            code.aload(receiver)
            code.readField(owner, state, "Integer")
            code.iconst_2()
            code.if_icmpne(checkCycle)
            code.return_()
            code.labelBinding(checkCycle)
            code.aload(receiver)
            code.readField(owner, state, "Integer")
            code.ifeq(initialize)
            code.failAtRuntime("Cyclic field initialization: ${owner.displayName()}.${field.name}")
            code.labelBinding(initialize)
            code.aload(receiver)
            code.iconst_1()
            code.writeField(owner, state, "Integer")
            code.aload(receiver)
            method.expression(field.initializer)
            code.writeField(owner, field.name, type)
            code.aload(receiver)
            code.iconst_2()
            code.writeField(owner, state, "Integer")
            code.return_()
        }
        builder.withMethodBody(getterName(owner, field.name), MethodTypeDesc.of(typeSymbol(type)), ACC_PUBLIC or ACC_FINAL) { code ->
            code.aload(code.receiverSlot())
            code.invokespecial(owner, initializerName(field.name), MTD_void)
            code.aload(code.receiverSlot())
            code.readField(owner, field.name, type)
            code.returnValue(type)
        }
    }

    private fun generateMethod(builder: ClassBuilder, method: MethodDeclaration) {
        builder.withMethodBody(method.jvmName(), method.jvmSignature(), ACC_PUBLIC) { code ->
            val result = method.resultType()
            val generator = MethodGenerator(semantics, this, code, result, method.parameters)
            when (val body = method.body) {
                is ExpressionBody -> generator.returnExpression(body.expression)
                is Block -> if (generator.block(body)) {
                    check(result == "Void") { "Missing return in ${method.name}" }
                    code.return_()
                }
            }
        }
    }

    private fun bridgeResults(parent: ClassDeclaration?, method: MethodDeclaration): List<String> {
        val parameters = method.parameters.map { it.type.typeName() }
        return generateSequence(parent) { classes[it.parent?.name] }
            .flatMap { it.members.filterIsInstance<MethodDeclaration>() }
            .filter { it.name == method.name && it.parameters.map { p -> p.type.typeName() } == parameters }
            .map { it.resultType() }
            .filter { typeSymbol(it) != typeSymbol(method.resultType()) }
            .distinctBy(::typeSymbol)
            .toList()
    }

    private fun generateBridge(builder: ClassBuilder, owner: ClassDesc, method: MethodDeclaration, inheritedResult: String) {
        val name = method.jvmName()
        val flags = ACC_PUBLIC or ACC_BRIDGE or ACC_SYNTHETIC
        builder.withMethodBody(name, method.jvmSignature(inheritedResult), flags) { code ->
            code.aload(code.receiverSlot())
            for ((index, parameter) in method.parameters.withIndex()) {
                code.load(parameter.type.typeName(), code.parameterSlot(index))
            }
            code.invokevirtual(owner, name, method.jvmSignature())
            code.coerce(method.resultType(), inheritedResult)
            code.returnValue(inheritedResult)
        }
    }
}
