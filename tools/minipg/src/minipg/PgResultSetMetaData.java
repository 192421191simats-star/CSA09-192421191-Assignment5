package minipg;

import java.sql.ResultSetMetaData;

/** Column names, types, sizes from the RowDescription message. */
public class PgResultSetMetaData {
    private final PgConnection.Result r;
    PgResultSetMetaData(PgConnection.Result r) { this.r = r; }

    public int getColumnCount() { return r.names.length; }
    public String getColumnName(int c) { return r.names[c - 1]; }
    public String getColumnLabel(int c) { return r.names[c - 1]; }
    public String getColumnTypeName(int c) { return PgResultSet.typeName(r.oids[c - 1]); }
    public int getColumnType(int c) { return PgResultSet.sqlType(r.oids[c - 1]); }
    public String getColumnClassName(int c) {
        return switch (r.oids[c - 1]) {
            case 16 -> "java.lang.Boolean"; case 20 -> "java.lang.Long"; case 21, 23 -> "java.lang.Integer";
            case 700, 701 -> "java.lang.Double"; case 1700 -> "java.math.BigDecimal"; case 1082 -> "java.sql.Date";
            case 1114 -> "java.sql.Timestamp"; default -> "java.lang.String";
        };
    }
    /** varchar(n)/bpchar(n): n; numeric(p,s): p; integers: digits. */
    public int getPrecision(int c) {
        int oid = r.oids[c - 1], mod = r.typmods[c - 1];
        return switch (oid) {
            case 1043, 1042 -> mod > 4 ? mod - 4 : 0;
            case 1700 -> mod > 4 ? ((mod - 4) >> 16) & 0xffff : 0;
            case 21 -> 5; case 23 -> 10; case 20 -> 19; case 701 -> 17; case 700 -> 8; case 1082 -> 10; case 1114 -> 26;
            default -> 0;
        };
    }
    public int getScale(int c) { return r.oids[c - 1] == 1700 && r.typmods[c - 1] > 4 ? (r.typmods[c - 1] - 4) & 0xffff : 0; }
    public int getColumnDisplaySize(int c) { int p = getPrecision(c); return p > 0 ? p + (r.oids[c - 1] == 1700 ? 2 : 0) : 32; }
    public int isNullable(int c) { return ResultSetMetaData.columnNullableUnknown; }
    public boolean isSigned(int c) { int o = r.oids[c - 1]; return o == 20 || o == 21 || o == 23 || o == 700 || o == 701 || o == 1700; }
    public boolean isAutoIncrement(int c) { return false; }
    public boolean isCaseSensitive(int c) { return true; }
    public boolean isSearchable(int c) { return true; }
    public boolean isCurrency(int c) { return false; }
    public boolean isReadOnly(int c) { return false; }
    public boolean isWritable(int c) { return true; }
    public boolean isDefinitelyWritable(int c) { return false; }
    public String getTableName(int c) { return ""; }
    public String getSchemaName(int c) { return ""; }
    public String getCatalogName(int c) { return ""; }
}
