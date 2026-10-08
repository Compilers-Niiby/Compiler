# The syntax analyzer

The syntax analyzer consumes the lexer's `List<Token>`, checks the program's structure, and returns a `Program` AST. The token list must end with exactly one `EOF` token. Parsing requires at least one class and consumes the complete input.

## Grammar source and scope

The implementation follows the recursive-descent approach from Lecture 3 and the AST structures from Lecture 4. The supplied lectures do not contain a formal O grammar or a detailed assessment rubric.

The existing lexer and `examples/demo.o` establish the keyword vocabulary, variable initialization syntax, constructors without parentheses, infix operators, and expression-bodied methods. Typed parameters, generics, full method bodies, member assignment, and `for ... in ... loop ... end` are explicit project grammar choices inferred from that vocabulary. They should be compared with the course's O specification if it becomes available. Square brackets represent generic type arguments; array indexing is outside this grammar.

## Implemented grammar

In this EBNF, brackets mean an optional part, braces mean repetition, and quoted text is a source token. `identifier`, `literal`, and `EOF` come from the lexer.

```ebnf
program       = classDecl, { classDecl }, EOF ;
classDecl     = "class", type, [ "extends", type ], "is",
                { variable | constructor | method }, "end" ;
type          = identifier, [ "[", type, { ",", type }, "]" ] ;
variable      = "var", identifier, ":", expression ;
parameters    = "(", [ parameter, { ",", parameter } ], ")" ;
parameter     = identifier, ":", type ;
constructor   = "this", [ parameters ], "is", body, "end" ;
method        = "method", identifier, [ parameters ], [ ":", type ],
                ( "is", body, "end" | "=>", expression ) ;
body          = { variable | statement } ;
statement     = assignment | whileStmt | ifStmt | forStmt
                | returnStmt | expression ;
assignment    = assignable, ":=", expression ;
whileStmt     = "while", expression, "loop", body, "end" ;
ifStmt        = "if", expression, "then", body,
                [ "else", body ], "end" ;
forStmt       = "for", identifier, "in", expression, "loop", body, "end" ;
returnStmt    = "return", [ expression ] ;

expression    = equality ;
equality      = comparison, { "==", comparison } ;
comparison    = addition, { ( "<" | ">" | "<=" | ">=" ), addition } ;
addition      = product, { ( "+" | "-" ), product } ;
product       = unary, { ( "*" | "/" ), unary } ;
unary         = ( "+" | "-" ), unary | postfix ;
postfix       = primary, { ".", identifier | arguments } ;
primary       = literal | type | "this" | "(", expression, ")" ;
arguments     = "(", [ expression, { ",", expression } ], ")" ;
```

An `assignable` expression is a name without generic arguments or a member access. Literal values, calls, binary expressions, grouped expressions, and `this` alone cannot be assignment targets. Whether a member's receiver or a name denotes a writable value is checked during semantic analysis.

The parser preserves an ambiguous name or call as written. For example, `Counter(0)` is a call node whose callee is the name `Counter`; resolving that name as a constructor is a later task.

## Parsing and AST construction

Each declaration and statement has a parsing function. Expression functions follow the precedence levels above. Addition, multiplication, comparison, and equality use loops, replacing left-recursive productions and preserving left associativity. For example, `a + b * c` produces addition with multiplication as its right child, and `a - b - c` produces `(a - b) - c`.

Calls and member access bind more tightly than unary operators. `2.Plus(3).Times(4)` is represented as a chain of member and call nodes. Parentheses produce a grouping node so their source span remains available.

The AST uses Kotlin data classes and sealed interfaces for declarations, statements, expressions, and method bodies. Fields and local variables share `VariableDeclaration`. A method has either a statement block or an expression body. Constructors always have a block. Later compiler stages can traverse these nodes.

Every node has a half-open `SourceSpan`: the start position is included and the end position is excluded. Declaration and control-flow spans include their closing `end`. Block spans cover their statements; an empty block has a zero-length span at its terminator. Literal nodes preserve their original text, including large integer values and real exponents.

## Statement boundaries and errors

The parser determines statement boundaries from tokens. Whitespace, including line breaks, and comments do not reach the parser. In particular, `return` followed by a newline and a name is parsed as a return with that name as its value. A bare return is unambiguous before `end`, `else`, or another token that cannot start an expression.

The parser stops at the first syntax error and throws `ParserException`. Missing keywords or delimiters, incomplete expressions, malformed lists, and input outside class declarations report the unexpected token's position. Invalid assignment targets report the start of the target expression. Unexpected end of input uses the lexer's EOF position.

The parser accepts syntactically valid programs even if names are unresolved, declarations are duplicated, or operands have incompatible types. Those checks belong to the next compiler stage.

## Demonstration and verification

```bash
make test
make parse
make parse FILE=examples/syntax.o
make ast FILE=examples/syntax.o OUT=results/syntax.ast
```

`examples/demo.o` checks compatibility with the lexer-stage example. `examples/syntax.o` demonstrates inheritance, parameters, block and expression method bodies, nested loops and branches, generic construction, and `for`.

Automated tests check AST structure, operator precedence and associativity, nested declarations and control flow, call chains, source spans, comments, complete-input validation, and diagnostics for malformed programs. The existing lexer tests remain part of the same Maven suite.

## Lecture references

Page numbers below are PDF page numbers, including pages without a printed slide number.

- `2026 CC BS3 01 Introduction.pdf`, pp. 13-14: syntax analyzer report and the second team report.
- `2026 CC BS3 03 Syntax Analysis.pdf`, pp. 6-8: token input and internal program representation; p. 12: separating syntax from declaration checks; pp. 25-28: precedence and recursive-descent functions; pp. 32-33: EBNF repetition and iterative binary tree construction.
- `2026 CC BS3 04 Compilation Structures.pdf`, pp. 8, 10, 12, 18-19: declaration attributes, source spans, expression trees, and AST class hierarchies.
- `lexer-presentation.pdf`, pp. 2-3, 5: the existing token API, vocabulary, and `2.Plus(3)` example.
