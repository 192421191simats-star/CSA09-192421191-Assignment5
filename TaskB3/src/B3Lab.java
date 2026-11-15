import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Task B3 - Statement vs PreparedStatement vs batch on the bus_pass table.
 *   part 1 : correctness on 10 rows + ResultSetMetaData        part 2 : 1,000 / 10,000 / 100,000 rows
 *   part 3 : auto-commit vs one transaction, and a failure half-way
 *   part 4 : Complete the Code - PassLoader.loadAll            part 5 : multi-row INSERT and COPY / LOAD DATA
 * Run: java -Xmx2g -cp "out:../lib/*" B3Lab [quick]
 */
public class B3Lab {
    interface Load { void run(Connection c, List<String[]> rows) throws Exception; }
    static boolean quick;

    public static void main(String[] args) throws Exception {
        quick = args.length > 0 && args[0].equals("quick");
        Files.createDirectories(Path.of("data"));
        try (Connection con = Db.open()) {
            System.out.println("Database: " + con.getMetaData().getDatabaseProductName() + " " + con.getMetaData().getDatabaseProductVersion()
                    + "  |  driver: " + con.getMetaData().getDriverName() + " " + con.getMetaData().getDriverVersion());
            part1(con);
            part2(con);
            part3(con);
            part4(con);
            part5(con);
            truncate(con);
        }
    }

