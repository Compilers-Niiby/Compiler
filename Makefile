MAVEN := mvn
FILE ?= examples/demo.o
OUT ?= tokens.txt

.PHONY: help test run stdin tokens parse ast clean

help:
	@printf '%s\n' \
		'Niiby O compiler commands:' \
		'  make test                         Run all tests' \
		'  make run                          Tokenize examples/demo.o' \
		'  make run FILE=path/to/program.o   Tokenize another file' \
		'  make stdin                        Read O code from standard input' \
		'  make tokens FILE=program.o        Save tokens to tokens.txt' \
		'  make tokens FILE=program.o OUT=result.tokens' \
		'  make parse FILE=program.o         Parse and print the AST' \
		'  make ast FILE=program.o OUT=result.ast  Save the AST' \
		'  make clean                        Remove build files'

test:
	$(MAVEN) test

run:
	$(MAVEN) -q compile exec:java -Dexec.args="$(FILE)"

stdin:
	$(MAVEN) -q compile exec:java

tokens:
	@mkdir -p "$(dir $(OUT))"
	$(MAVEN) -q compile exec:java -Dexec.args="$(FILE)" > "$(OUT)"
	@printf 'Tokens saved to %s\n' "$(OUT)"

parse:
	$(MAVEN) -q compile exec:java -Dexec.args="--parse $(FILE)"

ast: OUT = ast.txt
ast:
	@mkdir -p "$(dir $(OUT))"
	$(MAVEN) -q compile exec:java -Dexec.args="--parse $(FILE)" > "$(OUT)"
	@printf 'AST saved to %s\n' "$(OUT)"

clean:
	$(MAVEN) -q clean
