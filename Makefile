MAVEN := mvn
run_file_option := $(FILE)
FILE ?= examples/demo.o
OUT ?= tokens.txt

# GNU Make sees the positional filename as a second goal.
ifneq ($(filter runFile,$(MAKECMDGOALS)),)
run_file_goal := $(filter-out runFile,$(MAKECMDGOALS))
ifneq ($(run_file_goal),)
ifneq ($(words $(run_file_goal)),1)
$(error Specify one source file: make runFile path/to/program.o)
endif
ifeq ($(filter %.o,$(run_file_goal)),)
$(error Specify an O source filename: make runFile path/to/program.o)
endif
.PHONY: $(run_file_goal)
$(run_file_goal):
	@:
endif
endif

run_file = $(or $(run_file_goal),$(run_file_option))
shell_quote = '$(subst ','"'"',$(1))'

.PHONY: help test run runFile stdin tokens parse ast clean

help:
	@printf '%s\n' \
		'Niiby O compiler commands:' \
		'  make test                         Run all tests' \
		'  make run                          Tokenize examples/demo.o' \
		'  make run FILE=path/to/program.o   Tokenize another file' \
		'  make runFile examples/hello-sum.o   Compile and run a program with main' \
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

runFile:
	@source=$(call shell_quote,$(run_file)); \
	if [ -z "$$source" ]; then printf '%s\n' 'Usage: make runFile path/to/program.o' >&2; exit 2; fi; \
	if [ ! -f "$$source" ] && [ -f "examples/$$source" ]; then source="examples/$$source"; fi; \
	if [ ! -f "$$source" ]; then printf 'Source file not found: %s\n' "$$source" >&2; exit 2; fi; \
	exec $(MAVEN) -q compile exec:java "-Dexec.args=--run \"$$source\""

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

.PHONY: analyze optimize
analyze:
	$(MAVEN) -q compile exec:java -Dexec.args="--analyze $(FILE)"

optimize:
	$(MAVEN) -q compile exec:java -Dexec.args="--optimize $(FILE)"
