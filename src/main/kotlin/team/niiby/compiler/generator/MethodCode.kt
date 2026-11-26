package team.niiby.compiler.generator

internal class MethodCode {
    private val lines = mutableListOf<String>()
    private val catches = mutableListOf<String>()
    private val labelDepths = mutableMapOf<String, Int>()
    private var nextLabel = 0
    private var depth: Int? = 0
    private var maxStack = 0
    private var maxLocals = 0

    fun local(type: String): Int = maxLocals.also { maxLocals += slots(type) }

    fun label(): String = "L${nextLabel++}"

    fun place(label: String) {
        val incoming = labelDepths[label]
        if (depth != null && incoming != null) check(depth == incoming) { "Inconsistent stack at $label" }
        val atLabel = checkNotNull(incoming ?: depth) { "Unreachable label $label" }
        depth = atLabel
        labelDepths[label] = atLabel
        lines.add("$label:")
    }

    fun emit(instruction: String, pops: Int = 0, pushes: Int = 0) {
        val before = checkNotNull(depth) { "Unreachable instruction: $instruction" }
        check(before >= pops) { "Stack underflow at $instruction" }
        val after = before - pops + pushes
        depth = after
        maxStack = maxOf(maxStack, after)
        lines.add("    $instruction")
    }

    fun branch(instruction: String, label: String, pops: Int) {
        emit("$instruction $label", pops)
        val previous = labelDepths.putIfAbsent(label, checkNotNull(depth))
        check(previous == null || previous == depth) { "Inconsistent stack at $label" }
    }

    fun jump(label: String) {
        branch("goto_w", label, 0)
        depth = null
    }

    // The JVM has wide unconditional jumps. Invert the condition to skip a wide jump.
    fun wideBranch(inverseInstruction: String, target: String, pops: Int) {
        val skip = label()
        branch(inverseInstruction, skip, pops)
        jump(target)
        place(skip)
    }

    fun terminate(instruction: String, pops: Int = 0) {
        emit(instruction, pops)
        check(depth == 0)
        depth = null
    }

    fun load(type: String, index: Int) = emit("${instructionPrefix(type)}load $index", pushes = slots(type))

    fun store(type: String, index: Int) = emit("${instructionPrefix(type)}store $index", pops = slots(type))

    fun discard(type: String) {
        when (slots(type)) {
            1 -> emit("pop", pops = 1)
            2 -> emit("pop2", pops = 2)
        }
    }

    fun readField(owner: String, name: String, type: String) =
        emit("getfield ${jvmClassName(owner)}/$name ${descriptor(type)}", pops = 1, pushes = slots(type))

    fun writeField(owner: String, name: String, type: String) =
        emit("putfield ${jvmClassName(owner)}/$name ${descriptor(type)}", pops = 1 + slots(type))

    fun returnValue(type: String) {
        if (type == "Void") terminate("return") else terminate("${instructionPrefix(type)}return", slots(type))
    }

    fun box(type: String) {
        val wrapper = boxedClass(type) ?: return
        emit("invokestatic $wrapper/valueOf(${descriptor(type)})L$wrapper;", pops = slots(type), pushes = 1)
    }

    fun coerce(actual: String, expected: String) {
        if (boxedClass(expected) == null && expected != "Void") box(actual)
    }

    fun string(value: String) {
        val escaped = buildString {
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    in ' '..'~' -> append(character)
                    else -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                }
            }
        }
        emit("ldc_w \"$escaped\"", pushes = 1)
    }

    fun failure(message: String) {
        emit("new java/lang/IllegalStateException", pushes = 1)
        emit("dup", pops = 1, pushes = 2)
        string(message)
        emit("invokespecial java/lang/IllegalStateException/<init>(Ljava/lang/String;)V", pops = 2)
        terminate("athrow", 1)
    }

    // The handler starts with the caught exception as the only stack value.
    fun catchBlock(exception: String, from: String, to: String, handler: String) {
        labelDepths[handler] = 1
        catches.add("    .catch $exception from $from to $to using $handler")
    }

    fun render(signature: String, synthetic: Boolean = false): String = buildString {
        check(depth == null) { "Missing return in $signature" }
        appendLine(".method $signature")
        if (synthetic) appendLine("    .synthetic")
        appendLine("    .limit stack $maxStack")
        appendLine("    .limit locals $maxLocals")
        lines.forEach { appendLine(it) }
        catches.forEach { appendLine(it) }
        appendLine(".end method")
    }
}
