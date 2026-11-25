@echo off
rem Usage: run.bat          compile and start the program
rem        run.bat test     compile and run the automated tests
setlocal
set "HERE=%~dp0"
if not exist "%HERE%out" mkdir "%HERE%out"
if /I "%~1"=="test" (
  javac -d "%HERE%out" "%HERE%src\*.java" "%HERE%test\LoanTests.java" || exit /b 1
  java -cp "%HERE%out" LoanTests
) else (
  javac -d "%HERE%out" "%HERE%src\*.java" || exit /b 1
  java -cp "%HERE%out" LoanEmiPriorityManager
)
endlocal
