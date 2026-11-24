package team.niiby.compiler.generator

import team.niiby.compiler.lexer.TokenType
import team.niiby.compiler.lexer.decodeStringLiteral
import team.niiby.compiler.parser.Expression
import team.niiby.compiler.parser.GroupedExpression
import team.niiby.compiler.parser.LiteralExpression
import team.niiby.compiler.parser.UnaryExpression

private const val LIST_CLASS = "java/util/ArrayList"

internal class BuiltinGenerator(private val method: MethodGenerator) {
    private val code = method.code

    fun literal(literal: LiteralExpression) {
        when (literal.kind) {
            TokenType.INTEGER_LITERAL -> integer(literal.lexeme.toIntOrNull() ?: method.fail(literal, "Integer literal is outside the JVM int range"))
            TokenType.REAL_LITERAL -> {
                val normalized = literal.lexeme.map { if (it.isDigit()) '0' + it.digitToInt() else it }.joinToString("")
                val value = normalized.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: method.fail(literal, "Real literal is outside the JVM double range")
                real(value)
            }
            TokenType.BOOLEAN_LITERAL -> integer(if (literal.lexeme == "true") 1 else 0)
            TokenType.STRING_LITERAL -> code.string(decodeStringLiteral(literal.lexeme))
            else -> error("Unexpected literal kind ${literal.kind}")
        }
    }

    fun unary(node: UnaryExpression) {
        val operand = generateSequence(node.operand) { (it as? GroupedExpression)?.expression }.last()
        if (node.operator == TokenType.MINUS && operand is LiteralExpression &&
            operand.kind == TokenType.INTEGER_LITERAL && operand.lexeme.toLongOrNull() == 2147483648L
        ) {
            integer(Int.MIN_VALUE)
            return
        }
        method.expression(node.operand)
        if (node.operator == TokenType.MINUS) negate(method.typeOf(node.operand))
    }

    fun constant(type: String, name: String) {
        when (type) {
            "Integer" -> integer(if (name == "Min") Int.MIN_VALUE else Int.MAX_VALUE)
            "Real" -> real(when (name) {
                "Min" -> -Double.MAX_VALUE
                "Max" -> Double.MAX_VALUE
                else -> Math.ulp(1.0)
            })
        }
    }

    fun binary(operator: TokenType, left: Expression, right: Expression) = arithmetic(
        when (operator) {
            TokenType.PLUS -> "add"
            TokenType.MINUS -> "sub"
            TokenType.STAR -> "mul"
            TokenType.SLASH -> "div"
            TokenType.LESS -> "lt"
            TokenType.LESS_EQUAL -> "le"
            TokenType.GREATER -> "gt"
            TokenType.GREATER_EQUAL -> "ge"
            TokenType.EQUAL_EQUAL -> "eq"
            else -> error("Unsupported operator $operator")
        },
        left, right,
    )

    private fun arithmetic(operation: String, left: Expression, right: Expression) {
        val real = method.typeOf(left) == "Real" || method.typeOf(right) == "Real"
        for (operand in listOf(left, right)) {
            method.expression(operand)
            if (real && method.typeOf(operand) == "Integer") code.emit("i2d", pops = 1, pushes = 2)
        }
        if (operation in setOf("lt", "le", "gt", "ge", "eq")) {
            if (real) {
                // NaN must compare false for both the less-than and greater-than families.
                val compare = if (operation == "lt" || operation == "le") "dcmpg" else "dcmpl"
                code.emit(compare, pops = 4, pushes = 1)
                comparison("if$operation", 1)
            } else comparison("if_icmp$operation", 2)
        } else {
            if (real) code.emit("d$operation", pops = 4, pushes = 2)
            else code.emit("i$operation", pops = 2, pushes = 1)
        }
    }

    private fun comparison(branch: String, pops: Int) {
        val yes = code.label()
        val end = code.label()
        code.branch(branch, yes, pops)
        integer(0)
        code.jump(end)
        code.place(yes)
        integer(1)
        code.place(end)
    }

