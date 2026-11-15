import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** The insert approaches compared in Task B3 (all insert the same five columns). */
public final class Approaches {
    static final String INSERT = "INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES (?, ?, ?, ?, ?)";
    private Approaches() { }

    /** (a) Statement: the SQL text is rebuilt and re-parsed for every row; quotes must be escaped by hand. */
    static void statement(Connection con, List<String[]> rows) throws SQLException {
        try (Statement st = con.createStatement()) {
            for (String[] p : rows)
                st.executeUpdate("INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES ('" + p[0] + "', '" + p[1] + "', '"
                        + p[2].replace("'", "''") + "', " + Integer.parseInt(p[3]) + ", " + Double.parseDouble(p[4]) + ")");
        }
    }

    /** (b) PreparedStatement: parsed once, one round trip per row. */
    static void prepared(Connection con, List<String[]> rows) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(INSERT)) {
            for (String[] p : rows) { bind(ps, p); ps.executeUpdate(); }
        }
    }

    /** (c) PreparedStatement + addBatch/executeBatch, b rows per round trip, auto-commit left as it is. */
    static void batch(Connection con, List<String[]> rows, int b) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(INSERT)) {
            int n = 0;
            for (String[] p : rows) { bind(ps, p); ps.addBatch(); if (++n % b == 0) ps.executeBatch(); }
            ps.executeBatch();
        }
    }

    static void bind(PreparedStatement ps, String[] p) throws SQLException {
        ps.setString(1, p[0]); ps.setString(2, p[1]); ps.setString(3, p[2]);
        ps.setInt(4, Integer.parseInt(p[3])); ps.setDouble(5, Double.parseDouble(p[4]));
    }

    /** Alternative 1: one multi-row INSERT ... VALUES (?,?,?,?,?), (?,?,?,?,?), ... per b rows. */
    static void multiRow(Connection con, List<String[]> rows, int b) throws SQLException {
        String full = multiSql(b);
        try (PreparedStatement ps = con.prepareStatement(full)) {
            for (int from = 0; from < rows.size(); from += b) {
                int k = Math.min(b, rows.size() - from);
                if (k < b) {
                    try (PreparedStatement last = con.prepareStatement(multiSql(k))) { fill(last, rows, from, k); last.executeUpdate(); }
                } else { fill(ps, rows, from, k); ps.executeUpdate(); }
            }
        }
    }
    static String multiSql(int k) {
        return "INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES " + "(?, ?, ?, ?, ?), ".repeat(k - 1) + "(?, ?, ?, ?, ?)";
    }
    static void fill(PreparedStatement ps, List<String[]> rows, int from, int k) throws SQLException {
        int c = 1;
        for (int i = from; i < from + k; i++) {
            String[] p = rows.get(i);
            ps.setString(c++, p[0]); ps.setString(c++, p[1]); ps.setString(c++, p[2]);
            ps.setInt(c++, Integer.parseInt(p[3])); ps.setDouble(c++, Double.parseDouble(p[4]));
        }
    }

    /** Alternative 2: bulk load - PostgreSQL COPY ... FROM STDIN, MySQL LOAD DATA LOCAL INFILE. Rows are streamed as CSV text. */
    static long bulkLoad(Connection con, List<String[]> rows, java.nio.file.Path tmpCsv) throws Exception {
        if (Db.isMySql()) {
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(tmpCsv); java.io.Reader r = new CsvRows(rows)) { r.transferTo(w); }
            try (Statement st = con.createStatement()) {
                return st.executeUpdate("LOAD DATA LOCAL INFILE '" + tmpCsv.toAbsolutePath().toString().replace("\\", "/") + "' INTO TABLE bus_pass "
                        + "FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"' LINES TERMINATED BY '\\n' (pass_id, reg_no, name, route_no, amount)");
            }
        }
        // PostgreSQL through the test driver's copyIn (reflection keeps this file compilable without that jar)
        Class<?> pg = Class.forName("minipg.PgConnection");
        Object raw = con.unwrap(pg);
        return (Long) pg.getMethod("copyIn", String.class, java.io.Reader.class)
                .invoke(raw, "COPY bus_pass(pass_id, reg_no, name, route_no, amount) FROM STDIN WITH (FORMAT csv)", new CsvRows(rows));
    }

    /** Produces the CSV text one row at a time, so a bulk load needs only a small buffer, not the whole file in memory. */
    static final class CsvRows extends java.io.Reader {
        private final List<String[]> rows; private int next; private String cur = ""; private int pos;
        CsvRows(List<String[]> rows) { this.rows = rows; }
        @Override public int read(char[] buf, int off, int len) {
            int n = 0;
            while (n < len) {
                if (pos == cur.length()) {
                    if (next == rows.size()) return n == 0 ? -1 : n;
                    String[] p = rows.get(next++);
                    cur = p[0] + "," + p[1] + ",\"" + p[2].replace("\"", "\"\"") + "\"," + p[3] + "," + p[4] + "\n"; pos = 0;
                }
                int k = Math.min(len - n, cur.length() - pos);
                cur.getChars(pos, pos + k, buf, off + n); pos += k; n += k;
            }
            return n;
        }
        @Override public void close() { }
    }
}
