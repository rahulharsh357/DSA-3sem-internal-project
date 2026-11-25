#!/usr/bin/env bash
# Usage: ./run.sh          compile and start the program
#        ./run.sh test     compile and run the automated tests
# The data file loan_applications.txt is created in the folder you run this from.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/out"
if [ "$1" = "test" ]; then
  javac -d "$HERE/out" "$HERE"/src/*.java "$HERE/test/LoanTests.java"
  java -cp "$HERE/out" LoanTests
else
  javac -d "$HERE/out" "$HERE"/src/*.java
  java -cp "$HERE/out" LoanEmiPriorityManager
fi