    fun construct(type: String, arguments: List<Expression>) {
        when {
            type.startsWith("Array[") -> {
                val element = elementType(type)
                method.expression(arguments.single())
                val instruction = when (element) {
                    "Integer" -> "newarray int"
                    "Boolean" -> "newarray boolean"
                    "Real" -> "newarray double"
                    else -> "anewarray ${referenceName(element)}"
                }
                code.emit(instruction, pops = 1, pushes = 1)
            }
            type.startsWith("List[") -> list(arguments)
            else -> {
                val value = arguments.single()
                method.expression(value)
                when {
                    type == "Real" && method.typeOf(value) == "Integer" -> code.emit("i2d", pops = 1, pushes = 2)
                    type == "Integer" && method.typeOf(value) == "Real" -> code.emit("d2i", pops = 2, pushes = 1)
                }
            }
        }
    }

    private fun list(arguments: List<Expression>) {
        when (arguments.size) {
            0 -> {
                newList()
                code.emit("invokespecial $LIST_CLASS/<init>()V", pops = 1)
                return
            }
            1 -> {
                integer(1)
                boxed(arguments.single())
            }
            else -> {
                boxed(arguments[0])
                method.expression(arguments[1])
                code.emit("swap", pops = 2, pushes = 2)
            }
        }
        code.emit("invokestatic java/util/Collections/nCopies(ILjava/lang/Object;)Ljava/util/List;", pops = 2, pushes = 1)
        copyList()
    }

    fun call(receiver: Expression, name: String, arguments: List<Expression>) {
        val type = method.typeOf(receiver)
        when {
            type in setOf("Integer", "Real") -> numericCall(receiver, type, name, arguments)
            type == "Boolean" -> {
                method.expression(receiver)
                when (name) {
                    "And", "Or", "Xor" -> {
                        method.expression(arguments.single())
                        code.emit("i${name.lowercase()}", pops = 2, pushes = 1)
                    }
                    "Not" -> {
                        integer(1)
                        code.emit("ixor", pops = 2, pushes = 1)
                    }
                    "toInteger" -> Unit
                    else -> error("Unknown Boolean method $name")
                }
            }
            type.startsWith("Array[") || type.startsWith("List[") -> collectionCall(receiver, type, name, arguments)
            else -> error("Unknown built-in receiver $type")
        }
    }

    private fun numericCall(receiver: Expression, type: String, name: String, arguments: List<Expression>) {
        val operation = numericOperations[name]
        if (operation != null) {
            arithmetic(operation, receiver, arguments.single())
            return
        }
        method.expression(receiver)
        when (name) {
            "UnaryMinus" -> negate(type)
            "toInteger" -> code.emit("d2i", pops = 2, pushes = 1)
            "toReal" -> code.emit("i2d", pops = 1, pushes = 2)
            "toBoolean" -> comparison("ifne", 1)
            else -> error("Unknown numeric method $name")
        }
    }

    private fun collectionCall(receiver: Expression, type: String, name: String, arguments: List<Expression>) {
        method.expression(receiver)
        when (name) {
            "Length" -> length(type)
            "get" -> {
                method.expression(arguments.single())
                get(type)
            }
            "set" -> {
                val element = elementType(type)
                method.expression(arguments[0])
                method.expressionAs(arguments[1], element)
                code.emit("${elementPrefix(element)}astore", pops = 2 + slots(element))
            }
            "toList" -> arrayToList(type)
            "head" -> {
                integer(0)
                get(type)
            }
            "tail" -> {
                code.emit("dup", pops = 1, pushes = 2)
                length(type)
                integer(1)
                code.emit("swap", pops = 2, pushes = 2)
                code.emit("invokevirtual $LIST_CLASS/subList(II)Ljava/util/List;", pops = 3, pushes = 1)
                copyList()
            }
            "append" -> {
                code.emit("dup", pops = 1, pushes = 2)
                boxed(arguments.single())
                addElement()
            }
            else -> error("Unknown collection method $name")
        }
    }

