package team.niiby.compiler.generator

import team.niiby.compiler.analyzer.Analyzer
import team.niiby.compiler.analyzer.SemanticInfo
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.ClassDeclaration
import team.niiby.compiler.parser.ConstructorDeclaration
import team.niiby.compiler.parser.ExpressionBody
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Program
import team.niiby.compiler.parser.VariableDeclaration

class JasminGenerator {
    /** Returns one Jasmin source file per O class, keyed by filename. */
    fun generate(program: Program): Map<String, String> =
        ClassGenerator(program, Analyzer().resolve(program)).generate()
}

internal fun getterName(owner: String, field: String) = "\$read\$$owner\$$field"
private fun initializerName(field: String) = "\$init\$$field"
private fun stateName(field: String) = "\$state\$$field"

internal class ClassGenerator(program: Program, private val semantics: SemanticInfo) {
    private val classes = program.classes.associateBy { it.type.name }
    private val constructorMarkers = buildMap {
        for (declaration in program.classes) {
            val groups = declaration.members.filterIsInstance<ConstructorDeclaration>().groupBy { parameterDescriptor(it.parameters) }
            for (constructors in groups.values) {
                constructors.forEachIndexed { index, constructor -> put(constructor, index) }
            }
        }
    }

    fun generate(): Map<String, String> = classes.values.associate { declaration ->
        "${declaration.type.name}.j" to generateClass(declaration)
    }

    // The JVM erases some O parameter types. Hidden null String parameters distinguish colliding constructors.
    fun markers(constructor: ConstructorDeclaration?) = constructorMarkers[constructor] ?: 0

    fun constructorDescriptor(constructor: ConstructorDeclaration?): String =
        "(${parameterDescriptor(constructor?.parameters.orEmpty())}${"Ljava/lang/String;".repeat(markers(constructor))})V"

    private fun generateClass(declaration: ClassDeclaration): String = buildString {
        val owner = declaration.type.name
        if (owner == "Void") throw GenerationException("The name 'Void' is reserved for methods without a result", declaration.span.start)
        val parent = declaration.parent?.name
        val parentDeclaration = classes[parent]
        val superClass = when {
            parent == null || parent in setOf("Class", "AnyRef", "AnyValue") -> "java/lang/Object"
            parentDeclaration != null -> jvmClassName(parent)
            else -> throw GenerationException("Cannot extend built-in type '$parent' on the JVM", declaration.parent.span.start)
        }
        val parentConstructors = parentDeclaration?.members.orEmpty().filterIsInstance<ConstructorDeclaration>()
        if (parentConstructors.isNotEmpty() && parentConstructors.none { it.parameters.isEmpty() }) {
            throw GenerationException("Superclass '$parent' needs a parameterless constructor", declaration.span.start)
        }

        appendLine(".class public ${jvmClassName(owner)}")
        appendLine(".super $superClass")
        val fields = declaration.members.filterIsInstance<VariableDeclaration>()
        for (field in fields) {
            val type = semantics.types.getValue(field.initializer)
            appendLine(".field protected \"${field.name}\" ${descriptor(type)}")
            appendLine(".field private \"${stateName(field.name)}\" I")
        }

        val constructors = declaration.members.filterIsInstance<ConstructorDeclaration>()
        if (constructors.isEmpty()) appendLine(generateConstructor(owner, superClass, fields, null))
        for (constructor in constructors) appendLine(generateConstructor(owner, superClass, fields, constructor))
        for (field in fields) {
            appendLine(generateFieldInitializer(owner, field))
            appendLine(generateFieldGetter(owner, field))
        }
        for (method in declaration.members.filterIsInstance<MethodDeclaration>()) {
            appendLine(generateMethod(owner, method))
            for (result in bridgeResults(parentDeclaration, method)) appendLine(generateBridge(owner, method, result))
        }
    }

