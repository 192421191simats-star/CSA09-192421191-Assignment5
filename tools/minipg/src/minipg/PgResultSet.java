package minipg;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

/** Forward-only, read-only result set over rows already received in text format. */
public class PgResultSet {
    private final PgConnection.Result r;
    private final Statement stmt;
    private int row = -1;
    private boolean wasNull, closed;
    private ResultSet proxy;

    PgResultSet(PgConnection.Result r, Statement stmt) { this.r = r; this.stmt = stmt; }
    ResultSet proxy() { if (proxy == null) proxy = Dispatch.proxy(ResultSet.class, this); return proxy; }

    public boolean next() { if (row < r.rows.size()) row++; return row < r.rows.size(); }
    public void close() { closed = true; }
    public boolean isClosed() { return closed; }
    public boolean wasNull() { return wasNull; }
    public Statement getStatement() { return stmt; }
    public SQLWarning getWarnings() { return null; }
    public void clearWarnings() { }
    public int getRow() { return row + 1; }
    public boolean isBeforeFirst() { return row < 0 && !r.rows.isEmpty(); }
    public boolean isAfterLast() { return row >= r.rows.size() && !r.rows.isEmpty(); }
    public int getType() { return ResultSet.TYPE_FORWARD_ONLY; }
    public int getConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    public void setFetchSize(int n) { }
    public int getFetchSize() { return 0; }
    public ResultSetMetaData getMetaData() { return Dispatch.proxy(ResultSetMetaData.class, new PgResultSetMetaData(r)); }

    public int findColumn(String label) throws SQLException {
        for (int i = 0; i < r.names.length; i++) if (r.names[i].equalsIgnoreCase(label)) return i + 1;
        throw new SQLException("Column not found: " + label, "42703");
    }

    private String raw(int col) throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed", "HY010");
        if (row < 0 || row >= r.rows.size()) throw new SQLException("No current row", "24000");
        if (col < 1 || col > r.names.length) throw new SQLException("Column index " + col + " out of range", "07009");
        byte[] b = r.rows.get(row)[col - 1];
        wasNull = b == null;
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    public String getString(int c) throws SQLException { return raw(c); }
    public String getString(String l) throws SQLException { return raw(findColumn(l)); }
    public int getInt(int c) throws SQLException { String s = raw(c); return s == null ? 0 : (int) Double.parseDouble(s); }
    public int getInt(String l) throws SQLException { return getInt(findColumn(l)); }
    public long getLong(int c) throws SQLException { String s = raw(c); return s == null ? 0 : new BigDecimal(s).longValue(); }
    public long getLong(String l) throws SQLException { return getLong(findColumn(l)); }
    public short getShort(int c) throws SQLException { return (short) getInt(c); }
    public double getDouble(int c) throws SQLException { String s = raw(c); return s == null ? 0 : Double.parseDouble(s); }
    public double getDouble(String l) throws SQLException { return getDouble(findColumn(l)); }
    public float getFloat(int c) throws SQLException { return (float) getDouble(c); }
    public BigDecimal getBigDecimal(int c) throws SQLException { String s = raw(c); return s == null ? null : new BigDecimal(s); }
    public BigDecimal getBigDecimal(String l) throws SQLException { return getBigDecimal(findColumn(l)); }
    public boolean getBoolean(int c) throws SQLException { String s = raw(c); return s != null && (s.equals("t") || s.equalsIgnoreCase("true") || s.equals("1")); }
    public boolean getBoolean(String l) throws SQLException { return getBoolean(findColumn(l)); }
    public Date getDate(int c) throws SQLException { String s = raw(c); return s == null ? null : Date.valueOf(s.substring(0, 10)); }
    public Date getDate(String l) throws SQLException { return getDate(findColumn(l)); }
    public Timestamp getTimestamp(int c) throws SQLException { String s = raw(c); return s == null ? null : Timestamp.valueOf(s.length() == 10 ? s + " 00:00:00" : s); }
    public Timestamp getTimestamp(String l) throws SQLException { return getTimestamp(findColumn(l)); }
    public Object getObject(String l) throws SQLException { return getObject(findColumn(l)); }
    public Object getObject(int c) throws SQLException {
        String s = raw(c);
        if (s == null) return null;
        return switch (r.oids[c - 1]) {
            case 16 -> s.equals("t");
            case 20 -> Long.parseLong(s);
            case 21, 23 -> Integer.parseInt(s);
            case 700, 701 -> Double.parseDouble(s);
            case 1700 -> new BigDecimal(s);
            case 1082 -> Date.valueOf(s);
            case 1114 -> Timestamp.valueOf(s);
            default -> s;
        };
    }

    // ---------------------------------------------------------------- type names shared with metadata
    static String typeName(int oid) {
        return switch (oid) {
            case 16 -> "bool"; case 20 -> "int8"; case 21 -> "int2"; case 23 -> "int4"; case 25 -> "text";
            case 700 -> "float4"; case 701 -> "float8"; case 1042 -> "bpchar"; case 1043 -> "varchar";
            case 1082 -> "date"; case 1083 -> "time"; case 1114 -> "timestamp"; case 1184 -> "timestamptz";
            case 1700 -> "numeric"; case 19 -> "name"; case 18 -> "char"; case 26 -> "oid";
            default -> "oid:" + oid;
        };
    }
    static int sqlType(int oid) {
        return switch (oid) {
            case 16 -> Types.BOOLEAN; case 20 -> Types.BIGINT; case 21 -> Types.SMALLINT; case 23 -> Types.INTEGER;
            case 700 -> Types.REAL; case 701 -> Types.DOUBLE; case 1042 -> Types.CHAR; case 1043, 25, 19 -> Types.VARCHAR;
            case 1082 -> Types.DATE; case 1083 -> Types.TIME; case 1114 -> Types.TIMESTAMP; case 1184 -> Types.TIMESTAMP_WITH_TIMEZONE;
            case 1700 -> Types.NUMERIC; default -> Types.OTHER;
        };
    }
}
