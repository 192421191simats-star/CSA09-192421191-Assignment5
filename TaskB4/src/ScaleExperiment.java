import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task B4.3 + alternative - 12 campuses, 100x today's passes (12 x 66,667 = 800,004 passes):
 *   (1) migrate 12 campus files into ONE database table          (2) search and list in the database
 *   (3) the same data as 12 serialized campus files: size, search  (4) several counters issuing passes at once
 * Run: java -Xmx3g -cp "out:../lib/*" ScaleExperiment [perCampus]
 */
public class ScaleExperiment {
    static final int CAMPUSES = 12;
    static double med(double[] a) { double[] c = a.clone(); Arrays.sort(c); return c[c.length / 2]; }

    public static void main(String[] args) throws Exception {
        int per = args.length > 0 ? Integer.parseInt(args[0]) : 66_667;
        Path work = Path.of("work"); Files.createDirectories(work); Files.createDirectories(Path.of("data"));
        try (Connection con = Db.open(); PrintWriter csv = new PrintWriter("data/b4_scale.csv")) {
            csv.println("metric,value,unit");
            try (Statement s = con.createStatement()) { s.executeUpdate("TRUNCATE TABLE bus_pass"); }

            System.out.printf("=== (1) Migrate 12 campus files of %,d passes each (about 1%% damaged) into one table ===%n", per);
            long okRows = 0, bad = 0; double migMs = 0;
            for (int c = 1; c <= CAMPUSES; c++) {
                Path f = work.resolve("campus" + c + ".csv");
                if (!Files.exists(f)) MakeExistingFile.main(new String[]{f.toString(), String.valueOf(per), String.valueOf(1_000_000 * c + 1), String.valueOf(c)});
                MigratePasses.Report r = MigratePasses.migrate(con, f, c, work.resolve("campus" + c + "_failures.csv"), 1000);
                okRows += r.inserted(); bad += r.damaged() + r.duplicateInFile(); migMs += r.ms();
            }
            System.out.printf("  inserted %,d rows, %,d lines reported as failed, total %.1f s = %,.0f rows/s (batches of 1,000, one transaction per campus file)%n", okRows, bad, migMs / 1000, okRows / (migMs / 1000));
            csv.printf("migration_rows,%d,rows%nmigration_s,%.2f,s%n", okRows, migMs / 1000);
            try (Statement s = con.createStatement()) { s.executeUpdate(Db.isMySql() ? "ANALYZE TABLE bus_pass" : "ANALYZE bus_pass"); }

            System.out.println("\n=== (2) Searching one shared database (" + String.format("%,d", okRows) + " rows) ===");
            Random rnd = new Random(1);
            List<String> ids = sampleIds(con, 200);
            double[] t = new double[200];
            try (PreparedStatement ps = con.prepareStatement("SELECT pass_id, name, route_no, campus FROM bus_pass WHERE pass_id = ?")) {
                for (int w = 0; w < 200; w++) { ps.setString(1, ids.get(w)); try (ResultSet rs = ps.executeQuery()) { rs.next(); } }
                for (int i = 0; i < 200; i++) { ps.setString(1, ids.get(rnd.nextInt(ids.size()))); long t0 = System.nanoTime(); try (ResultSet rs = ps.executeQuery()) { rs.next(); } t[i] = (System.nanoTime() - t0) / 1e3; }
            }
            double findDb = med(t);
            int routeRows = 0; double[] rt = new double[9];
            try (PreparedStatement ps = con.prepareStatement("SELECT pass_id, name, campus FROM bus_pass WHERE route_no = ? ORDER BY pass_id")) {
                for (int i = 0; i < 9; i++) { ps.setInt(1, 1 + i); long t0 = System.nanoTime(); routeRows = 0; try (ResultSet rs = ps.executeQuery()) { while (rs.next()) routeRows++; } rt[i] = (System.nanoTime() - t0) / 1e6; }
            }
            double routeDb = med(rt);
            long tableBytes = tableBytes(con);
            System.out.printf("  find one pass by pass_id (primary-key index, median of 200)  %8.1f us%n", findDb);
            System.out.printf("  list one route across all campuses (~%,d rows, median of 9)  %8.1f ms%n", routeRows, routeDb);
            System.out.printf("  table + indexes on disk                                      %8.1f MB%n", tableBytes / 1048576.0);
            csv.printf("db_find_us,%.1f,us%ndb_route_ms,%.1f,ms%ndb_bytes,%d,bytes%n", findDb, routeDb, tableBytes);

            System.out.println("\n=== (3) Alternative: every campus keeps its own serialized file ===");
            long serBytes = 0;
            for (int c = 1; c <= CAMPUSES; c++) {
                List<SerialPass> l = readCampus(work.resolve("campus" + c + ".csv"), c);
                save(l, work.resolve("campus" + c + ".ser"));
                serBytes += Files.size(work.resolve("campus" + c + ".ser"));
            }
            List<SerialPass> last = load(work.resolve("campus" + CAMPUSES + ".ser"));
            String target = last.get(last.size() / 2).passId();          // a campus-12 pass: a search must open all 12 files
            last = null;
            double[] cold = new double[3];
            for (int r = 0; r < 3; r++) {
                long t0 = System.nanoTime();
                SerialPass hit = null;
                for (int c = 1; c <= CAMPUSES && hit == null; c++) for (SerialPass p : load(work.resolve("campus" + c + ".ser"))) if (p.passId().equals(target)) { hit = p; break; }
                cold[r] = (System.nanoTime() - t0) / 1e6;
                if (hit == null) throw new IllegalStateException("not found");
            }
            List<SerialPass> all = new ArrayList<>();
            for (int c = 1; c <= CAMPUSES; c++) all.addAll(load(work.resolve("campus" + c + ".ser")));
            double[] warm = new double[21];
            Random pick = new Random(3);
            for (int r = 0; r < 21; r++) { String id = all.get(pick.nextInt(all.size())).passId(); long t0 = System.nanoTime(); for (SerialPass p : all) if (p.passId().equals(id)) break; warm[r] = (System.nanoTime() - t0) / 1e6; }
            double[] rw = new double[9];
            for (int r = 0; r < 9; r++) { long t0 = System.nanoTime(); int k = 0; for (SerialPass p : all) if (p.routeNo() == 1 + r) k++; rw[r] = (System.nanoTime() - t0) / 1e6; }
            System.out.printf("  12 files, total size                                          %8.1f MB%n", serBytes / 1048576.0);
            System.out.printf("  find one campus-12 pass: open + deserialize the files until found (median of 3) %8.0f ms%n", med(cold));
            System.out.printf("  find a random pass: all 12 lists already in one JVM's memory, scan (median of 21) %6.1f ms%n", med(warm));
            System.out.printf("  list one route: scan all lists in memory (median of 9)        %8.1f ms%n", med(rw));
            csv.printf("ser_bytes,%d,bytes%nser_find_cold_ms,%.1f,ms%nser_find_warm_ms,%.2f,ms%nser_route_ms,%.2f,ms%n", serBytes, med(cold), med(warm), med(rw));
            all = null;

            System.out.println("\n=== (4) Counters issuing passes at the same time ===");
            concurrentDb(csv);
            concurrentFiles(work, csv, false);
            concurrentFiles(work, csv, true);
        }
        System.out.println("Raw data: data/b4_scale.csv");
    }

