import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/** Task B4.2 - what server, which driver, which tables: checked before the application trusts a new server. */
public class InspectDatabase {
    public static void main(String[] args) throws Exception {
        try (Connection con = Db.open()) {
            DatabaseMetaData md = con.getMetaData();
            System.out.println("=== DatabaseMetaData ===");
            System.out.println("Database product     : " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            System.out.println("Database version     : " + md.getDatabaseMajorVersion() + "." + md.getDatabaseMinorVersion());
            System.out.println("JDBC driver          : " + md.getDriverName() + " " + md.getDriverVersion());
            System.out.println("JDBC API version     : " + md.getJDBCMajorVersion() + "." + md.getJDBCMinorVersion());
            System.out.println("URL / user           : " + md.getURL() + " / " + md.getUserName());
            System.out.println("Transactions         : " + md.supportsTransactions() + " (default isolation " + iso(md.getDefaultTransactionIsolation()) + ")");
            System.out.println("Batch updates        : " + md.supportsBatchUpdates());
            System.out.println("Identifier quote     : " + md.getIdentifierQuoteString());
            System.out.println();
            System.out.println("Tables:");
            try (ResultSet rs = md.getTables(null, Db.isMySql() ? null : "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) System.out.printf("  %-12s %s%n", rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"));
            }
            System.out.println();
            System.out.println("Columns of bus_pass:");
            try (ResultSet rs = md.getColumns(null, null, "bus_pass", "%")) {
                while (rs.next()) System.out.printf("  %-14s %-10s size %-5s nullable %s%n", rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getString("COLUMN_SIZE"), rs.getString("IS_NULLABLE"));
            }
            try (ResultSet rs = md.getPrimaryKeys(null, null, "bus_pass")) {
                while (rs.next()) System.out.println("Primary key of bus_pass: " + rs.getString("COLUMN_NAME") + " (" + rs.getString("PK_NAME") + ")");
            }
            String[] needed = {"bus_pass", "users", "wallet", "student", "course", "marks"};
            int missing = 0;
            for (String t : needed) try (ResultSet rs = md.getTables(null, null, t, null)) { if (!rs.next()) { missing++; System.out.println("MISSING table: " + t); } }
            System.out.println(missing == 0 ? "Start-up check: all " + needed.length + " tables the application needs are present." : "Start-up check FAILED: run the schema script first.");
        }
    }

    static String iso(int l) {
        return switch (l) { case Connection.TRANSACTION_READ_COMMITTED -> "READ COMMITTED"; case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE"; case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED"; default -> "NONE"; };
    }
}
