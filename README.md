# Niiby O Compiler

A compiler construction course project that translates the object-oriented O language to Jasmin assembly. The compiler is written in Kotlin and currently contains a complete handwritten lexer.

## Project status

- Lexer: implemented and tested
- Parser: not implemented yet
- Semantic analysis: not implemented yet
- Jasmin code generation: not implemented yet

## Requirements

- JDK 17 or newer
- Maven 3.9 or newer

Maven downloads the Kotlin compiler and test dependencies during the first build.

## Repository structure

```text
.
├── examples/
│   └── demo.o                         Example O program
├── src/
│   ├── main/kotlin/team/niiby/compiler/
│   │   ├── Main.kt                    Command-line entry point
│   │   └── lexer/
│   │       ├── Lexer.kt               Scanner implementation
│   │       ├── LexerException.kt      Lexical error type
│   │       ├── Token.kt               Token and source positions
│   │       └── TokenType.kt           Supported token types
│   └── test/kotlin/team/niiby/compiler/lexer/
│       └── LexerTest.kt               Automated lexer tests
├── docs/
│   └── lexer-presentation.pdf        Lexer presentation
├── pom.xml                            Maven build configuration
└── README.md
```

## Build and test

Run all automated tests:

```bash
mvn test
```

The current test suite contains 19 tests covering keywords, literals, operators, comments, source positions, maximal munch, complete class input, and lexical errors.

## Run the lexer

Tokenize the included example:

```bash
mvn -q compile exec:java -Dexec.args="examples/demo.o"
```

Tokenize another file:

```bash
mvn -q compile exec:java -Dexec.args="path/to/program.o"
```

To read source code from standard input, run the lexer without a file argument and finish the input with `Ctrl+D`:

```bash
mvn -q compile exec:java
```

The output contains the token type, source position, and original lexeme:

```text
TYPE                 POSITION           LEXEME
--------------------------------------------------------------
CLASS                1:1                class
IDENTIFIER           1:7                Counter
IS                   1:15               is
```

## Supported lexical elements

- O keywords such as `class`, `extends`, `var`, `method`, `while`, `if`, and `return`
- integer, real, and boolean literals
- identifiers, including identifiers with Unicode letters
- parentheses, brackets, commas, colons, and member-access dots
- `:=` and `=>`
- arithmetic and comparison operators: `+`, `-`, `*`, `/`, `<`, `>`, `<=`, `>=`, and `==`
- Team Niiby extension keywords: `for` and `in`
- line comments (`//`) and block comments (`/* ... */`)

The lexer applies maximal munch. For example, `<=` is emitted as one token. A dot becomes part of a real literal only when a digit follows it, so `2.Plus(3)` is emitted as an integer, a dot, and an identifier.

## Error handling

Unexpected characters, malformed numeric exponents, and unterminated block comments produce a `LexerException` with an exact line and column:

```text
Lexical error: Unexpected character '@' at 2:17
```

The lexer only validates lexical structure. Grammar validation belongs to the future parser.

## Presentation

- `docs/lexer-presentation.pdf`
