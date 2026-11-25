import java.math.BigDecimal;

/** Strict parsing of typed input. Unlike Double.parseDouble it rejects "100d", "1e5", "0x1p3", "NaN". */
public final class InputParser {
    private InputParser() { }

    /** Accepts 5000, 5000.50, 1,00,000 and 1,000,000. A leading currency symbol or "Rs" is ignored. */
    public static double parseDecimal(String text) {
        if (text == null) throw new NumberFormatException("empty");
        String s = text.trim();
        s = s.replaceFirst("^(?i)(rs\\.?|inr|₹)\\s*", "");
        s = s.replaceFirst("\\s*%$", "");
        s = s.replace(",", "").replace(" ", "");
        if (!s.matches("[0-9]{1,15}(\\.[0-9]{1,6})?")) throw new NumberFormatException("not a plain number");
        return new BigDecimal(s).doubleValue();
    }

    public static int parseWholeNumber(String text) {
        if (text == null) throw new NumberFormatException("empty");
        String s = text.trim();
        if (!s.matches("[0-9]{1,9}")) throw new NumberFormatException("not a whole number");
        return Integer.parseInt(s);
    }

    /** Removes pipes and control characters, collapses spaces, trims and limits the length. */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.codePointCount(0, s.length()) > LoanApplication.MAX_NAME_LENGTH) {
            s = s.substring(0, s.offsetByCodePoints(0, LoanApplication.MAX_NAME_LENGTH)).trim();
        }
        return s;
    }

    /** Lets the user type 101 or l101 for L101. */
    public static String normalizeId(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase();
        return s.matches("[0-9]+") ? "L" + s : s;
    }
}
