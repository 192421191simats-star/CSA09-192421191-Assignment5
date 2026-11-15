import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Task B1.4 - time to find one pass as the file grows from 1,000 to 100,000 records (scan),
 * and the B1 alternative: load the file ONCE into HashMap<String, String[]> and answer q searches.
 * Run: java -Xmx1g -cp out GrowthBenchmark work
 */
public class GrowthBenchmark {
    static double median(double[] a) { double[] c = a.clone(); Arrays.sort(c); return c[c.length / 2]; }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "work");
        Files.createDirectories(dir); Files.createDirectories(Path.of("data"));
        int[] sizes = {1_000, 5_000, 10_000, 50_000, 100_000};
        // warm-up
        Path w = dir.resolve("passes_warm.csv"); Files.deleteIfExists(w);
        new PassFileStore(w, false).addAll(PassData.passes(20_000));
        for (int i = 0; i < 200; i++) new PassFileStore(w).find("P" + (100000 + 19_999));

        try (PrintWriter csv = new PrintWriter("data/b1_growth.csv"); PrintWriter alt = new PrintWriter("data/b1_hashmap.csv")) {
            csv.println("records,file_bytes,first_ms,middle_ms,last_ms,missing_ms");
            alt.println("records,q,scan_total_ms,map_build_ms,map_lookup_total_ms,map_heap_mb");
            System.out.println("=== B1.4 find(passId) by linear scan (median of 15 runs) ===");
            System.out.printf("%-9s %10s %11s %11s %11s %13s %14s%n", "records", "file KB", "first ms", "middle ms", "last ms", "missing ms", "us per record");
            for (int n : sizes) {
                Path f = dir.resolve("passes_" + n + ".csv"); Files.deleteIfExists(f);
                new PassFileStore(f, false).addAll(PassData.passes(n));
                PassFileStore s = new PassFileStore(f);
                String[] ids = {"P" + 100001, "P" + (100000 + n / 2), "P" + (100000 + n), "P999999"};
                double[] med = new double[4];
                for (int r = 0; r < 5; r++) s.find("P999999");                     // warm the page cache and the JIT on this file
                for (int k = 0; k < 4; k++) {
                    double[] t = new double[15];
                    for (int r = 0; r < 15; r++) { long t0 = System.nanoTime(); s.find(ids[k]); t[r] = (System.nanoTime() - t0) / 1e6; }
                    med[k] = median(t);
                }
                System.out.printf("%,-9d %10.0f %11.3f %11.3f %11.3f %13.3f %14.3f%n", n, Files.size(f) / 1024.0, med[0], med[1], med[2], med[3], med[3] * 1000 / n);
                csv.printf("%d,%d,%.4f,%.4f,%.4f,%.4f%n", n, Files.size(f), med[0], med[1], med[2], med[3]);
            }

            System.out.println();
            System.out.println("=== Alternative: read once into HashMap<String, String[]>, then q searches ===");
            System.out.printf("%-9s %6s %16s %15s %18s %14s %16s%n", "records", "q", "scan q times ms", "map build ms", "map q lookups ms", "map total ms", "map heap MB");
            Random rnd = new Random(5);
            for (int n : new int[]{1_000, 10_000, 100_000}) {
                Path f = dir.resolve("passes_" + n + ".csv");
                PassFileStore s = new PassFileStore(f);
                for (int q : new int[]{1, 10, 100, 1000}) {
                    String[] ids = new String[q];
                    for (int i = 0; i < q; i++) ids[i] = "P" + (100001 + rnd.nextInt(n));
                    double scan;
                    if ((long) q * n <= 10_000_000L) {
                        long t0 = System.nanoTime();
                        for (String id : ids) s.find(id);
                        scan = (System.nanoTime() - t0) / 1e6;
                    } else {                                     // too slow to run in full: time 100 and scale
                        long t0 = System.nanoTime();
                        for (int i = 0; i < 100; i++) s.find(ids[i]);
                        scan = (System.nanoTime() - t0) / 1e6 * q / 100.0;
                    }
                    Runtime rt = Runtime.getRuntime();
                    System.gc(); Thread.sleep(50); long before = rt.totalMemory() - rt.freeMemory();
                    long t0 = System.nanoTime();
                    Map<String, String[]> map = load(f);
                    double build = (System.nanoTime() - t0) / 1e6;
                    t0 = System.nanoTime();
                    int found = 0;
                    for (String id : ids) if (map.get(id) != null) found++;
                    double look = (System.nanoTime() - t0) / 1e6;
                    System.gc(); Thread.sleep(50); long after = rt.totalMemory() - rt.freeMemory();
                    double heap = (after - before) / 1048576.0;
                    if (found != q) throw new IllegalStateException("lookup failed");
                    System.out.printf("%,-9d %6d %16.1f %15.1f %18.3f %14.1f %16.1f%s%n", n, q, scan, build, look, build + look, heap, (long) q * n > 10_000_000L ? "   (scan: 100 timed, scaled)" : "");
                    alt.printf("%d,%d,%.3f,%.3f,%.4f,%.1f%n", n, q, scan, build, look, heap);
                    map.clear();
                }
            }
        }
        System.out.println("Raw data: data/b1_growth.csv, data/b1_hashmap.csv");
    }

    /** One pass over the file: O(n) time, O(n) memory. Damaged lines are skipped exactly as in find(). */
    static Map<String, String[]> load(Path f) throws IOException {
        Map<String, String[]> m = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                try { PassCsv.decode(line); m.putIfAbsent(PassCsv.idOf(line), PassCsv.split(line).toArray(new String[0])); }
                catch (PassCsv.DamagedLineException e) { /* skipped */ }
            }
        }
        return m;
    }
}
