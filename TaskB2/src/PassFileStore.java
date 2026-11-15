import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Task B1 - the bus-pass file store designed in Task A1 (text format, see PassCsv).
 *  add          : validate, encode, append ONE line, flush and force to disk (fsync)
 *  find(id)     : BufferedReader scan, cheap ID test first, full decode + checksum only on a match
 *  listByRoute  : BufferedReader scan, every line decoded; damaged lines reported, never fatal
 * Missing file  : find/list return "not found"/empty and say so; add creates the file with its header.
 * Power failure : a record cut short has no valid checksum (or no newline). Before the next append the
 *                 store ends the fragment with a newline, so the new record is never glued to it.
 */
public final class PassFileStore {
    public record Damaged(long lineNo, String reason, String text) { }
    public record ScanResult(List<PassCsv.Pass> passes, List<Damaged> damaged, boolean fileMissing) { }

    private final Path file;
    private final boolean durable;

    public PassFileStore(Path file) { this(file, true); }
    /** durable=false skips the fsync (used only to generate large benchmark files quickly). */
    public PassFileStore(Path file, boolean durable) { this.file = file; this.durable = durable; }

    public void add(PassCsv.Pass p) throws IOException {
        if (p.name().contains("\n") || p.name().contains("\r") || p.boardingStop().contains("\n"))
            throw new IllegalArgumentException("line breaks are not allowed in a field");
        try { PassCsv.decode(PassCsv.encode(p)); }
        catch (PassCsv.DamagedLineException e) { throw new IllegalArgumentException("invalid pass: " + e.getMessage()); }
        boolean isNew = !Files.exists(file) || Files.size(file) == 0;
        String prefix = isNew ? PassCsv.HEADER + "\n" : (endsWithNewline() ? "" : "\n");   // close an interrupted line
        byte[] bytes = (prefix + PassCsv.encode(p) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            if (durable) ch.force(false);                        // the record is on disk when add() returns
        }
    }

    /** Bulk append through one BufferedWriter (used to build test files). */
    public void addAll(List<PassCsv.Pass> passes) throws IOException {
        boolean isNew = !Files.exists(file) || Files.size(file) == 0;
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (isNew) { w.write(PassCsv.HEADER); w.newLine(); }
            for (PassCsv.Pass p : passes) { w.write(PassCsv.encode(p)); w.write('\n'); }
        }
    }

    private boolean endsWithNewline() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() == 0) return true;
            raf.seek(raf.length() - 1);
            return raf.read() == '\n';
        }
    }

    /** Linear scan; returns the first VALID record with this ID. Damaged lines are skipped (and collected). */
    public Optional<PassCsv.Pass> find(String passId, List<Damaged> damagedOut) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; long no = 0;
            while ((line = r.readLine()) != null) {
                no++;
                if (line.startsWith("#")) continue;
                if (!PassCsv.idOf(line).equals(passId)) continue;      // cheap test on most lines
                try { return Optional.of(PassCsv.decode(line)); }
                catch (PassCsv.DamagedLineException e) { if (damagedOut != null) damagedOut.add(new Damaged(no, e.getMessage(), line)); }
            }
        } catch (NoSuchFileException e) {
            if (damagedOut != null) damagedOut.add(new Damaged(0, "file " + file.getFileName() + " does not exist", ""));
        }
        return Optional.empty();
    }

    public Optional<PassCsv.Pass> find(String passId) throws IOException { return find(passId, null); }

    public ScanResult listByRoute(int route) throws IOException { return scan(route); }
    public ScanResult loadAll() throws IOException { return scan(-1); }

    private ScanResult scan(int route) throws IOException {
        List<PassCsv.Pass> out = new ArrayList<>();
        List<Damaged> bad = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; long no = 0;
            while ((line = r.readLine()) != null) {
                no++;
                if (line.startsWith("#")) continue;
                try {
                    PassCsv.Pass p = PassCsv.decode(line);
                    if (route < 0 || p.routeNo() == route) out.add(p);
                } catch (PassCsv.DamagedLineException e) { bad.add(new Damaged(no, e.getMessage(), line)); }
            }
        } catch (NoSuchFileException e) {
            return new ScanResult(out, bad, true);
        }
        return new ScanResult(out, bad, false);
    }
}
