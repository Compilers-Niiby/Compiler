package team.niiby.compiler.generator

import team.niiby.compiler.analyzer.Analyzer
import team.niiby.compiler.analyzer.ConsoleCall
import team.niiby.compiler.analyzer.SemanticInfo
import team.niiby.compiler.parser.Block
import team.niiby.compiler.parser.ClassDeclaration
import team.niiby.compiler.parser.ConstructorDeclaration
import team.niiby.compiler.parser.ExpressionBody
import team.niiby.compiler.parser.MethodDeclaration
import team.niiby.compiler.parser.Program
import team.niiby.compiler.parser.VariableDeclaration

class JasminGenerator {
    /** Returns the Jasmin source files for the program, keyed by filename. */
    fun generate(program: Program): Map<String, String> =
        ClassGenerator(program, Analyzer().resolve(program)).generate()
}

// Constructor parameter types that a program can receive from the command line.
private val ARGUMENT_PARSERS = mapOf(
    "Integer" to "java/lang/Integer/parseInt(Ljava/lang/String;)I",
    "Real" to "java/lang/Double/parseDouble(Ljava/lang/String;)D",
    "Boolean" to "java/lang/Boolean/parseBoolean(Ljava/lang/String;)Z",
)

internal fun MethodDeclaration.isEntryPoint() = name == "main" && parameters.isEmpty() && returnType == null

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

    fun generate(): Map<String, String> = buildMap {
        for (declaration in classes.values) {
            put("${jvmClassName(declaration.type.name)}.j", generateClass(declaration))
        }
        if (semantics.consoleCalls.containsValue(ConsoleCall.READ_INTEGER)) {
            put(JvmConsole.inputFilename, JvmConsole.generateInputClass())
        }
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
        if (declaration.members.any { it is MethodDeclaration && it.isEntryPoint() }) appendLine(generateLauncher(declaration))
    }

    // static main(String[]) converts command-line arguments, constructs the class, and calls its main method.
    private fun generateLauncher(declaration: ClassDeclaration): String {
        val className = jvmClassName(declaration.type.name)
        val declared = declaration.members.filterIsInstance<ConstructorDeclaration>()
        val launchable = (if (declared.isEmpty()) listOf(null) else declared)
            .filter { constructor -> constructor?.parameters.orEmpty().all { it.type.typeName() in ARGUMENT_PARSERS } }
            .distinctBy { it?.parameters.orEmpty().size }
        val code = MethodCode()
        val arguments = code.local("Class")
        val invalid = code.label()
        var parsesNumbers = false
        for (constructor in launchable) {
            val parameters = constructor?.parameters.orEmpty()
            val next = code.label()
            code.load("Class", arguments)
            code.emit("arraylength", pops = 1, pushes = 1)
            code.emit("bipush ${parameters.size}", pushes = 1)
            code.branch("if_icmpne", next, 2)
            val tryStart = code.label()
            val tryEnd = code.label()
            code.place(tryStart)
            val values = parameters.mapIndexed { index, parameter ->
                val type = parameter.type.typeName()
                code.load("Class", arguments)
                code.emit("bipush $index", pushes = 1)
                code.emit("aaload", pops = 2, pushes = 1)
                code.emit("invokestatic ${ARGUMENT_PARSERS.getValue(type)}", pops = 1, pushes = slots(type))
                val slot = code.local(type)
                code.store(type, slot)
                type to slot
            }
            code.place(tryEnd)
            if (parameters.isNotEmpty()) {
                code.catchBlock("java/lang/NumberFormatException", tryStart, tryEnd, invalid)
                parsesNumbers = true
            }
            code.emit("new $className", pushes = 1)
            code.emit("dup", pops = 1, pushes = 2)
            for ((type, slot) in values) code.load(type, slot)
            val markers = markers(constructor)
            repeat(markers) { code.emit("aconst_null", pushes = 1) }
            code.emit(
                "invokespecial $className/<init>${constructorDescriptor(constructor)}",
                pops = 1 + values.sumOf { slots(it.first) } + markers,
            )
            code.emit("invokevirtual $className/main()V", pops = 1)
            code.returnValue("Void")
            code.place(next)
        }
        val counts = launchable.map { it?.parameters.orEmpty().size }.sorted()
        printError(code, if (counts.isEmpty()) "${declaration.type.name} cannot be started with command-line arguments"
            else "Expected ${counts.joinToString(" or ")} program argument(s)")
        exit(code)
        if (parsesNumbers) {
            code.place(invalid)
            printError(code, "Invalid program argument:")
            code.emit("getstatic java/lang/System/err Ljava/io/PrintStream;", pushes = 1)
            code.emit("swap", pops = 2, pushes = 2)
            code.emit("invokevirtual java/lang/Throwable/getMessage()Ljava/lang/String;", pops = 1, pushes = 1)
            code.emit("invokevirtual java/io/PrintStream/println(Ljava/lang/String;)V", pops = 2)
            exit(code)
        }
        return code.render("public static main([Ljava/lang/String;)V")
    }

    private fun printError(code: MethodCode, message: String) {
        code.emit("getstatic java/lang/System/err Ljava/io/PrintStream;", pushes = 1)
        code.string(message)
        code.emit("invokevirtual java/io/PrintStream/println(Ljava/lang/String;)V", pops = 2)
    }

    private fun exit(code: MethodCode) {
        code.emit("iconst_1", pushes = 1)
        code.emit("invokestatic java/lang/System/exit(I)V", pops = 1)
        code.returnValue("Void")
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
        return generator.code.render("public ${method.jvmSignature()}")
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
        val result = method.resultType()
        // Jasmin spells access bit 0x0040 "volatile", which means ACC_BRIDGE on methods.
        return MethodCode().apply {
            local(owner)
            load(owner, 0)
            for (parameter in method.parameters) {
                val type = parameter.type.typeName()
                load(type, local(type))
            }
            val argumentSlots = method.parameters.sumOf { slots(it.type.typeName()) }
            emit("invokevirtual ${jvmClassName(owner)}/${method.jvmSignature()}", pops = 1 + argumentSlots, pushes = slots(result))
            coerce(result, inheritedResult)
            returnValue(inheritedResult)
        }.render("public volatile ${method.jvmSignature(inheritedResult)}", synthetic = true)
    }
}
