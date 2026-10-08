# Niiby O Compiler

A compiler construction course project written in Kotlin. The lexer converts O source code into tokens, and the syntax analyzer builds an abstract syntax tree (AST). Semantic analysis and Jasmin code generation are planned for later stages.

## Requirements

- JDK 25
- Maven 3.9 or newer

The project uses Kotlin 2.3.0 and targets Java 25. [Kotlin 2.3.0 supports Java 25 bytecode](https://kotlinlang.org/docs/whatsnew23.html#kotlin-jvm-support-for-java-25). To select an installed JDK 25 on macOS:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

Use JDK 25 as the project SDK and Maven runtime in IntelliJ IDEA. Maven downloads the Kotlin compiler and test dependencies during the first build.

## Quick start

```bash
make test
make run                         # Tokenize examples/demo.o
make parse                       # Parse examples/demo.o and print its AST
make parse FILE=examples/syntax.o # Parse the larger syntax example
make help
```

`examples/syntax.o` includes inheritance, constructors, methods, nested control flow, generic types, and the team's `for` extension.

## Command-line modes

The default mode prints tokens:

```bash
make run FILE=path/to/program.o
make tokens FILE=path/to/program.o OUT=results/program.tokens
make stdin
```

Parse a file and inspect or save its AST:

```bash
make parse FILE=path/to/program.o
make ast FILE=path/to/program.o OUT=results/program.ast
```

`FILE` defaults to `examples/demo.o`. The default output file is `tokens.txt` for `make tokens` and `ast.txt` for `make ast`.

The entry point accepts `[--tokens|--parse] [source-file]`. Without a file, it reads standard input:

```bash
printf 'class Empty is end\n' | mvn -q compile exec:java -Dexec.args="--parse"
mvn -q compile exec:java -Dexec.args="--help"
```

AST output includes each node's starting line and column:

```text
Program @ 1:1
  Class Empty @ 1:1
    Type Empty @ 1:7
```

The Kotlin API is `Parser(Lexer(source).tokenize()).parse()`. Every AST node retains its full source span, including offsets and end positions.

## Supported syntax

- Classes with optional inheritance and nested generic type arguments
- Fields and local variables initialized with `var name : expression`
- Constructors and methods with optional typed parameter lists
- Method bodies written as `is ... end` or `=> expression`
- Assignment, `while`, `if`/`else`, `return`, and `for name in expression loop ... end`
- Integer, real, and boolean literals; names; `this`; member access; chained calls; and generic constructor calls
- Arithmetic and comparison operators, unary `+`/`-`, and parentheses

The parser uses recursive descent. Operators are parsed in precedence order, and binary operators at the same level associate to the left. The lexer skips whitespace and comments, so newlines do not separate statements. An optional return value is consumed whenever the following token can start an expression.

The local lectures explain parsing and AST construction but do not provide a formal O language specification.

## Error handling

Lexical and syntax errors report a line and column and exit with status `1`. File-reading errors also exit with status `1`; invalid command-line arguments exit with status `2`.

```text
Lexical error: Unexpected character '@' at 2:17
```

Syntax diagnostics describe what was expected and identify the unexpected token or end of input. The parser stops at the first error. Name resolution, type checking, duplicate declarations, and return-type validation belong to semantic analysis.

## Repository structure

```text
examples/
  demo.o                   Original lexer example
  syntax.o                 Syntax analyzer example
src/main/kotlin/team/niiby/compiler/
  Main.kt                  Token and AST command-line modes
  lexer/                   Scanner, tokens, and source positions
  parser/
    Ast.kt                 AST data classes
    Parser.kt              Recursive-descent syntax analyzer
    ParserException.kt     Syntax diagnostics
    AstPrinter.kt          Readable AST outline
src/test/kotlin/team/niiby/compiler/
  lexer/                   Lexer tests
  parser/                  Parser and AST output tests
Makefile                   Build, test, and run commands
pom.xml                    Maven configuration
```

Lecture PDFs and the earlier lexer presentation remain local reference files and are ignored by Git.
