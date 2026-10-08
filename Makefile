# Portfolio Management module - build, run and test.
#
#   make            show the available targets
#   make build      compile and package the runnable CLI jar (skips tests)
#   make run        start the interactive CLI
#   make test       run the unit tests
#   make coverage   run the tests, enforce the coverage gate and write the HTML report

MVN      ?= mvn
JAR      := target/portfolio-cli.jar
SOURCES  := $(shell find src/main -type f 2>/dev/null)

# The project targets Java 17. Use a local JDK 17 when one is installed (macOS), otherwise
# fall back to whatever JAVA_HOME / PATH provide.
ifeq ($(origin JAVA_HOME),undefined)
  JDK17 := $(shell /usr/libexec/java_home -v 17 2>/dev/null)
  ifneq ($(JDK17),)
    export JAVA_HOME := $(JDK17)
  endif
endif
JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)

.DEFAULT_GOAL := help
.PHONY: help build compile run test coverage clean

help:
	@echo "Targets:"
	@echo "  build     compile and package $(JAR) (tests skipped)"
	@echo "  compile   compile the sources only"
	@echo "  run       build if needed, then start the interactive CLI"
	@echo "  test      run the unit tests (JUnit 5 + Mockito)"
	@echo "  coverage  run tests + JaCoCo; fails if Portfolio coverage is below 80%"
	@echo "            report: target/site/jacoco/index.html"
	@echo "  clean     remove build output and logs"

compile:
	$(MVN) -q compile

build: $(JAR)

$(JAR): pom.xml $(SOURCES)
	$(MVN) -q -DskipTests package

run: $(JAR)
	$(JAVA) -jar $(JAR)

test:
	$(MVN) test

coverage:
	$(MVN) verify
	@echo "Coverage report: target/site/jacoco/index.html"

clean:
	$(MVN) -q clean
	rm -rf logs
