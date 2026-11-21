#!/usr/bin/env bash
# Runs the plain-Java unit tests for the age rules.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/test && mkdir -p build/test
javac -nowarn -encoding UTF-8 -d build/test src/nl/screenshotopruimer/AgeRules.java test/nl/screenshotopruimer/AgeRulesTest.java
java -cp build/test nl.screenshotopruimer.AgeRulesTest
