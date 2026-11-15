import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task B4.1 - migrates an existing bus-pass file (Task A1 text format) into the bus_pass table.
 *   - streams the file line by line (memory does not grow with the file, except the set of IDs seen)
 *   - validates every line with PassCsv (field count, types, ranges, CRC); bad lines go to a failure report
 *   - inserts in JDBC batches of 1,000 inside ONE transaction: all valid rows or none
 *   - can be re-run safely: rows already in the table are skipped (ON CONFLICT DO NOTHING / INSERT IGNORE)
 * Run: java -cp "out:../lib/*" MigratePasses <pass file> <campus 1-12> <failure report csv> [fresh]
 */
public class MigratePasses {
    public record Report(long lines, int valid, int inserted, int alreadyInDb, int duplicateInFile, int damaged, double ms) { }

    static String insertSql() {
        String cols = "(pass_id, reg_no, name, route_no, amount, boarding_stop, valid_until, campus) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        return Db.isMySql() ? "INSERT IGNORE INTO bus_pass " + cols : "INSERT INTO bus_pass " + cols + " ON CONFLICT (pass_id) DO NOTHING";
    }

    public static Report migrate(Connection con, Path file, int campus, Path failures, int batchSize) throws IOException, SQLException {
        long t0 = System.nanoTime();
        long lineNo = 0; int valid = 0, inserted = 0, inDb = 0, dupFile = 0, damaged = 0;
        Map<String, Long> seen = new HashMap<>();
        List<String[]> pending = new ArrayList<>(batchSize);            // {lineNo, passId} of the rows in the current batch
        boolean oldAuto = con.getAutoCommit();
        con.setAutoCommit(false);
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             PrintWriter rep = new PrintWriter(Files.newBufferedWriter(failures, StandardCharsets.UTF_8));
             PreparedStatement ps = con.prepareStatement(insertSql())) {
            rep.println("line,pass_id,reason,text");
            String line;
            while ((line = in.readLine()) != null) {
                lineNo++;
                if (line.startsWith("#")) continue;
                PassCsv.Pass p;
                try { p = PassCsv.decode(line); }
                catch (PassCsv.DamagedLineException e) { damaged++; report(rep, lineNo, PassCsv.idOf(line), e.getMessage(), line); continue; }
                Long first = seen.putIfAbsent(p.passId(), lineNo);
                if (first != null) { dupFile++; report(rep, lineNo, p.passId(), "duplicate pass_id in the file (first at line " + first + ")", line); continue; }
                valid++;
                ps.setString(1, p.passId()); ps.setString(2, p.regNo()); ps.setString(3, p.name()); ps.setInt(4, p.routeNo());
                ps.setBigDecimal(5, p.amount()); ps.setString(6, p.boardingStop()); ps.setDate(7, Date.valueOf(p.validUntil())); ps.setInt(8, campus);
                ps.addBatch();
                pending.add(new String[]{String.valueOf(lineNo), p.passId()});
                if (pending.size() == batchSize) { int[] r = flush(ps, pending, rep); inserted += r[0]; inDb += r[1]; }
            }
            int[] r = flush(ps, pending, rep); inserted += r[0]; inDb += r[1];
            con.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            con.rollback();                                                // nothing from this run stays in the table
            throw e;
        } finally {
            con.setAutoCommit(oldAuto);
        }
        return new Report(lineNo, valid, inserted, inDb, dupFile, damaged, (System.nanoTime() - t0) / 1e6);
    }

    /** Executes the batch; an update count of 0 means the row was already in the table (skipped). */
    static int[] flush(PreparedStatement ps, List<String[]> pending, PrintWriter rep) throws SQLException {
        if (pending.isEmpty()) return new int[]{0, 0};
        int[] counts;
        try { counts = ps.executeBatch(); }
        catch (BatchUpdateException e) { throw new SQLException("database rejected a row near line " + pending.get(Math.min(e.getUpdateCounts().length, pending.size() - 1))[0] + ": " + e.getMessage(), e.getSQLState(), e); }
        int ins = 0, skip = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) { skip++; rep.printf("%s,%s,already in the database - skipped,%n", pending.get(i)[0], pending.get(i)[1]); }
            else ins++;
        }
        pending.clear();
        return new int[]{ins, skip};
    }

    static void report(PrintWriter rep, long line, String id, String reason, String text) {
        String t = text.length() > 60 ? text.substring(0, 57) + "..." : text;
        rep.printf("%d,%s,\"%s\",\"%s\"%n", line, id.length() > 12 ? id.substring(0, 12) : id, reason.replace("\"", "'"), t.replace("\"", "\"\""));
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]); int campus = Integer.parseInt(args[1]); Path rep = Path.of(args[2]);
        Files.createDirectories(rep.toAbsolutePath().getParent());
        try (Connection con = Db.open()) {
            if (args.length > 3 && args[3].equals("fresh")) try (java.sql.Statement s = con.createStatement()) { s.executeUpdate("TRUNCATE TABLE bus_pass"); System.out.println("(bus_pass emptied first)"); }
            Report r = migrate(con, file, campus, rep, 1000);
            System.out.printf("Migrated %s (campus %d) in %.0f ms%n", file, campus, r.ms());
            System.out.printf("  lines read %,d | valid %,d | inserted %,d | already in database %,d | duplicate in file %,d | damaged %,d%n",
                    r.lines(), r.valid(), r.inserted(), r.alreadyInDb(), r.duplicateInFile(), r.damaged());
            System.out.printf("  check: valid = inserted + already in database ? %b%n", r.valid() == r.inserted() + r.alreadyInDb());
            List<String> lines = Files.readAllLines(rep);
            System.out.println("  failure report " + rep + " (" + (lines.size() - 1) + " entries; first 12 shown):");
            for (String l : lines.subList(0, Math.min(13, lines.size()))) System.out.println("    " + l);
        }
    }
}
