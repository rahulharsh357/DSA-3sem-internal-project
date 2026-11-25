import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the data file. File format (one application per line):
 * id|name|loanAmount|annualRate|tenureMonths|monthlyIncome|priority|status
 * An optional first line "#maxEmiPercent=50.0" stores the affordability limit.
 * Files from older versions (9 fields, with a sequence number after the ID) are still readable.
 */
public class LoanFileStore implements AutoCloseable {
    private static final String LIMIT_KEY = "#maxEmiPercent=";

    public static class LoadResult {
        public final List<LoanApplication> applications = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int skippedRows = 0;
        public long maxIdSeen = 0;          // highest numeric ID seen, even in skipped rows
        public Double maxEmiPercent = null; // null when the file stores no limit
    }

    private final Path dataFile;
    private FileChannel lockChannel;
    private FileLock lock;
    private Path lockPath;

    public LoanFileStore(Path dataFile) { this.dataFile = dataFile.toAbsolutePath(); }

    public Path getDataFile() { return dataFile; }

    /** Prevents two copies of the program from overwriting each other. Returns false if already in use. */
    public boolean tryLock() throws IOException {
        Path parent = dataFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        lockPath = dataFile.resolveSibling(dataFile.getFileName() + ".lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException ex) {
            lock = null; // already locked inside this same JVM
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
        lock = null;
        lockChannel = null;
        try { if (lockPath != null) Files.deleteIfExists(lockPath); } catch (IOException ignored) { }
    }

    /** Throws IOException if the file exists but cannot be read (for example, invalid UTF-8). */
    public LoadResult load() throws IOException {
        LoadResult result = new LoadResult();
        if (!Files.exists(dataFile)) return result;
        int lineNumber = 0;
        for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) {
                readHeaderLine(trimmed, lineNumber, result);
                continue;
            }
            try {
                result.applications.add(parse(trimmed, result));
            } catch (RuntimeException ex) {
                result.skippedRows++;
                result.warnings.add("Skipping invalid data at line " + lineNumber + ": " + ex.getMessage());
            }
        }
        return result;
    }

    private void readHeaderLine(String line, int lineNumber, LoadResult result) {
        if (!line.startsWith(LIMIT_KEY)) return; // unknown comment: ignore
        try {
            double value = Double.parseDouble(line.substring(LIMIT_KEY.length()).trim());
            if (value > 0 && value <= 100) { result.maxEmiPercent = value; return; }
        } catch (NumberFormatException ignored) { }
        result.warnings.add("Ignoring invalid limit on line " + lineNumber + "; using the default.");
    }

    private LoanApplication parse(String line, LoadResult result) {
        String[] p = line.split("\\|", -1);
        int offset;
        if (p.length == 8) offset = 0;
        else if (p.length == 9) offset = 1; // legacy layout: skip the old sequence column
        else throw new IllegalArgumentException("expected 8 fields");

        String id = p[0].trim();
        if (id.matches("L[0-9]{1,18}")) result.maxIdSeen = Math.max(result.maxIdSeen, LoanApplication.idNumber(id));
        String name = InputParser.cleanName(p[1 + offset]);
        double amount = Double.parseDouble(p[2 + offset].trim());
        double rate = Double.parseDouble(p[3 + offset].trim());
        int months = Integer.parseInt(p[4 + offset].trim());
        double income = Double.parseDouble(p[5 + offset].trim());
        int priority = Integer.parseInt(p[6 + offset].trim());
        String statusText = p[7 + offset].trim();
        LoanApplication.Status status = statusText.equals("PROCESSED")   // older versions
                ? LoanApplication.Status.APPROVED
                : LoanApplication.Status.valueOf(statusText);

        String error = LoanApplication.validate(id, name, amount, rate, months, income, priority);
        if (error != null) throw new IllegalArgumentException(error);
        return new LoanApplication(id, name, amount, rate, months, income, priority, status);
    }

    /** Copies the data file to a timestamped .bak file and returns its path. */
    public Path backup() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path target = dataFile.resolveSibling(dataFile.getFileName() + ".bak-" + stamp);
        Files.copy(dataFile, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Writes to a temporary file first, then replaces the data file (atomic move when supported). */
    public void save(List<LoanApplication> applications, double maxEmiPercent) throws IOException {
        Path parent = dataFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        List<String> lines = new ArrayList<>();
        lines.add(LIMIT_KEY + maxEmiPercent);
        for (LoanApplication app : applications) lines.add(app.toDataLine());
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp); // never leave a stale .tmp file behind
        }
    }
}
