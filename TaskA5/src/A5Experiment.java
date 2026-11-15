import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Task A5 - transaction vs two auto-commit updates: failures, a real crash, timing and concurrency. */
public class A5Experiment {
    static final BigDecimal R500 = new BigDecimal("500");

    public static void main(String[] args) throws Exception {
        try (Connection con = Db.open()) {
            reset(con);
            show(con, "start");

            System.out.println("\n=== (1) Transaction, no failure ===");
            WalletService.transfer(con, "H-192311001", "C-192311001", R500, WalletService.NONE);
            show(con, "after transfer");

            System.out.println("\n=== (2) Transaction, exception thrown between the two updates ===");
            try { WalletService.transfer(con, "H-192311001", "C-192311001", R500, () -> { throw new IllegalStateException("simulated failure after the debit"); }); }
            catch (IllegalStateException e) { System.out.println("caught: " + e.getMessage() + " -> rollback()"); }
            show(con, "after the failed transfer");

            System.out.println("\n=== (3) Auto-commit, exception thrown between the two updates ===");
            try { WalletService.transferAutoCommit(con, "H-192311001", "C-192311001", R500, () -> { throw new IllegalStateException("simulated failure after the debit"); }); }
            catch (IllegalStateException e) { System.out.println("caught: " + e.getMessage() + " -> nothing to roll back, the debit is already committed"); }
            show(con, "after the failed transfer");

            System.out.println("\n=== (4) A real crash: a separate JVM halts between the two updates ===");
            reset(con); show(con, "start");
            child("transaction"); show(con, "after the crashed TRANSACTION");
            child("autocommit");  show(con, "after the crashed AUTO-COMMIT pair");

            System.out.println("\n=== (5) Insufficient funds ===");
            reset(con);
            try { WalletService.transfer(con, "H-192311001", "C-192311001", new BigDecimal("5000"), WalletService.NONE); }
            catch (WalletService.InsufficientFundsException e) { System.out.println("refused: " + e.getMessage()); }
            show(con, "unchanged");

            System.out.println("\n=== (6) Time per transfer (1,000 transfers back and forth, median of 5) ===");
            timing(con);

            System.out.println("\n=== (7) 4 threads x 250 random transfers between 10 wallets, 5% of them fail between the updates ===");
            concurrency("transaction");
            concurrency("autocommit");
        }
    }

    static void reset(Connection con) throws SQLException {
        try (Statement s = con.createStatement()) {
            s.executeUpdate("DELETE FROM wallet");
            s.executeUpdate("INSERT INTO wallet VALUES ('H-192311001', '192311001', 'HOSTEL', 2000.00)");
            s.executeUpdate("INSERT INTO wallet VALUES ('C-192311001', '192311001', 'CANTEEN', 500.00)");
        }
    }

    static BigDecimal total(Connection con) throws SQLException {
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT COALESCE(SUM(balance), 0) FROM wallet")) { rs.next(); return rs.getBigDecimal(1); }
    }

