import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Scanner;

public class LoanEmiPriorityManager {
    private static final String DATA_FILE = "loan_applications.txt";
    private static final long FIRST_ID = 101L;
    private static final long LAST_ID = 999_999_999_999_999_999L; // 18 digits, matches the ID format

    /** Thrown when input is closed (Ctrl+D / Ctrl+Z) so the program can save and stop. */
    private static class InputEndedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        InputEndedException() { super("input closed"); }
    }

    // Higher priority first; equal priority is processed in submission order (FIFO).
    private final PriorityQueue<LoanApplication> priorityQueue = new PriorityQueue<>(
            Comparator.comparingInt(LoanApplication::getPriority).reversed()
                    .thenComparingLong(LoanApplication::getSequence));
    private final Map<String, LoanApplication> applicationMap = new HashMap<>();
    private final List<LoanApplication> allApplications = new ArrayList<>();
    private final LoanBST loanBST = new LoanBST();
    private final LoanFileStore fileStore;
    private final Scanner sc;
    private final PrintStream out;
    private long nextId = FIRST_ID;
    private double maxEmiPercent = LoanApplication.DEFAULT_MAX_EMI_PERCENT;

    public LoanEmiPriorityManager(LoanFileStore fileStore, InputStream in, PrintStream out) {
        this.fileStore = fileStore;
        this.sc = new Scanner(in);
        this.out = out;
    }

    public static void main(String[] args) {
        try (LoanFileStore store = new LoanFileStore(Path.of(DATA_FILE))) {
            if (!store.tryLock()) {
                System.out.println("Another copy of this program is already using " + store.getDataFile()
                        + ". Close it first, or run from a different folder.");
                System.exit(1);
            }
            new LoanEmiPriorityManager(store, System.in, System.out).run();
        } catch (IOException ex) {
            System.out.println("Could not start: " + ex.getMessage());
            System.exit(1);
        }
    }

    public void run() {
        if (!loadData()) return; // never continue (and overwrite the file) after a failed load
        boolean running = true;
        try {
            while (running) {
                printMenu();
                int choice = parseMenuChoice(readLine(""));
                switch (choice) {
                    case 1: submitApplication(); break;
                    case 2: searchApplication(); break;
                    case 3: processNext(); break;
                    case 4: displayAll(); break;
                    case 5: displayBstOrder(); break;
                    case 6: generateSortedReport(); break;
                    case 7:
                        saveData();
                        out.println("Data saved. Thank you for using Loan EMI Priority Manager.");
                        running = false;
                        break;
                    case 8: rejectApplication(); break;
                    case 9: changeLimit(); break;
                    case -1: out.println("Please enter a valid menu number."); break;
                    default: out.println("Invalid choice. Select 1 to 9.");
                }
            }
        } catch (InputEndedException ex) {
            out.println("\nInput closed. Saving current data...");
            saveData();
        }
    }

    private int parseMenuChoice(String text) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException ex) { return -1; }
    }

    private void printMenu() {
        out.println("\n========== LOAN EMI PRIORITY MANAGER ==========");
        out.println("1. Submit Loan Application");
        out.println("2. Search Application by ID");
        out.printf(Locale.US, "3. Process Next Application (approves; rejects if EMI > %s%% of income)%n",
                formatPercent(maxEmiPercent));
        out.println("4. Display All Applications");
        out.println("5. Display Loans by Amount (BST)");
        out.println("6. Generate Sorted Report");
        out.println("7. Save and Exit");
        out.println("8. Reject Application");
        out.println("9. Change Affordability Limit");
        out.print("Enter your choice: ");
    }

    private void submitApplication() {
        String name = InputParser.cleanName(readLine("Enter applicant name: "));
        if (name.isEmpty()) { out.println("Name cannot be empty."); return; }
        double amount = readDecimal("Enter loan amount (1 to 1,000,000,000,000): ", 0.01, LoanApplication.MAX_LOAN_AMOUNT);
        double rate = readDecimal("Enter annual interest rate in % (0 to 1000): ", 0, LoanApplication.MAX_ANNUAL_RATE);
        int months = readWhole("Enter loan tenure in months (1-600): ",
                LoanApplication.MIN_TENURE_MONTHS, LoanApplication.MAX_TENURE_MONTHS);
        double income = readDecimal("Enter monthly income (1 to 1,000,000,000): ", 0.01, LoanApplication.MAX_MONTHLY_INCOME);
        out.println("Priority scale: 1 = lowest urgency, 10 = highest urgency.");
        int priority = readWhole("Enter priority (1-10): ", LoanApplication.MIN_PRIORITY, LoanApplication.MAX_PRIORITY);
        if (nextId > LAST_ID) { out.println("Cannot create more IDs: ID limit reached."); return; }

        LoanApplication app = new LoanApplication("L" + nextId, name, amount, rate, months, income,
                priority, LoanApplication.Status.PENDING);
        addApplication(app);
        out.println("Application received: " + app.getApplicationId() + " | " + name + " | " + formatMoney(amount));
        out.printf(Locale.US, "Estimated monthly EMI: %.2f | EMI/Income: %.2f%%%n",
                app.calculateEmi(), app.getEmiToIncomePercent());
        if (!app.isAffordable(maxEmiPercent)) {
            out.printf(Locale.US,
                    "WARNING: EMI is above %s%% of income. This application will be rejected when processed.%n",
                    formatPercent(maxEmiPercent));
        }
        saveData();
    }

    private void addApplication(LoanApplication app) {
        applicationMap.put(app.getApplicationId(), app);
        allApplications.add(app);
        loanBST.insert(app);
        if (app.getStatus() == LoanApplication.Status.PENDING) priorityQueue.offer(app);
        if (app.getSequence() >= nextId) nextId = app.getSequence() + 1;
    }

    private void searchApplication() {
        String id = InputParser.normalizeId(readLine("Enter application ID (e.g. L101 or 101): "));
        LoanApplication app = applicationMap.get(id);
        if (app == null) out.println("Application " + id + " not found.");
        else { printHeading(); out.println(app); }
    }

    private void processNext() {
        LoanApplication app = priorityQueue.poll();
        if (app == null) { out.println("No pending applications to process."); return; }
        if (app.isAffordable(maxEmiPercent)) {
            app.setStatus(LoanApplication.Status.APPROVED);
            out.println("APPROVED " + app.getApplicationId() + " | " + app.getApplicantName());
        } else {
            app.setStatus(LoanApplication.Status.REJECTED);
            out.printf(Locale.US, "REJECTED %s | %s (EMI is %.2f%% of income, limit %s%%)%n",
                    app.getApplicationId(), app.getApplicantName(),
                    app.getEmiToIncomePercent(), formatPercent(maxEmiPercent));
        }
        saveData();
    }

    private void rejectApplication() {
        String id = InputParser.normalizeId(readLine("Enter application ID to reject: "));
        LoanApplication app = applicationMap.get(id);
        if (app == null) { out.println("Application " + id + " not found."); return; }
        if (app.getStatus() != LoanApplication.Status.PENDING) {
            out.println("Only pending applications can be rejected."); return;
        }
        app.setStatus(LoanApplication.Status.REJECTED);
        priorityQueue.remove(app);
        out.println("Application " + id + " rejected.");
        saveData();
    }

    private void changeLimit() {
        out.println("Current limit: " + formatPercent(maxEmiPercent) + "% (applies to pending and new applications).");
        maxEmiPercent = readDecimal("Enter new limit as % of income (1 to 100): ", 1, 100);
        out.println("Limit set to " + formatPercent(maxEmiPercent) + "%.");
        saveData();
    }

    private void displayAll() {
        if (allApplications.isEmpty()) { out.println("No applications available."); return; }
        printHeading();
        for (LoanApplication app : allApplications) out.println(app);
    }

    private void displayBstOrder() {
        List<LoanApplication> sorted = loanBST.inOrder();
        if (sorted.isEmpty()) { out.println("No applications available."); return; }
        out.println("\nApplications sorted by loan amount (BST in-order):");
        printHeading();
        for (LoanApplication app : sorted) out.println(app);
    }

    private void generateSortedReport() {
        if (allApplications.isEmpty()) { out.println("No applications available for report."); return; }
        List<LoanApplication> sorted = new ArrayList<>(allApplications);
        sorted.sort(Comparator.comparingDouble(LoanApplication::getLoanAmount)
                .thenComparing(LoanApplication::getApplicationId));
        out.println("\n========== SORTED LOAN REVIEW REPORT ==========");
        printHeading();
        for (LoanApplication app : sorted) out.println(app);
    }

    private void printHeading() {
        String header = LoanApplication.header();
        out.println(header);
        out.println("-".repeat(header.length()));
    }

    /** Returns false when the data file exists but cannot be read; the file is then left untouched. */
    private boolean loadData() {
        try {
            LoanFileStore.LoadResult result = fileStore.load();
            for (String warning : result.warnings) out.println(warning);
            int skipped = result.skippedRows;
            for (LoanApplication app : result.applications) {
                if (applicationMap.containsKey(app.getApplicationId())) {
                    out.println("Skipping duplicate application ID: " + app.getApplicationId());
                    skipped++;
                } else {
                    addApplication(app);
                }
            }
            if (result.maxIdSeen >= nextId && result.maxIdSeen < LAST_ID) nextId = result.maxIdSeen + 1;
            if (result.maxEmiPercent != null) maxEmiPercent = result.maxEmiPercent;
            if (skipped > 0) {
                try {
                    out.println(skipped + " row(s) were skipped. The original file was backed up to "
                            + fileStore.backup().getFileName() + " so nothing is lost.");
                } catch (IOException ex) {
                    out.println("Could not back up the original file (" + ex.getMessage()
                            + "). Not starting, to avoid losing data.");
                    return false;
                }
            }
            out.println("Loaded " + allApplications.size() + " application(s).");
            return true;
        } catch (IOException ex) {
            out.println("Could not read " + fileStore.getDataFile().getFileName() + ": " + ex.getMessage());
            out.println("The file was left untouched. Fix or move it, then start the program again.");
            return false;
        }
    }

    private void saveData() {
        try { fileStore.save(allApplications, maxEmiPercent); }
        catch (IOException ex) { out.println("WARNING: Could not save data: " + ex.getMessage()); }
    }

    // ---- input helpers: all of them throw InputEndedException when input closes ----

    private String readLine(String prompt) {
        out.print(prompt);
        if (!sc.hasNextLine()) throw new InputEndedException();
        return sc.nextLine();
    }

    private double readDecimal(String prompt, double min, double max) {
        while (true) {
            try {
                double value = InputParser.parseDecimal(readLine(prompt));
                if (value >= min && value <= max) return value;
            } catch (NumberFormatException ignored) { }
            out.println("Enter a plain number in the allowed range (digits, optional decimal point and commas).");
        }
    }

    private int readWhole(String prompt, int min, int max) {
        while (true) {
            try {
                int value = InputParser.parseWholeNumber(readLine(prompt));
                if (value >= min && value <= max) return value;
            } catch (NumberFormatException ignored) { }
            out.println("Enter a whole number from " + min + " to " + max + ".");
        }
    }

    private String formatMoney(double amount) { return String.format(Locale.US, "%.2f", amount); }

    private String formatPercent(double value) {
        return value == Math.rint(value) ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.2f", value);
    }
}