    private fun generateConstructor(
        owner: String,
        superClass: String,
        fields: List<VariableDeclaration>,
        constructor: ConstructorDeclaration?,
    ): String {
        val parameters = constructor?.parameters.orEmpty()
        val method = MethodGenerator(semantics, this, owner, "Void", parameters)
        method.code.apply {
            repeat(markers(constructor)) { local("Class") }
            load(owner, 0)
            emit("invokespecial $superClass/<init>()V", pops = 1)
            fields.forEach {
                load(owner, 0)
                emit("invokespecial ${jvmClassName(owner)}/${initializerName(it.name)}()V", pops = 1)
            }
            if (constructor == null || method.block(constructor.body)) returnValue("Void")
        }
        return method.code.render("public <init>${constructorDescriptor(constructor)}")
    }

    private fun generateFieldInitializer(owner: String, field: VariableDeclaration): String {
        val method = MethodGenerator(semantics, this, owner, "Void")
        val type = semantics.types.getValue(field.initializer)
        val state = stateName(field.name)
        method.code.apply {
            // Constructors initiate fields in source order. Accessors also initialize dependencies on demand.
            val checkCycle = label()
            val initialize = label()
            load(owner, 0)
            readField(owner, state, "Integer")
            emit("iconst_2", pushes = 1)
            branch("if_icmpne", checkCycle, 2)
            returnValue("Void")
            place(checkCycle)
            load(owner, 0)
            readField(owner, state, "Integer")
            branch("ifeq", initialize, 1)
            failure("Cyclic field initialization: $owner.${field.name}")
            place(initialize)
            load(owner, 0)
            emit("iconst_1", pushes = 1)
            writeField(owner, state, "Integer")
            load(owner, 0)
            method.expression(field.initializer)
            writeField(owner, field.name, type)
            load(owner, 0)
            emit("iconst_2", pushes = 1)
            writeField(owner, state, "Integer")
            returnValue("Void")
        }
        return method.code.render("private ${initializerName(field.name)}()V")
    }

    private fun generateFieldGetter(owner: String, field: VariableDeclaration): String {
        val type = semantics.types.getValue(field.initializer)
        return MethodCode().apply {
            local(owner)
            load(owner, 0)
            emit("invokespecial ${jvmClassName(owner)}/${initializerName(field.name)}()V", pops = 1)
            load(owner, 0)
            readField(owner, field.name, type)
            returnValue(type)
        }.render("public final ${getterName(owner, field.name)}()${descriptor(type)}")
    }

    private fun generateMethod(owner: String, method: MethodDeclaration): String {
        val result = method.resultType()
        val generator = MethodGenerator(semantics, this, owner, result, method.parameters)
        when (val body = method.body) {
            is ExpressionBody -> generator.returnExpression(body.expression)
            is Block -> if (generator.block(body)) {
                check(result == "Void") { "Missing return in ${method.name}" }
                generator.code.returnValue(result)
            }
        }
        return generator.code.render("public ${method.jvmName()}(${parameterDescriptor(method.parameters)})${descriptor(result)}")
    }

    private fun bridgeResults(parent: ClassDeclaration?, method: MethodDeclaration): List<String> =
        generateSequence(parent) { classes[it.parent?.name] }
            .flatMap { it.members.filterIsInstance<MethodDeclaration>() }
            .filter { it.name == method.name && it.parameters.map { p -> p.type.typeName() } == method.parameters.map { p -> p.type.typeName() } }
            .map { it.resultType() }
            .filter { descriptor(it) != descriptor(method.resultType()) }
            .distinctBy(::descriptor)
            .toList()

    private fun generateBridge(owner: String, method: MethodDeclaration, inheritedResult: String): String {
        val parameters = parameterDescriptor(method.parameters)
        val result = method.resultType()
        return MethodCode().apply {
            local(owner)
            load(owner, 0)
            for (parameter in method.parameters) {
                val type = parameter.type.typeName()
                load(type, local(type))
            }
            val argumentSlots = method.parameters.sumOf { slots(it.type.typeName()) }
            emit("invokevirtual ${jvmClassName(owner)}/${method.jvmName()}($parameters)${descriptor(result)}", pops = 1 + argumentSlots, pushes = slots(result))
            coerce(result, inheritedResult)
            returnValue(inheritedResult)
        }.render("public ${method.jvmName()}($parameters)${descriptor(inheritedResult)}", synthetic = true)
    }
}