    static void show(Connection con, String label) throws SQLException {
        StringBuilder b = new StringBuilder();
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT wallet_id, balance FROM wallet WHERE wallet_id LIKE '%-192311001' ORDER BY wallet_id DESC")) {
            while (rs.next()) b.append(rs.getString(1)).append(" = Rs ").append(rs.getBigDecimal(2)).append("   ");
        }
        BigDecimal t = total(con);
        System.out.printf("  %-38s %s total Rs %s %s%n", label + ":", b, t, t.compareTo(new BigDecimal("2500")) == 0 ? "(conserved)" : "<-- MONEY " + (t.compareTo(new BigDecimal("2500")) < 0 ? "LOST" : "CREATED"));
    }

    static void child(String mode) throws Exception {
        String cp = System.getProperty("java.class.path");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", cp, "CrashChild", mode).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        int code = p.waitFor();
        System.out.println(out + "\n  child exit code " + code + " (" + mode + ")");
        Thread.sleep(300);                                  // let the server notice the dropped connection
    }

    static void timing(Connection con) throws Exception {
        reset(con);
        try (Statement s = con.createStatement()) { s.executeUpdate("UPDATE wallet SET balance = 100000"); }
        String a = "H-192311001", b = "C-192311001";
        for (int w = 0; w < 300; w++) { WalletService.transfer(con, a, b, BigDecimal.ONE, WalletService.NONE); WalletService.transferAutoCommit(con, b, a, BigDecimal.ONE, WalletService.NONE); }
        double[] tx = new double[5], ac = new double[5];
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 1000; i++) WalletService.transfer(con, i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, BigDecimal.ONE, WalletService.NONE);
            tx[r] = (System.nanoTime() - t0) / 1e3 / 1000;
            t0 = System.nanoTime();
            for (int i = 0; i < 1000; i++) WalletService.transferAutoCommit(con, i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, BigDecimal.ONE, WalletService.NONE);
            ac[r] = (System.nanoTime() - t0) / 1e3 / 1000;
        }
        Arrays.sort(tx); Arrays.sort(ac);
        System.out.printf("  one transaction (BEGIN, 2 UPDATEs, COMMIT = 1 commit)   %8.1f us per transfer%n", tx[2]);
        System.out.printf("  two auto-commit UPDATEs (2 commits)                     %8.1f us per transfer%n", ac[2]);
        Files.createDirectories(Path.of("data"));
        try (PrintWriter o = new PrintWriter("data/a5_timing.csv")) { o.println("mode,us_per_transfer"); o.printf("transaction,%.2f%nautocommit,%.2f%n", tx[2], ac[2]); }
        reset(con);
    }

    static void concurrency(String mode) throws Exception {
        try (Connection con = Db.open(); Statement s = con.createStatement()) {
            s.executeUpdate("DELETE FROM wallet");
            for (int i = 0; i < 10; i++) s.executeUpdate("INSERT INTO wallet VALUES ('W" + i + "', '19231100" + i + "', 'TEST', 1000.00)");
            BigDecimal before = total(con);
            AtomicInteger ok = new AtomicInteger(), failed = new AtomicInteger(), refused = new AtomicInteger(), retried = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                fs.add(pool.submit(() -> {
                    Random r = new Random(seed);
                    try (Connection c = Db.open()) {
                        for (int i = 0; i < 250; i++) {
                            String from = "W" + r.nextInt(10), to = "W" + r.nextInt(10);
                            if (from.equals(to)) continue;
                            BigDecimal amt = BigDecimal.valueOf(10 + r.nextInt(90));
                            boolean fail = r.nextInt(100) < 5;
                            WalletService.FailurePoint fp = fail ? () -> { throw new IllegalStateException("injected"); } : WalletService.NONE;
                            for (int attempt = 0; ; attempt++) {
                                try {
                                    if (mode.equals("transaction")) WalletService.transfer(c, from, to, amt, fp);
                                    else WalletService.transferAutoCommit(c, from, to, amt, fp);
                                    ok.incrementAndGet(); break;
                                } catch (IllegalStateException e) { failed.incrementAndGet(); break; }
                                catch (WalletService.InsufficientFundsException e) { refused.incrementAndGet(); break; }
                                catch (SQLException e) {
                                    if (("40P01".equals(e.getSQLState()) || "40001".equals(e.getSQLState())) && attempt < 3) { retried.incrementAndGet(); continue; }
                                    throw e;
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            pool.shutdown();
            BigDecimal after = total(con);
            System.out.printf("  %-12s completed %3d, failed mid-way %2d, refused (funds) %2d, deadlock retries %d | total before Rs %s, after Rs %s %s%n",
                    mode, ok.get(), failed.get(), refused.get(), retried.get(), before, after, before.compareTo(after) == 0 ? "(conserved)" : "<-- Rs " + before.subtract(after) + " LOST");
            s.executeUpdate("DELETE FROM wallet");
        }
    }
}
