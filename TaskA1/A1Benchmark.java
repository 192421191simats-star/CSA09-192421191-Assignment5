import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Task A1 - (1) round trip of both designed layouts, (2) the alternative: find by ID with a text scan vs a
 * RandomAccessFile seek as n grows, (3) space: text vs DataOutputStream binary vs fixed 128-byte records and
 * the bytes wasted by padding.  Run: java -cp out A1Benchmark work
 */
public class A1Benchmark {
    static double median(double[] a) { double[] c = a.clone(); Arrays.sort(c); return c[c.length / 2]; }

    static Optional<PassCsv.Pass> scan(Path f, String id) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || !PassCsv.idOf(line).equals(id)) continue;
                try { return Optional.of(PassCsv.decode(line)); } catch (PassCsv.DamagedLineException e) { /* skip */ }
            }
        }
        return Optional.empty();
    }

    static void writeText(Path f, List<PassCsv.Pass> l) throws IOException {
        try (var w = Files.newBufferedWriter(f)) { w.write(PassCsv.HEADER); w.newLine(); for (PassCsv.Pass p : l) { w.write(PassCsv.encode(p)); w.newLine(); } }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "work");
        Files.createDirectories(dir); Files.createDirectories(Path.of("data"));

        System.out.println("=== (1) The two designed layouts on 5 sample passes ===");
        List<PassCsv.Pass> sample = PassData.passes(5);
        sample = new java.util.ArrayList<>(sample);
        sample.set(2, new PassCsv.Pass("P100003", "192300003", "Rao, \"AK\" Arun", 12, "Main Gate", sample.get(2).validUntil(), sample.get(2).amount()));
        Path t = dir.resolve("sample.csv"), b = dir.resolve("sample.bin");
        writeText(t, sample);
        BinaryPassFormat.writeAll(b, sample);
        System.out.println("(a) text file " + t.getFileName() + " (" + Files.size(t) + " bytes):");
        for (String l : Files.readAllLines(t)) System.out.println("    " + l);
        BinaryPassFormat.ReadResult rr = BinaryPassFormat.readAll(b);
        System.out.println("(b) binary file " + b.getFileName() + " (" + Files.size(b) + " bytes) read back: " + rr.passes().size() + " passes, problems " + rr.problems());
        System.out.println("    record 3 name read back from binary: " + rr.passes().get(2).name() + "   equal to original: " + rr.passes().equals(sample));
        System.out.println("    first 48 bytes (hex): " + hex(Files.readAllBytes(b), 48));
        byte[] all = Files.readAllBytes(b);
        Files.write(b, Arrays.copyOf(all, all.length - 20));                  // simulate a power failure in the last record
        System.out.println("    after cutting the last 20 bytes off (power failure): " + BinaryPassFormat.readAll(b).passes().size() + " passes, problems " + BinaryPassFormat.readAll(b).problems());

        System.out.println();
        System.out.println("=== (2) find by pass ID: text scan O(n) vs RandomAccessFile seek O(1) (median of 15) ===");
        System.out.printf("%-9s %16s %18s %14s%n", "records", "text scan ms", "RAF seek ms", "speed-up");
        try (PrintWriter csv = new PrintWriter("data/a1_find.csv"); PrintWriter sp = new PrintWriter("data/a1_space.csv")) {
            csv.println("records,text_scan_ms,raf_seek_ms");
            sp.println("records,text_bytes,binary_bytes,fixed_bytes,useful_bytes_in_fixed,padding_bytes");
            Path warmT = dir.resolve("warm.csv"), warmR = dir.resolve("warm.dat");
            List<PassCsv.Pass> wl = PassData.passes(20_000);
            writeText(warmT, wl);
            Files.deleteIfExists(warmR);
            try (FixedRecordStore s = new FixedRecordStore(warmR)) { s.putAll(wl); for (int i = 0; i < 3000; i++) { scan(warmT, "P120000"); s.find("P" + (100001 + i % 20000)); } }
            for (int n : new int[]{1_000, 10_000, 50_000, 100_000}) {
                List<PassCsv.Pass> l = PassData.passes(n);
                Path tf = dir.resolve("a1_" + n + ".csv"), rf = dir.resolve("a1_" + n + ".dat"), bf = dir.resolve("a1_" + n + ".bin");
                writeText(tf, l); BinaryPassFormat.writeAll(bf, l);
                Files.deleteIfExists(rf);
                double[] ts = new double[15], rs = new double[15];
                try (FixedRecordStore s = new FixedRecordStore(rf)) {
                    s.putAll(l);
                    String last = "P" + (100000 + n);
                    if (!scan(tf, last).equals(s.find(last))) throw new IllegalStateException("stores disagree");
                    for (int r = 0; r < 15; r++) {
                        long t0 = System.nanoTime(); scan(tf, last); ts[r] = (System.nanoTime() - t0) / 1e6;
                        t0 = System.nanoTime(); s.find(last); rs[r] = (System.nanoTime() - t0) / 1e6;
                    }
                }
                System.out.printf("%,-9d %16.3f %18.4f %13.0fx%n", n, median(ts), median(rs), median(ts) / median(rs));
                csv.printf("%d,%.4f,%.5f%n", n, median(ts), median(rs));
                long useful = 0;
                for (PassCsv.Pass p : l) useful += 1 + 4 + 4 + 2L * p.name().length() + 1 + 2L * p.boardingStop().length() + 4 + 8 + 4;
                sp.printf("%d,%d,%d,%d,%d,%d%n", n, Files.size(tf), Files.size(bf), Files.size(rf), useful, Files.size(rf) - useful);
            }
        }

        System.out.println();
        System.out.println("=== (3) Space for 100,000 passes ===");
        List<String> rows = Files.readAllLines(Path.of("data/a1_space.csv"));
        String[] v = rows.get(rows.size() - 1).split(",");
        long text = Long.parseLong(v[1]), bin = Long.parseLong(v[2]), fixed = Long.parseLong(v[3]), useful = Long.parseLong(v[4]);
        System.out.printf("text (CSV + CRC)          %,12d bytes  %6.1f bytes/record%n", text, text / 1e5);
        System.out.printf("binary (DataOutputStream) %,12d bytes  %6.1f bytes/record%n", bin, bin / 1e5);
        System.out.printf("fixed 128-byte records    %,12d bytes  %6.1f bytes/record%n", fixed, fixed / 1e5);
        System.out.printf("  of which useful data    %,12d bytes; padding %,d bytes = %.1f%% of the file%n", useful, fixed - useful, 100.0 * (fixed - useful) / fixed);
        System.out.println("Raw data: data/a1_find.csv, data/a1_space.csv");
    }

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) sb.append(String.format("%02x", b[i])).append(i % 4 == 3 ? " " : "");
        return sb.toString();
    }
}
