import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Task B2 - adversarial logins against the local TEST database (table users, created by sql/.../01_schema.sql).
 * Every attack is tried on all four login checks; the users table is restored at the end.
 */
public class InjectionLab {
    record Attack(String name, String id, String pwd, String reveals) { }

    interface Check { String run(Connection c, String id, String pwd) throws SQLException; }

    public static void main(String[] args) throws Exception {
        try (Connection con = Db.open()) {
            resetUsers(con);
            Attack[] attacks = {
                new Attack("A0 genuine login", "arun", "arun@123", "baseline: all four accept the right password"),
                new Attack("A1 wrong password", "arun", "guess", "baseline: all four reject it"),
                new Attack("A2 comment out the password", "admin' -- ", "anything", "input becomes SQL: -- turns the password test into a comment"),
                new Attack("A3 tautology in both fields", "x' OR '1'='1", "x' OR '1'='1", "a condition that is always true logs in as the FIRST user"),
                new Attack("A4 tautology in the password", "arun", "' OR ''='", "knowing only a user name is enough"),
                new Attack("A5 UNION - read another row", "nobody' UNION SELECT id, full_name, pwd, role FROM users WHERE role='ADMIN' -- ", "x", "the 'name' shown is the admin's PASSWORD: data theft"),
                new Attack("A6 stacked statement", "x'; UPDATE users SET full_name='HACKED' WHERE id='admin'; --", "x", "a second statement runs: data is CHANGED"),
            };
            String[] names = {"unsafe (concat)", "PreparedStatement", "escape quotes", "regex whitelist"};
            Check[] checks = {LoginCheck::unsafe, LoginCheck::safe, LoginCheck::escaped, LoginCheck::regex};
            for (Attack a : attacks) {
                System.out.println("=== " + a.name() + " ===");
                System.out.println("  id  = " + a.id());
                System.out.println("  pwd = " + a.pwd());
                System.out.println("  unsafe SQL text: SELECT * FROM users WHERE id='" + a.id() + "' AND pwd='" + a.pwd() + "'");
                for (int i = 0; i < checks.length; i++) {
                    resetUsers(con);
                    String result;
                    try {
                        String who = checks[i].run(con, a.id(), a.pwd());
                        result = who == null ? "REJECTED (invalid login)" : "LOGGED IN as '" + who + "'";
                    } catch (IllegalArgumentException e) { result = "REJECTED before the query (" + e.getMessage() + ")"; }
                    catch (SQLException e) { result = "ERROR " + e.getSQLState() + ": " + e.getMessage().replaceAll("\\s+", " ").replaceAll("^(.{70}).*", "$1..."); }
                    String admin = adminName(con);
                    System.out.printf("  %-18s -> %s%s%n", names[i], result, admin.equals("Transport Admin") ? "" : "   !! users.admin.full_name is now '" + admin + "'");
                }
                System.out.println("  reveals: " + a.reveals());
                System.out.println();
            }

            System.out.println("=== A7 escaping is not enough: backslash escapes (MySQL default; PostgreSQL with standard_conforming_strings=off) ===");
            try (Statement s = con.createStatement()) { s.executeUpdate("SET standard_conforming_strings = off"); s.executeUpdate("SET escape_string_warning = off"); }
            String id = "\\' OR 1=1 -- ";
            System.out.println("  id  = " + id + "      escaped -> " + id.replace("'", "''"));
            System.out.println("  SQL text: SELECT * FROM users WHERE id='" + id.replace("'", "''") + "' AND pwd='x'");
            for (int i = 0; i < checks.length; i++) {
                String result;
                try { String who = checks[i].run(con, id, "x"); result = who == null ? "REJECTED" : "LOGGED IN as '" + who + "'"; }
                catch (IllegalArgumentException e) { result = "REJECTED before the query (" + e.getMessage() + ")"; }
                catch (SQLException e) { result = "ERROR " + e.getSQLState(); }
                System.out.printf("  %-18s -> %s%n", names[i], result);
            }
            try (Statement s = con.createStatement()) { s.executeUpdate("SET standard_conforming_strings = on"); }
            System.out.println("  reveals: \\' is read as an escaped quote, so the added ' closes the string - escaping depends on server settings");
            System.out.println();

            System.out.println("=== A8 the regex is either too strict or too weak: genuine users with unusual passwords ===");
            try (Statement s = con.createStatement()) { s.executeUpdate("INSERT INTO users VALUES ('dsouza', 'D''Souza 2026', 'Priya D''Souza', 'STUDENT')"); }
            for (int i = 0; i < checks.length; i++) {
                String result;
                try { String who = checks[i].run(con, "dsouza", "D'Souza 2026"); result = who == null ? "REJECTED" : "LOGGED IN as '" + who + "'"; }
                catch (IllegalArgumentException e) { result = "REJECTED before the query (" + e.getMessage() + ")  <- a genuine user is locked out"; }
                catch (SQLException e) { result = "ERROR " + e.getSQLState() + ": " + e.getMessage().replaceAll("\\s+", " ").replaceAll("^(.{60}).*", "$1..."); }
                System.out.printf("  %-18s -> %s%n", names[i], result);
            }
            resetUsers(con);
            System.out.println();
            timing(con);
        }
    }

