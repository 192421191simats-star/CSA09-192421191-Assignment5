package minipg;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * java.sql.PreparedStatement as a server-side prepared statement: the SQL is parsed ONCE (Parse message),
 * then each execution only sends the parameter values (Bind + Execute). Values travel separately from
 * the SQL text, so they can never change the statement's structure.
 */
public class PgPreparedStatement extends PgStatement {
    record Converted(String sql, int params) { }
    static final int PIPELINE_CHUNK = 500;          // entries per round trip in executeBatch

    private final String name;
    private String pgSql;                                // non-null until the statement has been parsed by the server
    private final int nParams;
    private String[] values;
    private final List<String[]> batch = new ArrayList<>();
    private final boolean returnsRows;
    private PreparedStatement psProxy;
    private ResultSetMetaData describe;

    PgPreparedStatement(PgConnection con, String sql) throws SQLException {
        super(con);
        Converted c = toDollarParams(sql);
        nParams = c.params();
        values = new String[nParams];
        name = con.newStatementName();
        pgSql = c.sql();                                 // sent with the first execution (Parse + Bind + Execute in one round trip)
        String head = sql.stripLeading().toUpperCase();
        returnsRows = head.startsWith("SELECT") || head.startsWith("WITH") || head.startsWith("VALUES") || head.startsWith("SHOW") || head.contains(" RETURNING ");
    }

    PreparedStatement proxyPs() { if (psProxy == null) psProxy = Dispatch.proxy(PreparedStatement.class, this); return psProxy; }
    @Override protected java.sql.Statement self() { return proxyPs(); }

    /** JDBC '?' placeholders -> PostgreSQL $1..$n, ignoring '?' inside quotes and comments. */
    static Converted toDollarParams(String sql) {
        StringBuilder sb = new StringBuilder(sql.length() + 16);
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                int j = i + 1;
                while (j < sql.length()) { if (sql.charAt(j) == ch) { if (j + 1 < sql.length() && sql.charAt(j + 1) == ch) { j += 2; continue; } break; } j++; }
                sb.append(sql, i, Math.min(j + 1, sql.length())); i = j; continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int j = sql.indexOf('\n', i); if (j < 0) j = sql.length();
                sb.append(sql, i, j); i = j - 1; continue;
            }
            if (ch == '?') { sb.append('$').append(++n); continue; }
            sb.append(ch);
        }
        return new Converted(sb.toString(), n);
    }

    private void set(int i, String v) throws SQLException {
        ensureOpen();
        if (i < 1 || i > nParams) throw new SQLException("Parameter index " + i + " out of range 1.." + nParams, "07009");
        values[i - 1] = v;
    }
    public void setString(int i, String v) throws SQLException { set(i, v); }
    public void setInt(int i, int v) throws SQLException { set(i, Integer.toString(v)); }
    public void setLong(int i, long v) throws SQLException { set(i, Long.toString(v)); }
    public void setShort(int i, short v) throws SQLException { set(i, Short.toString(v)); }
    public void setDouble(int i, double v) throws SQLException {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new SQLException("Invalid double value " + v, "22003");
        set(i, Double.toString(v));
    }
    public void setFloat(int i, float v) throws SQLException { set(i, Float.toString(v)); }
    public void setBigDecimal(int i, BigDecimal v) throws SQLException { set(i, v == null ? null : v.toPlainString()); }
    public void setBoolean(int i, boolean v) throws SQLException { set(i, v ? "true" : "false"); }
    public void setDate(int i, Date v) throws SQLException { set(i, v == null ? null : v.toString()); }
    public void setTime(int i, Time v) throws SQLException { set(i, v == null ? null : v.toString()); }
    public void setTimestamp(int i, Timestamp v) throws SQLException { set(i, v == null ? null : v.toString()); }
    public void setNull(int i, int sqlType) throws SQLException { set(i, null); }
    public void setObject(int i, Object v) throws SQLException {
        if (v == null) set(i, null);
        else if (v instanceof java.time.LocalDate d) set(i, d.toString());
        else if (v instanceof java.time.LocalDateTime t) set(i, t.toString().replace('T', ' '));
        else set(i, v.toString());
    }
    public void setObject(int i, Object v, int type) throws SQLException { setObject(i, v); }
    public void clearParameters() { values = new String[nParams]; }

    public ResultSet executeQuery() throws SQLException {
        ensureOpen();
        take(exec(List.<String[]>of(values.clone()), true, false));
        if (current == null) throw new SQLException("PreparedStatement did not return a result set", "02000");
        return current;
    }
    public int executeUpdate() throws SQLException {
        ensureOpen();
        take(exec(List.<String[]>of(values.clone()), returnsRows, false));
        return (int) Math.max(updateCount, 0);
    }
    public long executeLargeUpdate() throws SQLException { executeUpdate(); return updateCount; }
    public boolean execute() throws SQLException {
        ensureOpen();
        take(exec(List.<String[]>of(values.clone()), returnsRows, false));
        return current != null;
    }

    public void addBatch() throws SQLException { ensureOpen(); batch.add(values.clone()); }
    @Override public void clearBatch() { batch.clear(); }
    @Override public int[] executeBatch() throws SQLException {
        ensureOpen();
        int[] counts = new int[batch.size()];
        int done = 0;
        try {
            for (int from = 0; from < batch.size(); from += PIPELINE_CHUNK) {
                List<String[]> chunk = batch.subList(from, Math.min(batch.size(), from + PIPELINE_CHUNK));
                try {
                    List<PgConnection.Result> r = exec(chunk, false, true);
                    for (PgConnection.Result x : r) counts[done++] = (int) x.updateCount;
                } catch (java.sql.BatchUpdateException e) {
                    int[] part = e.getUpdateCounts();
                    int[] all = Arrays.copyOf(counts, done + part.length);
                    System.arraycopy(part, 0, all, done, part.length);
                    throw new java.sql.BatchUpdateException(e.getMessage(), e.getSQLState(), 0, all, e.getCause());
                }
            }
            return counts;
        } finally { batch.clear(); }
    }

    private List<PgConnection.Result> exec(List<String[]> sets, boolean rows, boolean batch) throws SQLException {
        String parse = pgSql;
        try {
            return con.execute(name, parse, sets, rows, batch);
        } finally {
            if (parse != null && con.lastParseCompleted) pgSql = null;   // the server now holds the statement
        }
    }

    public ResultSetMetaData getMetaData() { return describe; }
    public java.sql.ParameterMetaData getParameterMetaData() throws SQLException { throw new java.sql.SQLFeatureNotSupportedException(); }

    @Override public void close() { if (!closed) { closed = true; con.closeStatement(name, pgSql == null); } }

    // Statement methods taking SQL are not allowed on a PreparedStatement
    @Override public ResultSet executeQuery(String sql) throws SQLException { throw new SQLException("Cannot call executeQuery(String) on a PreparedStatement", "HY000"); }
    @Override public int executeUpdate(String sql) throws SQLException { throw new SQLException("Cannot call executeUpdate(String) on a PreparedStatement", "HY000"); }
    @Override public boolean execute(String sql) throws SQLException { throw new SQLException("Cannot call execute(String) on a PreparedStatement", "HY000"); }
    @Override public void addBatch(String sql) throws SQLException { throw new SQLException("Cannot call addBatch(String) on a PreparedStatement", "HY000"); }
}
