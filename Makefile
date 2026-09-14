MAVEN := mvn
FILE ?= examples/demo.o
OUT ?= tokens.txt

.PHONY: help test run stdin tokens clean

help:
	@printf '%s\n' \
		'Niiby O lexer commands:' \
		'  make test                         Run all tests' \
		'  make run                          Tokenize examples/demo.o' \
		'  make run FILE=path/to/program.o   Tokenize another file' \
		'  make stdin                        Read O code from standard input' \
		'  make tokens FILE=program.o        Save tokens to tokens.txt' \
		'  make tokens FILE=program.o OUT=result.tokens' \
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

clean:
	$(MAVEN) -q clean
