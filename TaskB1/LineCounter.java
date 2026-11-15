import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Task A2 / B1 - counting the lines of an attendance log.
 *   unbuffered : FileInputStream.read()           - one native call (and one read system call) per BYTE
 *   buffered   : BufferedInputStream.read()       - read() still per byte, but served from an 8 KB array;
 *                                                   the OS is asked once per 8,192 bytes
 *   array      : FileInputStream.read(byte[64K])  - our own buffer, one call per 64 KB
 *   reader     : BufferedReader.readLine()        - decodes characters and builds a String per line
 * Run: java -cp out LineCounter <mode> <file>
 */
public final class LineCounter {
    private LineCounter() { }

    public static long unbuffered(Path f) throws IOException {
        long lines = 0;
        try (InputStream in = new FileInputStream(f.toFile())) {
            int b;
            while ((b = in.read()) != -1) if (b == '\n') lines++;
        }
        return lines;
    }

    public static long buffered(Path f) throws IOException {
        long lines = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f.toFile()))) {   // default 8,192-byte buffer
            int b;
            while ((b = in.read()) != -1) if (b == '\n') lines++;
        }
        return lines;
    }

    public static long array(Path f, int size) throws IOException {
        long lines = 0;
        byte[] buf = new byte[size];
        try (InputStream in = new FileInputStream(f.toFile())) {
            int n;
            while ((n = in.read(buf)) > 0) for (int i = 0; i < n; i++) if (buf[i] == '\n') lines++;
        }
        return lines;
    }

    public static long reader(Path f) throws IOException {
        long lines = 0;
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            while (r.readLine() != null) lines++;
        }
        return lines;
    }

    public static long count(String mode, Path f) throws IOException {
        return switch (mode) {
            case "unbuffered" -> unbuffered(f);
            case "buffered" -> buffered(f);
            case "array" -> array(f, 65536);
            case "reader" -> reader(f);
            default -> throw new IllegalArgumentException("mode: unbuffered | buffered | array | reader");
        };
    }

    public static void main(String[] args) throws IOException {
        long t0 = System.nanoTime();
        long n = count(args[0], Path.of(args[1]));
        System.out.printf("%s: %,d lines in %s (%.1f ms)%n", args[0], n, args[1], (System.nanoTime() - t0) / 1e6);
    }
}