    private fun length(collection: String) {
        val instruction = if (collection.startsWith("Array[")) "arraylength" else "invokevirtual $LIST_CLASS/size()I"
        code.emit(instruction, pops = 1, pushes = 1)
    }

    private fun get(collection: String) {
        val element = elementType(collection)
        if (collection.startsWith("Array[")) {
            code.emit("${elementPrefix(element)}aload", pops = 2, pushes = slots(element))
            return
        }
        code.emit("invokevirtual $LIST_CLASS/get(I)Ljava/lang/Object;", pops = 2, pushes = 1)
        val wrapper = boxedClass(element)
        if (wrapper != null) {
            val unbox = when (element) {
                "Integer" -> "intValue"
                "Boolean" -> "booleanValue"
                else -> "doubleValue"
            }
            code.emit("checkcast $wrapper", pops = 1, pushes = 1)
            code.emit("invokevirtual $wrapper/$unbox()${descriptor(element)}", pops = 1, pushes = slots(element))
        } else if (descriptor(element) != "Ljava/lang/Object;") {
            code.emit("checkcast ${referenceName(element)}", pops = 1, pushes = 1)
        }
    }

    private fun arrayToList(array: String) {
        val listSlot = code.local("List[Class]")
        newList()
        code.emit("invokespecial $LIST_CLASS/<init>()V", pops = 1)
        code.store("List[Class]", listSlot)
        forEachElement(array) {
            code.box(elementType(array))
            code.load("List[Class]", listSlot)
            code.emit("swap", pops = 2, pushes = 2)
            addElement()
            true
        }
        code.load("List[Class]", listSlot)
    }

    // The collection is already on the stack. Each iteration supplies one element to the body.
    fun forEachElement(collection: String, body: () -> Boolean) {
        val collectionSlot = code.local(collection)
        val index = code.local("Integer")
        code.store(collection, collectionSlot)
        integer(0)
        code.store("Integer", index)
        val start = code.label()
        val end = code.label()
        code.place(start)
        code.load("Integer", index)
        code.load(collection, collectionSlot)
        length(collection)
        code.wideBranch("if_icmplt", end, 2)
        code.load(collection, collectionSlot)
        code.load("Integer", index)
        get(collection)
        if (body()) {
            code.emit("iinc $index 1")
            code.jump(start)
        }
        code.place(end)
    }

    private fun newList() {
        code.emit("new $LIST_CLASS", pushes = 1)
        code.emit("dup", pops = 1, pushes = 2)
    }

    private fun copyList() {
        val source = code.local("Class")
        code.store("Class", source)
        newList()
        code.load("Class", source)
        code.emit("invokespecial $LIST_CLASS/<init>(Ljava/util/Collection;)V", pops = 2)
    }

    private fun addElement() {
        code.emit("invokevirtual $LIST_CLASS/add(Ljava/lang/Object;)Z", pops = 2, pushes = 1)
        code.discard("Boolean")
    }

    private fun boxed(value: Expression) {
        method.expression(value)
        code.box(method.typeOf(value))
    }

    private fun elementPrefix(type: String): String = if (type == "Boolean") "b" else instructionPrefix(type)

    private fun negate(type: String) = code.emit("${instructionPrefix(type)}neg", pops = slots(type), pushes = slots(type))

    private fun integer(value: Int) {
        val instruction = when (value) {
            -1 -> "iconst_m1"
            in 0..5 -> "iconst_$value"
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> "bipush $value"
            in Short.MIN_VALUE..Short.MAX_VALUE -> "sipush $value"
            else -> "ldc_w $value"
        }
        code.emit(instruction, pushes = 1)
    }

    private fun real(value: Double) = code.emit("ldc2_w $value", pushes = 2)

    private companion object {
        val numericOperations = mapOf(
            "Plus" to "add", "Minus" to "sub", "Mult" to "mul", "Div" to "div", "Rem" to "rem",
            "Less" to "lt", "LessEqual" to "le", "Greater" to "gt", "GreaterEqual" to "ge", "Equal" to "eq",
        )
    }
}
