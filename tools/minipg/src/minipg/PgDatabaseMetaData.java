package minipg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** The parts of DatabaseMetaData needed to inspect a server: product, driver, tables, columns, keys. */
public class PgDatabaseMetaData {
    private final PgConnection con;
    PgDatabaseMetaData(PgConnection con) { this.con = con; }

    public String getDatabaseProductName() { return "PostgreSQL"; }
    public String getDatabaseProductVersion() { return con.serverVersion(); }
    public int getDatabaseMajorVersion() { return Integer.parseInt(con.serverVersion().split("[^0-9]")[0]); }
    public int getDatabaseMinorVersion() { String[] p = con.serverVersion().split("[^0-9]+"); return p.length > 1 ? Integer.parseInt(p[1]) : 0; }
    public String getDriverName() { return "MiniPg JDBC (CSA09 test driver)"; }
    public String getDriverVersion() { return "0.1"; }
    public int getDriverMajorVersion() { return 0; }
    public int getDriverMinorVersion() { return 1; }
    public int getJDBCMajorVersion() { return 4; }
    public int getJDBCMinorVersion() { return 2; }
    public String getURL() { return con.url; }
    public String getUserName() { return con.user; }
    public Connection getConnection() { return con.proxy(); }
    public boolean supportsTransactions() { return true; }
    public boolean supportsBatchUpdates() { return true; }
    public boolean supportsSavepoints() { return false; }
    public boolean supportsTransactionIsolationLevel(int l) { return true; }
    public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_READ_COMMITTED; }
    public boolean supportsGetGeneratedKeys() { return false; }
    public String getIdentifierQuoteString() { return "\""; }
    public String getSQLKeywords() { return ""; }
    public boolean isReadOnly() { return false; }
    public boolean storesLowerCaseIdentifiers() { return true; }

    /** Standard JDBC columns: TABLE_CAT, TABLE_SCHEM, TABLE_NAME, TABLE_TYPE, REMARKS. */
    public ResultSet getTables(String catalog, String schemaPattern, String tablePattern, String[] types) throws SQLException {
        PreparedStatement ps = con.prepareStatement(
            "SELECT table_catalog AS \"TABLE_CAT\", table_schema AS \"TABLE_SCHEM\", table_name AS \"TABLE_NAME\", "
          + "CASE table_type WHEN 'BASE TABLE' THEN 'TABLE' ELSE table_type END AS \"TABLE_TYPE\", NULL AS \"REMARKS\" "
          + "FROM information_schema.tables WHERE table_schema LIKE ? AND table_name LIKE ? "
          + "AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY table_schema, table_name");
        ps.setString(1, schemaPattern == null ? "%" : schemaPattern);
        ps.setString(2, tablePattern == null ? "%" : tablePattern);
        return ps.executeQuery();
    }

    /** COLUMN_NAME, TYPE_NAME, COLUMN_SIZE, DECIMAL_DIGITS, IS_NULLABLE, ORDINAL_POSITION (subset of the JDBC columns). */
    public ResultSet getColumns(String catalog, String schemaPattern, String tablePattern, String columnPattern) throws SQLException {
        PreparedStatement ps = con.prepareStatement(
            "SELECT table_name AS \"TABLE_NAME\", column_name AS \"COLUMN_NAME\", udt_name AS \"TYPE_NAME\", "
          + "COALESCE(character_maximum_length, numeric_precision) AS \"COLUMN_SIZE\", numeric_scale AS \"DECIMAL_DIGITS\", "
          + "is_nullable AS \"IS_NULLABLE\", ordinal_position AS \"ORDINAL_POSITION\" "
          + "FROM information_schema.columns WHERE table_schema LIKE ? AND table_name LIKE ? AND column_name LIKE ? "
          + "AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY table_name, ordinal_position");
        ps.setString(1, schemaPattern == null ? "%" : schemaPattern);
        ps.setString(2, tablePattern == null ? "%" : tablePattern);
        ps.setString(3, columnPattern == null ? "%" : columnPattern);
        return ps.executeQuery();
    }

    /** TABLE_NAME, COLUMN_NAME, KEY_SEQ, PK_NAME. */
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        PreparedStatement ps = con.prepareStatement(
            "SELECT k.table_name AS \"TABLE_NAME\", k.column_name AS \"COLUMN_NAME\", k.ordinal_position AS \"KEY_SEQ\", "
          + "k.constraint_name AS \"PK_NAME\" FROM information_schema.table_constraints t "
          + "JOIN information_schema.key_column_usage k ON k.constraint_name = t.constraint_name AND k.table_schema = t.table_schema "
          + "WHERE t.constraint_type = 'PRIMARY KEY' AND k.table_name = ? ORDER BY k.ordinal_position");
        ps.setString(1, table);
        return ps.executeQuery();
    }
}
