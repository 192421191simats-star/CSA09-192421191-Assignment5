import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Task A2 alternative concept - count the lines of the 50 MB log by
 *   (a) Files.readAllLines()   : every line becomes a String held in one List  -> O(file size) memory
 *   (b) BufferedReader         : one line at a time                            -> O(buffer + longest line)
 *   (c) Files.lines()          : lazy stream over a BufferedReader              -> O(buffer)
 *   (d) read(byte[64 KB])      : raw bytes, no String at all                    -> O(64 KB)
 * Reports time (median of 5) and the PEAK heap used during the call (memory pools' peak usage, reset before each run).
 * Also: (e) "when buffering does not help" - wrapping a 64 KB bulk read in a BufferedInputStream.
 * Run: java -Xmx1g -cp out ReadAllLinesVsBuffered ../TaskB1/work/attendance_50MB.log
 *      java -Xmx64m -cp out ReadAllLinesVsBuffered <file> readAllLines      (shows the OutOfMemoryError)
 */
public class ReadAllLinesVsBuffered {
    interface Counter { long count(Path f) throws IOException; }

    static long peakHeapDuring(Counter c, Path f, long[] linesOut) throws IOException {
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
        System.gc();
        long base = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        linesOut[0] = c.count(f);
        long peak = heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        return Math.max(0, peak - base);
    }

    public static void main(String[] args) throws Exception {
        Path f = Path.of(args.length > 0 ? args[0] : "../TaskB1/work/attendance_50MB.log");
        if (args.length > 1) {                                       // single method, for the small-heap demonstration
            long t0 = System.nanoTime();
            try {
                long n = args[1].equals("readAllLines") ? Files.readAllLines(f).size() : bufferedReader(f);
                System.out.printf("%s with -Xmx%dm: %,d lines in %.0f ms%n", args[1], Runtime.getRuntime().maxMemory() >> 20, n, (System.nanoTime() - t0) / 1e6);
            } catch (OutOfMemoryError e) {
                System.out.printf("%s with max heap %d MB: %s after %.0f ms%n", args[1], Runtime.getRuntime().maxMemory() >> 20, e, (System.nanoTime() - t0) / 1e6);
            }
            return;
        }
        long size = Files.size(f);
        String[] names = {"Files.readAllLines()", "BufferedReader.readLine()", "Files.lines() stream", "read(byte[64 KB])"};
        Counter[] cs = {p -> Files.readAllLines(p, StandardCharsets.UTF_8).size(), ReadAllLinesVsBuffered::bufferedReader,
                p -> { try (Stream<String> s = Files.lines(p)) { return s.count(); } }, p -> bulk(p, false)};
        for (int w = 0; w < 2; w++) for (Counter c : cs) c.count(f);
        Files.createDirectories(Path.of("data"));
        System.out.printf("File %s: %,d bytes (%.1f MB)%n%n", f.getFileName(), size, size / 1048576.0);
        System.out.printf("%-28s %12s %12s %18s %14s%n", "method", "median ms", "lines", "peak extra heap", "heap / file");
        try (PrintWriter csv = new PrintWriter("data/a2_readall.csv")) {
            csv.println("method,median_ms,peak_heap_mb,file_mb");
            for (int i = 0; i < cs.length; i++) {
                double[] t = new double[5]; long[] lines = new long[1]; long peak = 0;
                for (int r = 0; r < 5; r++) {
                    long t0 = System.nanoTime();
                    if (r == 0) peak = peakHeapDuring(cs[i], f, lines); else lines[0] = cs[i].count(f);
                    t[r] = (System.nanoTime() - t0) / 1e6;
                }
                Arrays.sort(t);
                System.out.printf("%-28s %12.1f %,12d %15.1f MB %13.2fx%n", names[i], t[2], lines[0], peak / 1048576.0, peak / (double) size);
                csv.printf("%s,%.2f,%.2f,%.2f%n", names[i], t[2], peak / 1048576.0, size / 1048576.0);
            }
        }
        System.out.println();
        System.out.println("=== A2.3 when buffering does NOT help: the program already reads in big blocks ===");
        double[] plain = new double[21], wrapped = new double[21];
        for (int w = 0; w < 5; w++) { bulk(f, false); bulk(f, true); }
        for (int r = 0; r < 21; r++) {                              // alternate the order so neither always runs first
            boolean wrapFirst = r % 2 == 1;
            for (int k = 0; k < 2; k++) {
                boolean wrap = (k == 0) == wrapFirst;
                long t0 = System.nanoTime(); bulk(f, wrap); double ms = (System.nanoTime() - t0) / 1e6;
                if (wrap) wrapped[r] = ms; else plain[r] = ms;
            }
        }
        Arrays.sort(plain); Arrays.sort(wrapped);
        System.out.printf("FileInputStream.read(byte[64 KB])                       %8.1f ms (median of 21)%n", plain[10]);
        System.out.printf("BufferedInputStream(FileInputStream).read(byte[64 KB])  %8.1f ms (median of 21) - requests >= 8 KB bypass the buffer%n", wrapped[10]);
        System.out.println("Raw data: data/a2_readall.csv");
    }

    static long bufferedReader(Path f) throws IOException {
        long n = 0;
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { while (r.readLine() != null) n++; }
        return n;
    }

    static long bulk(Path f, boolean wrap) throws IOException {
        long n = 0; byte[] buf = new byte[65536];
        try (InputStream in = wrap ? new BufferedInputStream(new FileInputStream(f.toFile())) : new FileInputStream(f.toFile())) {
            int k;
            while ((k = in.read(buf)) > 0) for (int i = 0; i < k; i++) if (buf[i] == '\n') n++;
        }
        return n;
    }
}
