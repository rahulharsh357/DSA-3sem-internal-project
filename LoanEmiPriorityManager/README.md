# Loan EMI Priority Manager (PS No. 65)

A menu-driven Java DSA project using PriorityQueue, Binary Search Tree, HashMap, sorting and file handling.

## Requirements
- Java JDK 11 or newer (no external libraries)

## Compile and run
Easiest: `./run.sh` (Linux/macOS) or `run.bat` (Windows). Or by hand, from this folder:

```bash
javac -d out src/*.java
java -cp out LoanEmiPriorityManager
```

The program creates `loan_applications.txt` in the folder you start it from. Start it from the same folder each time so your saved applications load again. (`run.sh` and `run.bat` also use the folder you launch them from.)

## Run the tests
```bash
./run.sh test        # or run.bat test
```
or by hand:
```bash
javac -d out src/*.java test/LoanTests.java
java -cp out LoanTests
```
The 83 checks need no libraries and the program exits with code 1 if any check fails. They cover EMI maths, affordability, input parsing, the priority queue, the BST, file loading and saving, the lock, and complete menu runs with scripted input.

## Menu
1. Submit Loan Application
2. Search Application by ID (type `L101` or just `101`)
3. Process Next Application (approves; rejects if EMI is above the limit)
4. Display All Applications
5. Display Loans by Amount (BST)
6. Generate Sorted Report
7. Save and Exit
8. Reject Application (pending applications only)
9. Change Affordability Limit

## Entering numbers
Plain numbers only: `250000`, `9.5`, `2,50,000`, `1,000,000`, `Rs. 5000`. Forms such as `100d`, `1e5`, `0x10`, `NaN` are rejected. Limits: amount up to 1,000,000,000,000; income up to 1,000,000,000; rate 0 to 1000 %; tenure 1 to 600 months; priority 1 to 10; up to 6 decimal places; names up to 60 characters.

## Priority, EMI and affordability
- Priority 1 (lowest) to 10 (highest). The priority queue processes the highest score first; equal priorities are processed FIFO.
- EMI uses the reducing-balance formula `EMI = P x r x (1+r)^n / ((1+r)^n - 1)`, with r = annual rate / 12 / 100, calculated with `log1p`/`expm1` so very small rates and long tenures stay accurate. At 0% interest, EMI = P / n.
- EMI-to-income percentage is shown for every application. If it is above the limit (default **50%**, change it with menu option 9), a warning is shown at submit and **Process Next** rejects the application instead of approving it. The decision uses the percentage exactly as displayed (2 decimals).

## Status
`PENDING` -> `APPROVED` or `REJECTED`.

## Data file
`loan_applications.txt`, one application per line:
`id|name|loanAmount|annualRate|tenureMonths|monthlyIncome|priority|status`
An optional first line `#maxEmiPercent=50.0` stores the affordability limit. Files from the older version (9 fields, status `PROCESSED`) are still read and are upgraded when saved.

## Data safety
- Saved after every submit, process, reject, limit change and exit; end of input (Ctrl+D, or Ctrl+Z then Enter on Windows) also saves.
- Saving writes a temporary file and then replaces the data file; the temporary file is always removed.
- If the data file cannot be read, the program stops and leaves the file untouched.
- If some rows are invalid or duplicated, they are skipped with a line-specific message and the original file is first copied to `loan_applications.txt.bak-<timestamp>`, so nothing is lost. IDs of skipped rows are never reused.
- A lock file (`loan_applications.txt.lock`) stops a second copy of the program from using the same data file at the same time.

## Files
- `src/LoanApplication.java` - record, validation limits, EMI maths, affordability
- `src/InputParser.java` - strict number parsing and name cleaning
- `src/LoanBST.java` - iterative BST keyed by loan amount, then ID
- `src/LoanFileStore.java` - validated load, backup, safe save, lock
- `src/LoanEmiPriorityManager.java` - menu and program flow (input and output can be replaced, which is how the tests run it)
- `test/LoanTests.java` - automated tests
- `run.sh`, `run.bat` - build and run helpers

## Limitations
- The BST is not self-balancing. Sorted input makes insertion O(n); the iterative code avoids stack overflow. An AVL tree would guarantee O(log n).
- Money is stored as `double`. This is fine for an academic project; production code should use `BigDecimal`.
- The whole file is rewritten on every change. This is fine for thousands of records, but not for very large data.
- Text-file storage, single user. The lock protects the file but there is no multi-user support.
- Alignment assumes normal-width characters. Names with wide characters (for example some emoji or East Asian text) can shift the table columns slightly. On consoles that are not UTF-8, non-ASCII characters in names may be replaced.
