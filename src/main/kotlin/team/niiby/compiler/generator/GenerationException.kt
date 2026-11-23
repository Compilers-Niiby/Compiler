package team.niiby.compiler.generator

import team.niiby.compiler.lexer.SourcePosition

class GenerationException(message: String, position: SourcePosition) :
    RuntimeException("$message at ${position.line}:${position.column}")
