import java.util.Locale;

public class LoanApplication {
    public enum Status { PENDING, APPROVED, REJECTED }

    // Limits shared by the menu input and the data-file loader.
    public static final double DEFAULT_MAX_EMI_PERCENT = 50.0;
    public static final double MAX_LOAN_AMOUNT = 1e12;
    public static final double MAX_MONTHLY_INCOME = 1e9;
    public static final double MAX_ANNUAL_RATE = 1000.0;
    public static final int MIN_TENURE_MONTHS = 1;
    public static final int MAX_TENURE_MONTHS = 600;
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 10;
    public static final int MAX_NAME_LENGTH = 60;

    private static final int NAME_COLUMN_WIDTH = 22;
    private static final String ROW_FORMAT = "%-7s %-22s %16.2f %14.2f %8d %11.2f %8d %-10s";
    private static final String HEADER_FORMAT = "%-7s %-22s %16s %14s %8s %11s %8s %-10s";

    private final String applicationId;
    private final long sequence; // numeric part of the ID; defines submission (FIFO) order
    private final String applicantName;
    private final double loanAmount;
    private final double annualInterestRate;
    private final int tenureMonths;
    private final double monthlyIncome;
    private final int priority;
    private Status status;

    public LoanApplication(String applicationId, String applicantName, double loanAmount,
                           double annualInterestRate, int tenureMonths, double monthlyIncome,
                           int priority, Status status) {
        String error = validate(applicationId, applicantName, loanAmount, annualInterestRate,
                tenureMonths, monthlyIncome, priority);
        if (error != null) throw new IllegalArgumentException(error);
        this.applicationId = applicationId;
        this.sequence = idNumber(applicationId);
        this.applicantName = applicantName;
        this.loanAmount = loanAmount;
        this.annualInterestRate = annualInterestRate;
        this.tenureMonths = tenureMonths;
        this.monthlyIncome = monthlyIncome;
        this.priority = priority;
        this.status = status;
    }

    /** Returns an error message, or null when every field is valid. NaN and infinity are rejected. */
    public static String validate(String id, String name, double amount, double rate,
                                  int months, double income, int priority) {
        if (!isValidId(id)) return "bad application ID";
        if (name == null || name.isEmpty()) return "empty name";
        if (!(amount > 0 && amount <= MAX_LOAN_AMOUNT)) return "bad loan amount";
        if (!(rate >= 0 && rate <= MAX_ANNUAL_RATE)) return "bad interest rate";
        if (months < MIN_TENURE_MONTHS || months > MAX_TENURE_MONTHS) return "bad tenure";
        if (!(income > 0 && income <= MAX_MONTHLY_INCOME)) return "bad income";
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) return "bad priority";
        return null;
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("L[0-9]{1,18}");
    }

    public static long idNumber(String id) {
        return Long.parseLong(id.substring(1));
    }

    public String getApplicationId() { return applicationId; }
    public long getSequence() { return sequence; }
    public String getApplicantName() { return applicantName; }
    public double getLoanAmount() { return loanAmount; }
    public double getAnnualInterestRate() { return annualInterestRate; }
    public int getTenureMonths() { return tenureMonths; }
    public double getMonthlyIncome() { return monthlyIncome; }
    public int getPriority() { return priority; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    /**
     * Reducing-balance EMI = P*r*(1+r)^n / ((1+r)^n - 1), written as P*r / (1 - (1+r)^-n) and
     * evaluated with log1p/expm1 so that very small rates stay accurate (no 0/0) and large
     * tenures cannot overflow.
     */
    public double calculateEmi() {
        double monthlyRate = annualInterestRate / 12.0 / 100.0;
        double growth = Math.log1p(monthlyRate) * tenureMonths;
        if (growth == 0.0) return loanAmount / tenureMonths;
        return loanAmount * monthlyRate / -Math.expm1(-growth);
    }

    public double getEmiToIncomePercent() {
        return calculateEmi() / monthlyIncome * 100.0;
    }

    /** Compares the percentage as displayed (2 decimals), so the screen and the decision always agree. */
    public boolean isAffordable(double maxEmiPercent) {
        double shown = Math.round(getEmiToIncomePercent() * 100.0) / 100.0;
        return shown <= maxEmiPercent;
    }

    public String toDataLine() {
        return String.join("|", applicationId, applicantName,
                Double.toString(loanAmount), Double.toString(annualInterestRate),
                Integer.toString(tenureMonths), Double.toString(monthlyIncome),
                Integer.toString(priority), status.name());
    }

    /** Truncates by Unicode code point, so emoji and other characters are never split in half. */
    static String shorten(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) return text;
        int end = text.offsetByCodePoints(0, maxCodePoints - 3);
        return text.substring(0, end) + "...";
    }

    @Override
    public String toString() {
        return String.format(Locale.US, ROW_FORMAT, applicationId,
                shorten(applicantName, NAME_COLUMN_WIDTH), loanAmount, calculateEmi(), tenureMonths,
                getEmiToIncomePercent(), priority, status);
    }

    public static String header() {
        return String.format(Locale.US, HEADER_FORMAT,
                "ID", "Applicant", "LoanAmount", "EMI", "Months", "EMI/Income%", "Priority", "Status");
    }
}
