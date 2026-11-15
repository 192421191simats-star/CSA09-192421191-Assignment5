import hostel.Hostel;
import hostel.Resident;
import hostel.Room;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task A4 alternative - Java serialization vs CSV vs JSON for n residents (4 per room):
 * file size, time to save, time to load (median of 5); then how each copes with a new field.
 * Run: java -Xmx2g -cp out FormatBenchmark
 */
public class FormatBenchmark {
    interface Io { void run() throws Exception; }
    static double ms(Io io) throws Exception { long t0 = System.nanoTime(); io.run(); return (System.nanoTime() - t0) / 1e6; }
    static double med(double[] a) { double[] c = a.clone(); Arrays.sort(c); return c[c.length / 2]; }

    static Hostel build(int n) {
        Hostel h = new Hostel("Vivekananda Hostel");
        Room r = null;
        for (int i = 0; i < n; i++) {
            if (i % 4 == 0) r = h.addRoom(String.format("R%05d", i / 4 + 1), 4);
            r.residents.add(new Resident("Resident " + i + (i % 50 == 0 ? ", Jr." : ""), String.valueOf(192300000 + i), "pw" + i, null));
        }
        return h;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("work"); Files.createDirectories(dir); Files.createDirectories(Path.of("data"));
        Path ser = dir.resolve("fmt.ser"), csv = dir.resolve("fmt.csv"), json = dir.resolve("fmt.json");
        Io[] warm = {() -> {}};
        Hostel w = build(20_000);
        for (int k = 0; k < 5; k++) { saveSer(w, ser); loadSer(ser); HostelFormats.saveCsv(w, csv); HostelFormats.loadCsv(csv, null); HostelFormats.saveJson(w, json); HostelFormats.loadJson(json, null); }
        System.out.printf("%-10s %-22s %12s %10s %12s %12s%n", "residents", "format", "file bytes", "B/resident", "save ms", "load ms");
        try (PrintWriter out = new PrintWriter("data/a4_formats.csv")) {
            out.println("residents,format,bytes,save_ms,load_ms");
            for (int n : new int[]{1_000, 10_000, 100_000}) {
                Hostel h = build(n);
                String[] names = {"Java serialization", "CSV (header)", "JSON"};
                for (int fmt = 0; fmt < 3; fmt++) {
                    double[] s = new double[5], l = new double[5];
                    Path f = fmt == 0 ? ser : fmt == 1 ? csv : json;
                    for (int r = 0; r < 5; r++) {
                        final int F = fmt;
                        s[r] = ms(() -> { if (F == 0) saveSer(h, ser); else if (F == 1) HostelFormats.saveCsv(h, csv); else HostelFormats.saveJson(h, json); });
                        Hostel[] back = new Hostel[1];
                        l[r] = ms(() -> back[0] = F == 0 ? loadSer(ser) : F == 1 ? HostelFormats.loadCsv(csv, null) : HostelFormats.loadJson(json, null));
                        if (back[0].residentCount() != n) throw new IllegalStateException("lost residents");
                    }
                    long size = Files.size(f);
                    System.out.printf("%,-10d %-22s %,12d %10.1f %12.2f %12.2f%n", n, names[fmt], size, size / (double) n, med(s), med(l));
                    out.printf("%d,%s,%d,%.3f,%.3f%n", n, names[fmt], size, med(s), med(l));
                }
            }
        }

        System.out.println();
        System.out.println("=== A new field (phone) is added after files were saved ===");
        Hostel small = build(3);
        HostelFormats.saveCsv(small, csv); HostelFormats.saveJson(small, json);
        Map<String, String> phones = new LinkedHashMap<>();
        HostelFormats.loadCsv(csv, phones);
        System.out.println("old CSV (no phone column) read by the new reader  -> " + phones);
        phones.clear(); HostelFormats.loadJson(json, phones);
        System.out.println("old JSON (no phone key) read by the new reader    -> " + phones);
        Files.writeString(csv, "hostel,room,beds,name,reg_no,phone\nVivekananda Hostel,R00001,4,Resident 0,192300000,98400 12345\n");
        phones.clear(); HostelFormats.loadCsv(csv, phones);
        System.out.println("new CSV (phone column) read by the new reader     -> " + phones);
        String[] naive = Files.readAllLines(csv).get(1).split(",");
        System.out.println("new CSV read by an OLD positional reader (expects 5 fields) -> " + (naive.length == 5 ? "ok" : "sees " + naive.length + " fields: rejects the line, or silently ignores 'phone' if it only reads columns 0-4"));
        Files.writeString(json, "{\"hostel\":\"Vivekananda Hostel\",\"rooms\":[{\"number\":\"R00001\",\"beds\":4,\"residents\":[{\"name\":\"Resident 0\",\"regNo\":\"192300000\",\"phone\":\"98400 12345\"}]}]}");
        phones.clear(); HostelFormats.loadJson(json, phones);
        System.out.println("new JSON (phone key) read by the new reader       -> " + phones + "   (an old reader just ignores the unknown key)");
        System.out.println("(serialization: see VersionDemo - compatible only with a fixed serialVersionUID)");
        System.out.println("Raw data: data/a4_formats.csv");
    }

    static void saveSer(Hostel h, Path f) throws Exception {
        try (ObjectOutputStream o = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(f)))) { o.writeObject(h); }
    }
    static Hostel loadSer(Path f) throws Exception {
        try (ObjectInputStream i = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(f)))) { return (Hostel) i.readObject(); }
    }
}
