package team.niiby.compiler.generator

import java.lang.classfile.Opcode
import java.lang.classfile.TypeKind
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_boolean
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.ConstantDescs.MTD_void
import java.lang.constant.MethodTypeDesc
import team.niiby.compiler.lexer.TokenType
import team.niiby.compiler.lexer.decodeStringLiteral
import team.niiby.compiler.parser.Expression
import team.niiby.compiler.parser.GroupedExpression
import team.niiby.compiler.parser.LiteralExpression
import team.niiby.compiler.parser.UnaryExpression

private val LIST = ClassDesc.of("java.util.List")
private val COLLECTION = ClassDesc.of("java.util.Collection")
private val COLLECTIONS = ClassDesc.of("java.util.Collections")

private enum class NumericOperation {
    ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, EQUAL,
}

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
            TokenType.STRING_LITERAL -> code.ldc(code.constantPool().stringEntry(decodeStringLiteral(literal.lexeme)))
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
            TokenType.PLUS -> NumericOperation.ADD
            TokenType.MINUS -> NumericOperation.SUBTRACT
            TokenType.STAR -> NumericOperation.MULTIPLY
            TokenType.SLASH -> NumericOperation.DIVIDE
            TokenType.LESS -> NumericOperation.LESS
            TokenType.LESS_EQUAL -> NumericOperation.LESS_EQUAL
            TokenType.GREATER -> NumericOperation.GREATER
            TokenType.GREATER_EQUAL -> NumericOperation.GREATER_EQUAL
            TokenType.EQUAL_EQUAL -> NumericOperation.EQUAL
            else -> error("Unsupported operator $operator")
        },
        left, right,
    )

    private fun arithmetic(operation: NumericOperation, left: Expression, right: Expression) {
        val real = method.typeOf(left) == "Real" || method.typeOf(right) == "Real"
        for (operand in listOf(left, right)) {
            method.expression(operand)
            if (real && method.typeOf(operand) == "Integer") code.i2d()
        }
        when (operation) {
            NumericOperation.ADD -> if (real) code.dadd() else code.iadd()
            NumericOperation.SUBTRACT -> if (real) code.dsub() else code.isub()
            NumericOperation.MULTIPLY -> if (real) code.dmul() else code.imul()
            NumericOperation.DIVIDE -> if (real) code.ddiv() else code.idiv()
            NumericOperation.REMAINDER -> if (real) code.drem() else code.irem()
            NumericOperation.LESS -> compare(real, Opcode.IF_ICMPLT, Opcode.IFLT)
            NumericOperation.LESS_EQUAL -> compare(real, Opcode.IF_ICMPLE, Opcode.IFLE)
            NumericOperation.GREATER -> compare(real, Opcode.IF_ICMPGT, Opcode.IFGT)
            NumericOperation.GREATER_EQUAL -> compare(real, Opcode.IF_ICMPGE, Opcode.IFGE)
            NumericOperation.EQUAL -> compare(real, Opcode.IF_ICMPEQ, Opcode.IFEQ)
        }
    }

    private fun compare(real: Boolean, integerBranch: Opcode, realBranch: Opcode) {
        if (real) {
            // Select the comparison that leaves NaN outside this branch.
            if (realBranch == Opcode.IFLT || realBranch == Opcode.IFLE) code.dcmpg() else code.dcmpl()
            comparison(realBranch)
        } else comparison(integerBranch)
    }

    private fun comparison(branch: Opcode) {
        val yes = code.newLabel()
        val end = code.newLabel()
        code.branch(branch, yes)
        integer(0)
        code.goto_(end)
        code.labelBinding(yes)
        integer(1)
        code.labelBinding(end)
    }

    fun construct(type: String, arguments: List<Expression>) {
        when {
            type.startsWith("Array[") -> {
                val element = typeSymbol(elementType(type))
                method.expression(arguments.single())
                if (element.isPrimitive) code.newarray(TypeKind.from(element))
                else code.anewarray(element)
            }
            type.startsWith("List[") -> list(arguments)
            else -> {
                val value = arguments.single()
                method.expression(value)
                when {
                    type == "Real" && method.typeOf(value) == "Integer" -> code.i2d()
                    type == "Integer" && method.typeOf(value) == "Real" -> code.d2i()
                }
            }
        }
    }

    private fun list(arguments: List<Expression>) {
        when (arguments.size) {
            0 -> {
                newList()
                code.invokespecial(ARRAY_LIST, "<init>", MTD_void)
                return
            }
            1 -> {
                integer(1)
                boxed(arguments.single())
            }
            else -> {
                boxed(arguments[0])
                method.expression(arguments[1])
                code.swap()
            }
        }
        code.invokestatic(COLLECTIONS, "nCopies", MethodTypeDesc.of(LIST, CD_int, CD_Object))
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
                        when (name) {
                            "And" -> code.iand()
                            "Or" -> code.ior()
                            else -> code.ixor()
                        }
                    }
                    "Not" -> {
                        integer(1)
                        code.ixor()
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
            "toInteger" -> code.d2i()
            "toReal" -> code.i2d()
            "toBoolean" -> comparison(Opcode.IFNE)
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
                code.arrayStore(typeKind(element))
            }
            "toList" -> arrayToList(type)
            "head" -> {
                integer(0)
                get(type)
            }
            "tail" -> {
                code.dup()
                length(type)
                integer(1)
                code.swap()
                code.invokevirtual(ARRAY_LIST, "subList", MethodTypeDesc.of(LIST, CD_int, CD_int))
                copyList()
            }
            "append" -> {
                code.dup()
                boxed(arguments.single())
                addElement()
            }
            else -> error("Unknown collection method $name")
        }
    }

    fun length(collection: String) {
        if (collection.startsWith("Array[")) code.arraylength()
        else code.invokevirtual(ARRAY_LIST, "size", MethodTypeDesc.of(CD_int))
    }

    fun get(collection: String) {
        val element = elementType(collection)
        if (collection.startsWith("Array[")) {
            code.arrayLoad(typeKind(element))
            return
        }
        code.invokevirtual(ARRAY_LIST, "get", MethodTypeDesc.of(CD_Object, CD_int))
        val elementType = typeSymbol(element)
        val wrapper = boxedClass(element)
        if (wrapper != null) {
            val unbox = when (element) {
                "Integer" -> "intValue"
                "Boolean" -> "booleanValue"
                else -> "doubleValue"
            }
            code.checkcast(wrapper)
            code.invokevirtual(wrapper, unbox, MethodTypeDesc.of(elementType))
        } else if (elementType != CD_Object) {
            code.checkcast(elementType)
        }
    }

    private fun arrayToList(array: String) {
        val arraySlot = code.local(array)
        val listSlot = code.local("List[Class]")
        val index = code.local("Integer")
        code.store(array, arraySlot)
        newList()
        code.invokespecial(ARRAY_LIST, "<init>", MTD_void)
        code.store("List[Class]", listSlot)
        integer(0)
        code.store("Integer", index)
        val start = code.newBoundLabel()
        val end = code.newLabel()
        code.load("Integer", index)
        code.load(array, arraySlot)
        length(array)
        code.if_icmpge(end)
        code.load("List[Class]", listSlot)
        code.load(array, arraySlot)
        code.load("Integer", index)
        get(array)
        code.box(elementType(array))
        addElement()
        code.iinc(index, 1)
        code.goto_(start)
        code.labelBinding(end)
        code.load("List[Class]", listSlot)
    }

    private fun newList() {
        code.new_(ARRAY_LIST)
        code.dup()
    }

    private fun copyList() {
        val source = code.local("Class")
        code.store("Class", source)
        newList()
        code.load("Class", source)
        code.invokespecial(ARRAY_LIST, "<init>", MethodTypeDesc.of(CD_void, COLLECTION))
    }

    private fun addElement() {
        code.invokevirtual(ARRAY_LIST, "add", MethodTypeDesc.of(CD_boolean, CD_Object))
        code.pop()
    }

    private fun boxed(value: Expression) {
        method.expression(value)
        code.box(method.typeOf(value))
    }

    private fun negate(type: String) = if (type == "Real") code.dneg() else code.ineg()

    private fun integer(value: Int) = code.loadConstant(value)

    private fun real(value: Double) = code.loadConstant(value)

    private companion object {
        val numericOperations = mapOf(
            "Plus" to NumericOperation.ADD,
            "Minus" to NumericOperation.SUBTRACT,
            "Mult" to NumericOperation.MULTIPLY,
            "Div" to NumericOperation.DIVIDE,
            "Rem" to NumericOperation.REMAINDER,
            "Less" to NumericOperation.LESS,
            "LessEqual" to NumericOperation.LESS_EQUAL,
            "Greater" to NumericOperation.GREATER,
            "GreaterEqual" to NumericOperation.GREATER_EQUAL,
            "Equal" to NumericOperation.EQUAL,
        )
    }
}
