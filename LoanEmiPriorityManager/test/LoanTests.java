import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Dependency-free tests. Run: java -cp out LoanTests  (exit code 1 if any check fails). */
public class LoanTests {
    private static int passed = 0, failed = 0;

    private static LoanApplication app(int idNum, double amount, double rate, int months,
                                       double income, int priority, LoanApplication.Status status) {
        return new LoanApplication("L" + idNum, "Name " + idNum, amount, rate, months, income, priority, status);
    }

    private static void check(String name, boolean condition) {
        if (condition) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) throws Exception {
        emiTests();
        affordabilityTests();
        parserTests();
        displayTests();
        priorityQueueTests();
        bstTests();
        fileStoreTests();
        lockTests();
        managerFlowTests();
        dataSafetyTests();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---------- EMI ----------
    static void emiTests() {
        check("EMI at 0% = amount / months",
                Math.abs(app(1, 120000, 0, 12, 30000, 3, LoanApplication.Status.PENDING).calculateEmi() - 10000.0) < 1e-9);
        LoanApplication a = app(2, 500000, 9.5, 60, 40000, 5, LoanApplication.Status.PENDING);
        check("EMI known value (500000, 9.5%, 60m) = 10500.93", Math.abs(a.calculateEmi() - 10500.93) < 0.01);
        check("EMI/Income percent = 26.25", Math.abs(a.getEmiToIncomePercent() - 26.25) < 0.01);

        // Bug: a tiny rate used to give Infinity (1 + r rounded to 1, division by zero)
        LoanApplication tiny = app(3, 1000, 1e-20, 12, 5000, 5, LoanApplication.Status.PENDING);
        check("BUG FIX: tiny rate gives a finite EMI close to amount/months",
                Double.isFinite(tiny.calculateEmi()) && Math.abs(tiny.calculateEmi() - 1000.0 / 12) < 1e-6);

        LoanApplication extreme = app(4, 1e12, 1000, 600, 1e9, 5, LoanApplication.Status.PENDING);
        check("Extreme but allowed inputs give a finite EMI", Double.isFinite(extreme.calculateEmi()));
        LoanApplication tinyAmount = app(5, 0.01, 1000, 1, 1, 5, LoanApplication.Status.PENDING);
        check("Smallest amount gives a finite EMI", Double.isFinite(tinyAmount.calculateEmi()));
    }

    static void affordabilityTests() {
        LoanApplication ok = app(1, 500000, 9.5, 60, 40000, 5, LoanApplication.Status.PENDING);
        check("Affordable at 26% with the 50% limit", ok.isAffordable(50));
        check("Not affordable when the limit is lowered to 20%", !ok.isAffordable(20));
        LoanApplication bad = app(2, 250000, 8, 24, 1000, 5, LoanApplication.Status.PENDING);
        check("Not affordable when EMI > 50% of income", !bad.isAffordable(50));

        // 100 / 199.99 = 50.0025% -> shown as 50.00 -> must be approved at limit 50
        LoanApplication edge = app(3, 1000, 0, 10, 199.99, 5, LoanApplication.Status.PENDING);
        String shown = String.format("%.2f", edge.getEmiToIncomePercent());
        check("BUG FIX: decision matches the displayed percentage (" + shown + ")",
                shown.equals("50.00") && edge.isAffordable(50));
    }

    // ---------- parsing ----------
    static void parserTests() {
        check("Parser: plain number", InputParser.parseDecimal("5000") == 5000.0);
        check("Parser: decimal", InputParser.parseDecimal("9.5") == 9.5);
        check("Parser: Indian grouping 1,00,000", InputParser.parseDecimal("1,00,000") == 100000.0);
        check("Parser: western grouping and currency", InputParser.parseDecimal("Rs. 1,000,000") == 1000000.0);
        check("Parser: percent sign allowed", InputParser.parseDecimal("9.5%") == 9.5);
        for (String bad : new String[]{"100d", "1e5", "0x1p3", "NaN", "Infinity", "-5", "", "abc", "1.2.3", "1e300"}) {
            boolean rejected;
            try { InputParser.parseDecimal(bad); rejected = false; } catch (NumberFormatException ex) { rejected = true; }
            check("BUG FIX: parser rejects \"" + bad + "\"", rejected);
        }
        boolean wholeRejected;
        try { InputParser.parseWholeNumber("12.5"); wholeRejected = false; } catch (NumberFormatException ex) { wholeRejected = true; }
        check("Parser: whole number rejects 12.5", wholeRejected && InputParser.parseWholeNumber(" 12 ") == 12);
        check("Name cleaning removes pipes, tabs, extra spaces", InputParser.cleanName("  a|b\tc   d ").equals("a b c d"));
        check("Name cleaning limits length", InputParser.cleanName("x".repeat(200)).length() == LoanApplication.MAX_NAME_LENGTH);
        check("ID normalising: 101 -> L101, l7 -> L7", InputParser.normalizeId("101").equals("L101")
                && InputParser.normalizeId(" l7 ").equals("L7"));
    }

    static void displayTests() {
        String emoji = "😀".repeat(30); // 30 emoji, each is 2 UTF-16 units
        LoanApplication a = new LoanApplication("L1", emoji, 1000, 5, 12, 5000, 3, LoanApplication.Status.PENDING);
        String row = a.toString();
        boolean loneSurrogate = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (Character.isHighSurrogate(c) && (i + 1 >= row.length() || !Character.isLowSurrogate(row.charAt(i + 1)))) loneSurrogate = true;
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(row.charAt(i - 1)))) loneSurrogate = true;
        }
        check("BUG FIX: long emoji names are shortened without splitting characters", !loneSurrogate && row.contains("..."));
        LoanApplication big = app(2, 1e12, 5, 12, 1e9, 5, LoanApplication.Status.PENDING);
        check("Table header and rows have equal width at the maximum amount",
                big.toString().length() == LoanApplication.header().length());
    }

    // ---------- data structures ----------
    static void priorityQueueTests() {
        PriorityQueue<LoanApplication> pq = new PriorityQueue<>(
                Comparator.comparingInt(LoanApplication::getPriority).reversed()
                        .thenComparingLong(LoanApplication::getSequence));
        int[] priorities = {2, 5, 3, 5};
        for (int i = 0; i < priorities.length; i++) pq.offer(app(101 + i, 1000, 5, 12, 5000, priorities[i], LoanApplication.Status.PENDING));
        // priorities by ID: L101=2, L102=5, L103=3, L104=5
        check("Priority queue: highest priority first (L102, earliest of the two 5s)", pq.poll().getApplicationId().equals("L102"));
        check("Priority queue: equal priority is FIFO (L104 next)", pq.poll().getApplicationId().equals("L104"));
        check("Priority queue: then priority 3 (L103)", pq.poll().getApplicationId().equals("L103"));
        check("Priority queue: lowest last (L101)", pq.poll().getApplicationId().equals("L101"));
    }

    static void bstTests() {
        LoanBST bst = new LoanBST();
        double[] amounts = {500, 100, 300, 300, 900, 50};
        for (int i = 0; i < amounts.length; i++) bst.insert(app(101 + i, amounts[i], 5, 12, 5000, 1, LoanApplication.Status.PENDING));
        List<LoanApplication> sorted = bst.inOrder();
        boolean ordered = true;
        for (int i = 1; i < sorted.size(); i++) if (sorted.get(i - 1).getLoanAmount() > sorted.get(i).getLoanAmount()) ordered = false;
        check("BST in-order is ascending by amount", ordered);
        check("BST keeps equal amounts (6 nodes)", sorted.size() == 6);
        check("BST ties broken by ID", sorted.get(2).getApplicationId().equals("L103") && sorted.get(3).getApplicationId().equals("L104"));
        LoanBST degenerate = new LoanBST();
        for (int i = 1; i <= 50000; i++) degenerate.insert(app(100000 + i, i, 5, 12, 5000, 1, LoanApplication.Status.PENDING));
        check("Sorted 50,000-node BST does not overflow the stack", degenerate.inOrder().size() == 50000);
    }

    // ---------- file store ----------
    static void fileStoreTests() throws Exception {
        Path dir = Files.createTempDirectory("loan-test");
        Path file = dir.resolve("data.txt");
        LoanFileStore store = new LoanFileStore(file);
        List<LoanApplication> original = new ArrayList<>();
        original.add(app(101, 100000, 0, 12, 30000, 3, LoanApplication.Status.PENDING));
        original.add(app(102, 500000, 9.5, 60, 40000, 5, LoanApplication.Status.APPROVED));
        original.add(app(103, 250000, 8, 24, 90000, 7, LoanApplication.Status.REJECTED));
        store.save(original, 42.5);

        LoanFileStore.LoadResult loaded = store.load();
        check("Round trip keeps all records", loaded.applications.size() == 3);
        check("Round trip keeps status and amount", loaded.applications.get(1).getStatus() == LoanApplication.Status.APPROVED
                && loaded.applications.get(1).getLoanAmount() == 500000.0);
        check("Round trip keeps the affordability limit", loaded.maxEmiPercent != null && loaded.maxEmiPercent == 42.5);
        check("No leftover .tmp file", !Files.exists(dir.resolve("data.txt.tmp")));

        Files.write(file, List.of(
                "L101|Good|1000.0|5.0|12|5000.0|3|PENDING",
                "garbage line",
                "L102|BadPriority|1000.0|5.0|12|5000.0|99|PENDING",
                "L103|101|Legacy|1000.0|5.0|12|5000.0|3|PROCESSED".replace("L103|101|", "L103|103|"),
                "L104|BadStatus|1000.0|5.0|12|5000.0|3|WHATEVER",
                "L105|Huge|1e300|5.0|12|5000.0|3|PENDING",
                "L106|NaN guy|NaN|5.0|12|5000.0|3|PENDING",
                "#maxEmiPercent=abc",
                ""));
        LoanFileStore.LoadResult mixed = store.load();
        check("Invalid rows skipped, valid rows kept (2 valid)", mixed.applications.size() == 2);
        check("Skipped rows are counted (5)", mixed.skippedRows == 5);
        check("Highest ID seen includes skipped rows", mixed.maxIdSeen == 106);
        check("Legacy 9-field row with PROCESSED loads as APPROVED", mixed.applications.get(1).getStatus() == LoanApplication.Status.APPROVED);
        check("Bad limit line produces a warning and keeps the default", mixed.maxEmiPercent == null
                && mixed.warnings.stream().anyMatch(w -> w.contains("invalid limit")));
        Path bak = store.backup();
        check("backup() copies the file", Files.exists(bak) && Files.readAllLines(bak).size() == Files.readAllLines(file).size());
    }

    static void lockTests() throws Exception {
        Path dir = Files.createTempDirectory("loan-lock");
        LoanFileStore first = new LoanFileStore(dir.resolve("data.txt"));
        LoanFileStore second = new LoanFileStore(dir.resolve("data.txt"));
        check("Lock: first instance gets the lock", first.tryLock());
        check("BUG FIX: second instance is refused", !second.tryLock());
        first.close();
        check("Lock: released after close, lock file removed", second.tryLock() && true);
        second.close();
        check("Lock file is deleted on close", !Files.exists(dir.resolve("data.txt.lock")));
    }

    // ---------- whole-program flows with scripted input ----------
    private static String sub(String name, String amount, String rate, String months, String income, String priority) {
        return "1\n" + name + "\n" + amount + "\n" + rate + "\n" + months + "\n" + income + "\n" + priority + "\n";
    }

    private static String run(Path dataFile, String input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
             LoanFileStore store = new LoanFileStore(dataFile)) {
            new LoanEmiPriorityManager(store,
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out).run();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static Path newFile() throws Exception { return Files.createTempDirectory("loan-flow").resolve("data.txt"); }

    static void managerFlowTests() throws Exception {
        // Submit three, process all: priority order and approve/reject decisions
        Path f = newFile();
        String out = run(f, sub("Aman", "100000", "0", "12", "30000", "3")
                + sub("Riya", "500000", "9.5", "60", "40000", "5")
                + sub("Zed|X", "250000", "8", "24", "1000", "9")
                + "3\n3\n3\n3\n4\n7\n");
        int zed = out.indexOf("REJECTED L103"), riya = out.indexOf("APPROVED L102"), aman = out.indexOf("APPROVED L101");
        check("Flow: processed in priority order (9, then 5, then 3)", zed >= 0 && riya > zed && aman > riya);
        check("Flow: warning shown at submit for unaffordable loan", out.contains("WARNING: EMI is above 50%"));
        check("Flow: empty queue message", out.contains("No pending applications to process."));
        check("Flow: no Infinity or NaN anywhere", !out.contains("Infinity") && !out.contains("NaN"));
        check("BUG FIX: choosing 7 saves once and does not print the 'Input closed' message",
                out.contains("Data saved.") && !out.contains("Input closed"));
        List<String> lines = Files.readAllLines(f);
        check("Flow: file saved with limit header and 8-field rows", lines.get(0).equals("#maxEmiPercent=50.0")
                && lines.get(1).equals("L101|Aman|100000.0|0.0|12|30000.0|3|APPROVED") && lines.size() == 4);
        check("Flow: pipe in name was replaced", lines.get(3).startsWith("L103|Zed X|"));

        // Restart: data reloads, IDs continue, search by number works
        String out2 = run(f, "2\n102\n" + sub("Next", "1000", "5", "12", "9000", "2") + "7\n");
        check("Restart: records reloaded", out2.contains("Loaded 3 application(s)."));
        check("Restart: search accepts 102", out2.contains("L102") && out2.contains("APPROVED"));
        check("Restart: next ID continues at L104", out2.contains("Application received: L104"));

        // Smallest typed rate (6 decimals) and a rate typed with too many decimals
        Path f2 = newFile();
        String out3 = run(f2, sub("Tiny", "1000", "0.000001", "12", "5000", "5") + "3\n7\n");
        check("Tiny typed rate shows a normal EMI and approves",
                !out3.contains("Infinity") && out3.contains("Estimated monthly EMI: 83.33") && out3.contains("APPROVED L101"));
        String out3b = run(newFile(), sub("Tiny", "1000", "0.00000000000000000001\n0", "12", "5000", "5") + "7\n");
        check("Rate with more than 6 decimals is rejected with a message", out3b.contains("Enter a plain number"));
        Path tinyFile = newFile();
        Files.write(tinyFile, List.of("L101|FromFile|1000.0|1.0E-20|12|5000.0|5|PENDING"));
        String out3c = run(tinyFile, "3\n7\n");
        check("BUG FIX: tiny rate loaded from a file is approved, not Infinity",
                !out3c.contains("Infinity") && out3c.contains("APPROVED L101"));

        // Bug: huge amount, 100d, hex, commas
        Path f3 = newFile();
        String out4 = run(f3, sub("Odd", "1e300\n100d\n0x10\n1,00,000", "5", "12", "50000", "5") + "7\n");
        check("BUG FIX: 1e300, 100d and 0x10 are rejected with a message", out4.split("Enter a plain number", -1).length - 1 >= 3);
        check("Commas accepted: amount saved as 100000.0", Files.readAllLines(f3).get(1).contains("|100000.0|"));

        // Range checks re-prompt
        Path f4 = newFile();
        String out5 = run(f4, sub("Range", "5000", "5", "0\n601\n12", "5000", "11\n0\n4") + "7\n");
        check("Tenure and priority out of range re-prompt", out5.contains("Enter a whole number from 1 to 600")
                && out5.contains("Enter a whole number from 1 to 10") && Files.readAllLines(f4).get(1).endsWith("|12|5000.0|4|PENDING"));

        // Reject flow and not-found
        Path f5 = newFile();
        String out6 = run(f5, sub("A", "1000", "5", "12", "9000", "2") + "8\nL101\n8\nL101\n8\nL999\n3\n7\n");
        check("Reject: pending can be rejected, then only pending message, then not found",
                out6.contains("Application L101 rejected.") && out6.contains("Only pending applications can be rejected.")
                        && out6.contains("Application L999 not found.") && out6.contains("No pending applications to process."));

        // Changing the limit
        Path f6 = newFile();
        String out7 = run(f6, sub("Lim", "500000", "9.5", "60", "40000", "5") + "9\n20\n3\n7\n");
        check("Limit change: 26% loan rejected at 20% limit", out7.contains("Limit set to 20%.") && out7.contains("REJECTED L101"));
        check("Limit change is saved to the file", Files.readAllLines(f6).get(0).equals("#maxEmiPercent=20.0"));
        String out8 = run(f6, "7\n");
        check("Limit is reloaded after restart", out8.contains("rejects if EMI > 20% of income"));

        // End of input in the middle of entering an application
        Path f7 = newFile();
        String out9 = run(f7, sub("Done", "1000", "5", "12", "9000", "2") + "1\nHalf\n500\n");
        check("End of input mid-entry saves earlier data and stops cleanly",
                out9.contains("Input closed. Saving current data...") && Files.readAllLines(f7).size() == 2);

        // Invalid menu input
        String out10 = run(newFile(), "abc\n99\n-1\n7\n");
        check("Invalid menu input handled", out10.contains("Please enter a valid menu number.") && out10.contains("Invalid choice. Select 1 to 9."));
    }

    static void dataSafetyTests() throws Exception {
        // BUG FIX: unreadable file must NOT be overwritten
        Path f = newFile();
        byte[] original = "L101|Café|1000.0|5.0|12|5000.0|3|PENDING\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(f, original);
        String out = run(f, sub("New", "500", "5", "12", "5000", "3") + "7\n");
        check("BUG FIX: unreadable file -> program stops with a clear message", out.contains("Could not read") && out.contains("left untouched"));
        check("BUG FIX: unreadable file is byte-for-byte unchanged", java.util.Arrays.equals(Files.readAllBytes(f), original));

        // BUG FIX: skipped rows are backed up and IDs are not reused
        Path g = newFile();
        Files.write(g, List.of("L101|Good|1000.0|5.0|12|5000.0|3|PENDING",
                "L102|Typo|1000.0|5.0|12|5000.0|99|PENDING"));
        String out2 = run(g, sub("New", "500", "5", "12", "5000", "3") + "7\n");
        List<Path> backups;
        try (Stream<Path> s = Files.list(g.getParent())) {
            backups = s.filter(p -> p.getFileName().toString().contains(".bak-")).collect(Collectors.toList());
        }
        check("BUG FIX: a backup is created when rows are skipped", backups.size() == 1 && out2.contains("backed up"));
        check("BUG FIX: backup still contains the bad row", Files.readAllLines(backups.get(0)).stream().anyMatch(l -> l.startsWith("L102|Typo")));
        check("BUG FIX: new application does not reuse the skipped ID L102", out2.contains("Application received: L103"));

        // Duplicate IDs are skipped and backed up
        Path h = newFile();
        Files.write(h, List.of("L101|One|1000.0|5.0|12|5000.0|3|PENDING", "L101|Two|2000.0|5.0|12|5000.0|3|PENDING"));
        String out3 = run(h, "7\n");
        check("Duplicate ID skipped with a message and a backup", out3.contains("Skipping duplicate application ID: L101") && out3.contains("backed up"));

        // Legacy file (9 fields, PROCESSED) is upgraded on save
        Path k = newFile();
        Files.write(k, List.of("L101|101|Old|1000.0|5.0|12|5000.0|3|PROCESSED"));
        String out4 = run(k, "7\n");
        check("Legacy file loads without any skipped rows", out4.contains("Loaded 1 application(s).") && !out4.contains("backed up"));
        check("Legacy file is rewritten in the new 8-field format", Files.readAllLines(k).get(1).equals("L101|Old|1000.0|5.0|12|5000.0|3|APPROVED"));
    }
}
