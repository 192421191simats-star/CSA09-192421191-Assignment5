package minipg;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** java.sql.Statement over the simple query protocol: the SQL text is sent and parsed by the server every time. */
public class PgStatement {
    final PgConnection con;
    private Statement proxy;
    protected ResultSet current;
    protected long updateCount = -1;
    protected boolean closed;
    private final List<String> batch = new ArrayList<>();
    private int maxRows, fetchSize, queryTimeout;

    PgStatement(PgConnection con) { this.con = con; }
    Statement proxy() { if (proxy == null) proxy = Dispatch.proxy(Statement.class, this); return proxy; }
    protected Statement self() { return proxy(); }

    protected void ensureOpen() throws SQLException { if (closed) throw new SQLException("Statement is closed", "HY010"); }

    protected void take(List<PgConnection.Result> rs) {
        current = null; updateCount = -1;
        if (rs.isEmpty()) { updateCount = 0; return; }
        PgConnection.Result r = rs.get(rs.size() - 1);
        if (r.rows != null) current = new PgResultSet(r, self()).proxy();
        else updateCount = r.updateCount;
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        ensureOpen();
        take(con.simpleQuery(sql));
        if (current == null) throw new SQLException("Statement did not return a result set: " + sql, "02000");
        return current;
    }
    public int executeUpdate(String sql) throws SQLException {
        ensureOpen();
        take(con.simpleQuery(sql));
        if (current != null) throw new SQLException("executeUpdate returned a result set", "0100E");
        return (int) Math.max(updateCount, 0);
    }
    public int executeUpdate(String sql, int keys) throws SQLException { return executeUpdate(sql); }
    public long executeLargeUpdate(String sql) throws SQLException { executeUpdate(sql); return updateCount; }
    public boolean execute(String sql) throws SQLException { ensureOpen(); take(con.simpleQuery(sql)); return current != null; }
    public ResultSet getResultSet() { return current; }
    public int getUpdateCount() { return current != null ? -1 : (int) updateCount; }
    public boolean getMoreResults() { current = null; updateCount = -1; return false; }

    public void addBatch(String sql) throws SQLException { ensureOpen(); batch.add(sql); }
    public void clearBatch() { batch.clear(); }
    public int[] executeBatch() throws SQLException {
        ensureOpen();
        int[] counts = new int[batch.size()];
        try {
            for (int i = 0; i < counts.length; i++) {
                List<PgConnection.Result> r = con.simpleQuery(batch.get(i));
                counts[i] = r.isEmpty() ? 0 : (int) Math.max(r.get(r.size() - 1).updateCount, 0);
            }
            return counts;
        } finally { batch.clear(); }
    }

    public void close() { closed = true; }
    public boolean isClosed() { return closed; }
    public java.sql.Connection getConnection() { return con.proxy(); }
    public SQLWarning getWarnings() { return null; }
    public void clearWarnings() { }
    public void setMaxRows(int n) { maxRows = n; }
    public int getMaxRows() { return maxRows; }
    public void setFetchSize(int n) { fetchSize = n; }
    public int getFetchSize() { return fetchSize; }
    public void setQueryTimeout(int s) { queryTimeout = s; }
    public int getQueryTimeout() { return queryTimeout; }
    public void setEscapeProcessing(boolean b) { }
    public void closeOnCompletion() { }
    public boolean isCloseOnCompletion() { return false; }
    public ResultSet getGeneratedKeys() throws SQLException { throw new java.sql.SQLFeatureNotSupportedException("use INSERT ... RETURNING"); }
    public int getResultSetType() { return ResultSet.TYPE_FORWARD_ONLY; }
    public int getResultSetConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
}