    static void truncate(Connection c) throws SQLException { try (Statement s = c.createStatement()) { s.executeUpdate("TRUNCATE TABLE bus_pass"); } }
    static int count(Connection c) throws SQLException { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM bus_pass")) { rs.next(); return rs.getInt(1); } }
    static String range(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*), MIN(pass_id), MAX(pass_id) FROM bus_pass")) {
            rs.next(); return rs.getInt(1) + " rows" + (rs.getInt(1) > 0 ? " (" + rs.getString(2) + " .. " + rs.getString(3) + ")" : "");
        }
    }

    // ------------------------------------------------------------------ B3.1
    static void part1(Connection con) throws Exception {
        System.out.println("\n=== B3.1 Correctness on 10 records ===");
        String[] names = {"(a) Statement", "(b) PreparedStatement", "(c) PreparedStatement batch"};
        Load[] loads = {Approaches::statement, Approaches::prepared, (c, r) -> Approaches.batch(c, r, 1000)};
        List<String[]> ten = Passes.generate(10, 900001);
        ten.get(3)[2] = "Priya D'Souza";                                      // an apostrophe in a name
        for (int a = 0; a < 3; a++) {
            truncate(con);
            loads[a].run(con, ten);
            int ok = 0;
            try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT pass_id, reg_no, name, route_no, amount FROM bus_pass ORDER BY pass_id")) {
                int i = 0;
                while (rs.next()) {
                    String[] p = ten.get(i++);
                    boolean same = rs.getString(1).equals(p[0]) && rs.getString(2).equals(p[1]) && rs.getString(3).equals(p[2])
                            && rs.getInt(4) == Integer.parseInt(p[3]) && rs.getBigDecimal(5).doubleValue() == Double.parseDouble(p[4]);
                    if (same) ok++;
                }
            }
            System.out.printf("%-30s inserted %d, SELECT returned %d, %d/10 rows identical to the input %s%n", names[a], 10, count(con), ok, ok == 10 ? "PASS" : "FAIL");
        }
        System.out.println("Table contents (SELECT * ... ORDER BY pass_id) after (c):");
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT pass_id, reg_no, name, route_no, amount, boarding_stop, valid_until FROM bus_pass ORDER BY pass_id")) {
            ResultSetMetaData md = rs.getMetaData();
            int[] w = {9, 11, 18, 10, 9, 15, 12};
            StringBuilder h = new StringBuilder("  ");
            for (int i = 1; i <= md.getColumnCount(); i++) h.append(String.format("%-" + w[i - 1] + "s", md.getColumnName(i)));
            System.out.println(h);
            while (rs.next()) {
                StringBuilder r = new StringBuilder("  ");
                for (int i = 1; i <= md.getColumnCount(); i++) r.append(String.format("%-" + w[i - 1] + "s", rs.getString(i)));
                System.out.println(r);
            }
            System.out.println("ResultSetMetaData:");
            System.out.printf("  %-3s %-14s %-11s %-14s %-10s %-6s %s%n", "#", "column", "type name", "java.sql.Types", "precision", "scale", "Java class");
            for (int i = 1; i <= md.getColumnCount(); i++)
                System.out.printf("  %-3d %-14s %-11s %-14s %-10d %-6d %s%n", i, md.getColumnName(i), md.getColumnTypeName(i), typeName(md.getColumnType(i)), md.getPrecision(i), md.getScale(i), md.getColumnClassName(i));
        }
    }

    static String typeName(int t) {
        for (var f : java.sql.Types.class.getFields()) try { if (f.getInt(null) == t) return f.getName(); } catch (IllegalAccessException e) { }
        return String.valueOf(t);
    }

    // ------------------------------------------------------------------ B3.2
    static double time(Connection con, Load l, List<String[]> rows, boolean inTx) throws Exception {
        truncate(con);
        long t0 = System.nanoTime();
        if (inTx) { con.setAutoCommit(false); l.run(con, rows); con.commit(); con.setAutoCommit(true); }
        else l.run(con, rows);
        double ms = (System.nanoTime() - t0) / 1e6;
        if (count(con) != rows.size()) throw new IllegalStateException("row count wrong");
        return ms;
    }

    static void part2(Connection con) throws Exception {
        System.out.println("\n=== B3.2 Insert time by size (auto-commit on, as a program would do by default) ===");
        int[] sizes = quick ? new int[]{1_000, 10_000} : new int[]{1_000, 10_000, 100_000};
        String[] names = {"(a) Statement", "(b) PreparedStatement", "(c) batch, 1,000 per executeBatch", "(a) Statement - inside 1 transaction", "(b) PreparedStatement - inside 1 transaction"};
        for (int w = 0; w < 2; w++) { time(con, Approaches::statement, Passes.generate(2000, 1), false); time(con, Approaches::prepared, Passes.generate(2000, 1), false); time(con, (c, r) -> Approaches.batch(c, r, 1000), Passes.generate(2000, 1), false); }
        try (PrintWriter csv = new PrintWriter("data/b3_insert_times.csv")) {
            csv.println("rows,approach,ms,rows_per_s");
            System.out.printf("%-46s %12s %12s %12s %16s%n", "approach", "1,000 rows", "10,000 rows", "100,000 rows", "us/row (largest)");
            for (int a = 0; a < names.length; a++) {
                StringBuilder line = new StringBuilder(String.format("%-46s", names[a]));
                double last = 0;
                for (int n : sizes) {
                    List<String[]> rows = Passes.generate(n, 100001);
                    int reps = n <= 10_000 ? 3 : 1;
                    double[] t = new double[reps];
                    for (int r = 0; r < reps; r++) {
                        t[r] = switch (a) {
                            case 0 -> time(con, Approaches::statement, rows, false);
                            case 1 -> time(con, Approaches::prepared, rows, false);
                            case 2 -> time(con, (c, x) -> Approaches.batch(c, x, 1000), rows, false);
                            case 3 -> time(con, Approaches::statement, rows, true);
                            default -> time(con, Approaches::prepared, rows, true);
                        };
                    }
                    Arrays.sort(t);
                    double ms = t[reps / 2];
                    line.append(String.format(" %9.0f ms", ms));
                    csv.printf("%d,\"%s\",%.2f,%.0f%n", n, names[a], ms, n / (ms / 1000));
                    last = ms * 1000 / n;
                }
                System.out.println(line + String.format(" %16.1f", last));
            }
        }
        System.out.println("(1,000 and 10,000: median of 3; 100,000: one run. Rows/s and per-row costs are in data/b3_insert_times.csv)");
    }

    // ------------------------------------------------------------------ B3.3
    static void batchAutoCommit(Connection c, List<String[]> rows) throws Exception { Approaches.batch(c, rows, 1000); }

    static void part3(Connection con) throws Exception {
        System.out.println("\n=== B3.3 Batch insert: auto-commit vs ONE transaction ===");
        try (PrintWriter csv = new PrintWriter("data/b3_autocommit_vs_tx.csv")) {
            csv.println("rows,mode,ms");
            for (int n : quick ? new int[]{10_000} : new int[]{10_000, 100_000}) {
                List<String[]> rows = Passes.generate(n, 100001);
                double[] ac = new double[3], tx = new double[3];
                for (int r = 0; r < 3; r++) {
                    ac[r] = time(con, B3Lab::batchAutoCommit, rows, false);
                    truncate(con);
                    long t0 = System.nanoTime(); PassLoader.loadAll(con, rows); tx[r] = (System.nanoTime() - t0) / 1e6;
                }
                Arrays.sort(ac); Arrays.sort(tx);
                System.out.printf("  %,7d rows: batch with auto-commit %8.0f ms | batch in one transaction (PassLoader) %8.0f ms%n", n, ac[1], tx[1]);
                csv.printf("%d,autocommit,%.1f%n%d,transaction,%.1f%n", n, ac[1], n, tx[1]);
            }
        }

        System.out.println("\n  Failure half-way: 10,000 passes, pass P105000 has amount -500 (violates CHECK amount >= 0 in the database)");
        List<String[]> rows = Passes.generate(10_000, 100001);
        rows.get(4999)[4] = "-500";
        truncate(con);
        try { batchAutoCommit(con, rows); System.out.println("  auto-commit batch: no error?"); }
        catch (BatchUpdateException e) {
            int failed = 0; for (int x : e.getUpdateCounts()) if (x == Statement.EXECUTE_FAILED) failed++;
            System.out.println("  auto-commit batch : BatchUpdateException - " + failed + " entry failed; table now holds " + range(con) + "  <- rows before the failure stay committed, later rows were never sent: a PARTIAL load");
        }
        truncate(con);
        try { PassLoader.loadAll(con, rows); System.out.println("  transaction: no error?"); }
        catch (SQLException e) { System.out.println("  one transaction   : " + e.getClass().getSimpleName() + " (SQLState " + e.getSQLState() + ") -> rollback; table now holds " + range(con)); }

        System.out.println("\n  Failure half-way in Java: pass P105000 has amount 'abc' (NumberFormatException before it is sent)");
        rows.get(4999)[4] = "abc";
        truncate(con);
        try { batchAutoCommit(con, rows); } catch (NumberFormatException e) { System.out.println("  auto-commit batch : NumberFormatException; table now holds " + range(con) + "  <- the 4 batches already sent are committed"); }
        truncate(con);
        try { PassLoader.loadAll(con, rows); } catch (NumberFormatException e) { System.out.println("  one transaction   : NumberFormatException -> rollback; table now holds " + range(con)); }
    }

    // ------------------------------------------------------------------ Complete the Code
    static void part4(Connection con) throws Exception {
        System.out.println("\n=== Complete the Code: PassLoader.loadAll (one transaction, batches of 1,000) ===");
        for (int n : quick ? new int[]{1_000, 10_000} : new int[]{1_000, 10_000, 100_000}) {
            truncate(con);
            List<String[]> rows = Passes.generate(n, 100001);
            long t0 = System.nanoTime();
            int loaded = PassLoader.loadAll(con, rows);
            System.out.printf("  loadAll(%,7d passes) returned %,7d in %7.0f ms; SELECT COUNT(*) = %,d ; auto-commit restored = %b%n", n, loaded, (System.nanoTime() - t0) / 1e6, count(con), con.getAutoCommit());
        }
        truncate(con);
        List<String[]> rows = Passes.generate(10_000, 100001);
        rows.get(5000)[4] = "-1.0";
        try { PassLoader.loadAll(con, rows); }
        catch (SQLException e) { System.out.println("  invalid amount (-1.0) at pass " + rows.get(5000)[0] + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage().replaceAll("^(.{100}).*", "$1..."));
                                 System.out.println("  rows left in bus_pass after the failure: " + count(con) + "   (expected: 0)"); }
    }

    // ------------------------------------------------------------------ alternative
    static long retainedBytes(Runnable fill) throws Exception {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(30); }
        long before = rt.totalMemory() - rt.freeMemory();
        fill.run();
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(30); }
        return Math.max(0, rt.totalMemory() - rt.freeMemory() - before);
    }

    static void part5(Connection con) throws Exception {
        System.out.println("\n=== Alternative: batch vs multi-row INSERT vs bulk load (COPY / LOAD DATA), 100,000 rows, one transaction ===");
        int n = quick ? 20_000 : 100_000;
        List<String[]> rows = Passes.generate(n, 100001);
        Path tmp = Files.createTempFile("passes", ".csv");
        String[] names = {"PreparedStatement batch, b = 100", "PreparedStatement batch, b = 1,000", "PreparedStatement batch, b = 10,000",
                          "multi-row INSERT, b = 100 rows/statement", "multi-row INSERT, b = 1,000 rows/statement",
                          Db.isMySql() ? "LOAD DATA LOCAL INFILE (streamed)" : "COPY FROM STDIN (streamed)"};
        Load[] loads = {(c, r) -> Approaches.batch(c, r, 100), (c, r) -> Approaches.batch(c, r, 1000), (c, r) -> Approaches.batch(c, r, 10_000),
                        (c, r) -> Approaches.multiRow(c, r, 100), (c, r) -> Approaches.multiRow(c, r, 1000), (c, r) -> Approaches.bulkLoad(c, r, tmp)};
        int[] b = {100, 1000, 10_000, 100, 1000, 0};
        try (PrintWriter csv = new PrintWriter("data/b3_alternatives.csv")) {
            csv.println("approach,rows,ms,rows_per_s,client_bytes_per_batch");
            System.out.printf("%-46s %10s %12s %22s%n", "approach", "ms", "rows/s", "client memory / batch");
            for (int a = 0; a < loads.length; a++) {
                time(con, loads[a], rows.subList(0, Math.min(5000, n)), true);                 // warm-up
                double[] t = new double[3];
                for (int r = 0; r < 3; r++) t[r] = time(con, loads[a], rows, true);
                Arrays.sort(t);
                long mem = memoryPerBatch(con, a, b[a], rows);
                System.out.printf("%-46s %10.0f %,12.0f %19.1f KB%n", names[a], t[1], n / (t[1] / 1000), mem / 1024.0);
                csv.printf("\"%s\",%d,%.1f,%.0f,%d%n", names[a], n, t[1], n / (t[1] / 1000), mem);
            }
        }
        Files.deleteIfExists(tmp);
        System.out.println("(median of 3; memory = heap retained by the client while one batch is waiting to be sent, after GC)");
    }

    /** Heap held by the client for ONE pending batch (b rows bound, not yet executed). */
    static long memoryPerBatch(Connection con, int approach, int b, List<String[]> rows) throws Exception {
        if (approach == 5) {   // streamed bulk load: the reader holds one row, the driver a 32 KB buffer
            Approaches.CsvRows[] keep = new Approaches.CsvRows[1];
            char[][] buf = new char[1][];
            return retainedBytes(() -> { keep[0] = new Approaches.CsvRows(rows); buf[0] = new char[32768]; }) + 0 * keep.length;
        }
        java.sql.PreparedStatement ps = con.prepareStatement(approach >= 3 ? Approaches.multiSql(b) : Approaches.INSERT);
        long bytes = retainedBytes(() -> {
            try {
                if (approach >= 3) Approaches.fill(ps, rows, 0, b);
                else for (int i = 0; i < b; i++) { Approaches.bind(ps, rows.get(i)); ps.addBatch(); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        ps.clearBatch(); ps.close();
        return bytes;
    }
}