    static List<String> sampleIds(Connection con, int n) throws SQLException {
        List<String> l = new ArrayList<>();
        String sql = Db.isMySql() ? "SELECT pass_id FROM bus_pass ORDER BY RAND() LIMIT " + n : "SELECT pass_id FROM bus_pass TABLESAMPLE SYSTEM (1) LIMIT " + n;
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) { while (rs.next()) l.add(rs.getString(1)); }
        return l;
    }

    static long tableBytes(Connection con) throws SQLException {
        String sql = Db.isMySql() ? "SELECT data_length + index_length FROM information_schema.tables WHERE table_name = 'bus_pass' AND table_schema = DATABASE()"
                                  : "SELECT pg_total_relation_size('bus_pass')";
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    static List<SerialPass> readCampus(Path csvFile, int campus) throws Exception {
        List<SerialPass> l = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(csvFile)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                try { PassCsv.Pass p = PassCsv.decode(line); l.add(new SerialPass(p.passId(), p.regNo(), p.name(), p.routeNo(), p.boardingStop(), p.validUntil(), p.amount(), campus)); }
                catch (PassCsv.DamagedLineException e) { /* reported by the migration */ }
            }
        }
        return l;
    }

    static void save(List<SerialPass> l, Path f) throws Exception {
        try (ObjectOutputStream o = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(f)))) { o.writeObject(new ArrayList<>(l)); }
    }
    @SuppressWarnings("unchecked")
    static List<SerialPass> load(Path f) throws Exception {
        try (ObjectInputStream i = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(f)))) { return (List<SerialPass>) i.readObject(); }
    }

    /** 12 counters (one per campus), 50 passes each, plus 2 counters racing for the SAME pass ID. */
    static void concurrentDb(PrintWriter csv) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CAMPUSES);
        AtomicInteger issued = new AtomicInteger(), rejected = new AtomicInteger();
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int c = 1; c <= CAMPUSES; c++) {
            final int campus = c;
            fs.add(pool.submit(() -> {
                try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO bus_pass (pass_id, reg_no, name, route_no, amount, boarding_stop, valid_until, campus) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (int i = 0; i < 52; i++) {
                        String id = i < 50 ? "Q" + campus + "-" + i : "RACE-1";                  // the last 2 inserts of every counter fight for one ID
                        if (i == 51) id = "RACE-2";
                        ps.setString(1, id); ps.setString(2, "192399999"); ps.setString(3, "Counter test"); ps.setInt(4, 1 + i % 40);
                        ps.setBigDecimal(5, new BigDecimal("3000")); ps.setString(6, "Main Gate"); ps.setObject(7, LocalDate.of(2027, 6, 30)); ps.setInt(8, campus);
                        try { ps.executeUpdate(); issued.incrementAndGet(); }
                        catch (SQLIntegrityConstraintViolationException e) { rejected.incrementAndGet(); }
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        double ms = (System.nanoTime() - t0) / 1e6;
        int stored;
        try (Connection con = Db.open(); Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM bus_pass WHERE pass_id LIKE 'Q%' OR pass_id LIKE 'RACE-%'")) { rs.next(); stored = rs.getInt(1); }
        System.out.printf("  database, 12 counters x 50 passes + 2 shared IDs each: issued %d, rejected as duplicates %d, rows stored %d (expected 602), %.0f ms, %.2f ms per pass%n",
                issued.get(), rejected.get(), stored, ms, ms / issued.get());
        csv.printf("db_concurrent_ms_per_pass,%.3f,ms%ndb_concurrent_lost,%d,passes%n", ms / issued.get(), 602 - stored);
        try (Connection con = Db.open(); Statement s = con.createStatement()) { s.executeUpdate("DELETE FROM bus_pass WHERE pass_id LIKE 'Q%' OR pass_id LIKE 'RACE-%'"); }
    }

    /**
     * 4 counters of campus 1 issue 10 passes each into the SAME serialized file: load the list, add, save.
     * locked: an exclusive FileLock (between processes) taken while holding a JVM monitor (FileLock is per JVM:
     * two threads of one JVM asking for it get OverlappingFileLockException instead of waiting).
     */
    static void concurrentFiles(Path work, PrintWriter csv, boolean locked) throws Exception {
        Path f = work.resolve("campus1.ser"), shared = work.resolve("campus1_shared.ser"), lock = work.resolve("campus1.lock");
        Files.copy(f, shared, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        int before = load(shared).size();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger errors = new AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<String, Integer> kinds = new java.util.concurrent.ConcurrentHashMap<>();
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            final int counter = k;
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    SerialPass p = new SerialPass("F" + counter + "-" + i, "192399999", "Counter test", 1, "Main Gate", LocalDate.of(2027, 6, 30), new BigDecimal("3000"), 1);
                    try {
                        if (locked) {
                            synchronized (ScaleExperiment.class) {
                                try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock l = ch.lock()) {
                                    List<SerialPass> list = load(shared); list.add(p); save(list, shared);
                                }
                            }
                        } else {
                            List<SerialPass> list = load(shared); list.add(p); save(list, shared);   // read - modify - write, no lock
                        }
                    } catch (Exception e) {                    // the clerk sees an error; the pass was not saved
                        errors.incrementAndGet();
                        kinds.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                    }
                }
                return null;
            }));
        }
        for (Future<?> fu : fs) fu.get();
        pool.shutdown();
        double ms = (System.nanoTime() - t0) / 1e6;
        int after;
        try { after = load(shared).size(); } catch (Exception e) { after = -1; }
        int kept = after < 0 ? 0 : after - before;
        String result = after < 0 ? "FILE UNREADABLE at the end" : String.format("%d of 40 passes in the file", kept);
        System.out.printf("  serialized file, 4 counters x 10 passes, %-9s: %s -> %d LOST; %d operations failed %s; %.0f ms = %.0f ms per pass (whole %,d-pass file rewritten each time)%n",
                locked ? "WITH lock" : "no lock", result, 40 - kept, errors.get(), kinds.isEmpty() ? "" : kinds.toString(), ms, ms / 40, before);
        csv.printf("file_%s_ms_per_pass,%.1f,ms%nfile_%s_lost,%d,passes%n", locked ? "locked" : "nolock", ms / 40, locked ? "locked" : "nolock", 40 - kept);
        Files.deleteIfExists(shared); Files.deleteIfExists(lock);
    }
}
