import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Task B1.3 - time to count lines in 1, 10 and 50 MB logs, unbuffered vs buffered (plus two other
 * buffered forms). Median of 5 runs (unbuffered 50 MB: median of 3). The file is in the OS page cache
 * after the first run, so the times measure the cost of the CALLS, not of the disk.
 * Run: java -cp out BufferingBenchmark work
 */
public class BufferingBenchmark {
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "work");
        Files.createDirectories(Path.of("data"));
        String[] modes = {"unbuffered", "buffered", "array", "reader"};
        String[] label = {"FileInputStream.read() per byte", "BufferedInputStream (8 KB)", "read(byte[64 KB])", "BufferedReader.readLine()"};
        for (int w = 0; w < 3; w++) for (String m : new String[]{"buffered", "array", "reader"}) LineCounter.count(m, dir.resolve("attendance_10MB.log"));
        try (PrintWriter csv = new PrintWriter("data/b1_buffering.csv")) {
            csv.println("file_mb,bytes,mode,median_ms,lines");
            System.out.printf("%-8s %-34s %12s %12s %16s%n", "file", "method", "median ms", "lines", "MB/s");
            for (int mb : new int[]{1, 10, 50}) {
                Path f = dir.resolve("attendance_" + mb + "MB.log");
                long bytes = Files.size(f);
                for (int m = 0; m < modes.length; m++) {
                    int reps = modes[m].equals("unbuffered") && mb == 50 ? 3 : 5;
                    double[] t = new double[reps];
                    long lines = 0;
                    for (int r = 0; r < reps; r++) {
                        long t0 = System.nanoTime();
                        lines = LineCounter.count(modes[m], f);
                        t[r] = (System.nanoTime() - t0) / 1e6;
                    }
                    Arrays.sort(t);
                    double med = t[reps / 2];
                    System.out.printf("%-8s %-34s %12.1f %,12d %16.1f%n", mb + " MB", label[m], med, lines, bytes / 1048576.0 / (med / 1000));
                    csv.printf("%d,%d,%s,%.3f,%d%n", mb, bytes, modes[m], med, lines);
                }
            }
        }
        System.out.println("Raw data: data/b1_buffering.csv");
    }
}
