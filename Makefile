MVN ?= mvn

.PHONY: build test itest run clean

build:
	$(MVN) -q verify -DskipITs

test:
	$(MVN) test

# needs docker running
itest:
	$(MVN) verify -Pitest

run:
	$(MVN) -q install -DskipTests
	$(MVN) -pl example-payments-api spring-boot:run

clean:
	$(MVN) -q clean