    static void resetUsers(Connection con) throws SQLException {
        try (Statement s = con.createStatement()) {
            s.executeUpdate("DELETE FROM users");
            s.executeUpdate("INSERT INTO users VALUES ('admin', 'Adm!n#2026', 'Transport Admin', 'ADMIN')");
            s.executeUpdate("INSERT INTO users VALUES ('counter1', 'Cntr1-pass', 'Counter Clerk 1', 'CLERK')");
            s.executeUpdate("INSERT INTO users VALUES ('arun', 'arun@123', 'Arun Kumar', 'STUDENT')");
        }
    }

    static String adminName(Connection con) throws SQLException {
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT full_name FROM users WHERE id = 'admin'")) { return rs.next() ? rs.getString(1) : "(deleted)"; }
    }

    /** Time per login check: validation/escaping cost vs the PreparedStatement. */
    static void timing(Connection con) throws Exception {
        System.out.println("=== Time per login check (2,000 genuine logins per round, 9 interleaved rounds, median) ===");
        int n = 2000;
        PreparedStatement reused = con.prepareStatement(LoginCheck.SAFE_SQL);
        Check[] cs = {LoginCheck::unsafe, LoginCheck::escaped, LoginCheck::regex, LoginCheck::safe, (c, i, p) -> LoginCheck.safe(reused, i, p)};
        String[] names = {"unsafe concatenation (Statement)", "escape quotes + Statement", "regex whitelist + Statement", "PreparedStatement, prepared per call", "PreparedStatement, prepared once"};
        for (int w = 0; w < 1000; w++) for (Check c : cs) c.run(con, "arun", "arun@123");
        double[] res = new double[cs.length];
        double[][] t = new double[cs.length][9];
        for (int r = 0; r < 9; r++)
            for (int j = 0; j < cs.length; j++) {
                int k = (j + r) % cs.length;                     // rotate the order every round
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) cs[k].run(con, "arun", "arun@123");
                t[k][r] = (System.nanoTime() - t0) / 1e3 / n;
            }
        for (int k = 0; k < cs.length; k++) {
            Arrays.sort(t[k]); res[k] = t[k][4];
            System.out.printf("  %-40s %8.1f us per login%n", names[k], res[k]);
        }
        long t0 = System.nanoTime(); int ok = 0;
        for (int i = 0; i < 1_000_000; i++) { if (LoginCheck.ID.matcher("arun").matches() && LoginCheck.PWD.matcher("arun@123").matches()) ok++; }
        double regexNs = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime(); int len = 0;
        for (int i = 0; i < 1_000_000; i++) len += "arun".replace("'", "''").length() + "arun@123".replace("'", "''").length();
        double escNs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("  validation alone: regex check %.0f ns, quote escaping %.0f ns per login (1,000,000 runs; %d/%d)%n", regexNs, escNs, ok, len);
        reused.close();
        Files.createDirectories(Path.of("data"));
        try (PrintWriter o = new PrintWriter("data/b2_login_timing.csv")) {
            o.println("method,us_per_login");
            for (int k = 0; k < cs.length; k++) o.printf("\"%s\",%.2f%n", names[k], res[k]);
            o.printf("regex check alone,%.4f%nquote escaping alone,%.4f%n", regexNs / 1000, escNs / 1000);
        }
    }
}
