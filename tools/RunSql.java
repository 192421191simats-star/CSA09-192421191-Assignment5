import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Executes a .sql script through JDBC (statements end with ';' at the end of a line; '--' lines are comments).
 * Used by run_all.sh to (re)create the test schema on PostgreSQL or MySQL without a command-line client.
 * Run: java -cp "tools/out:lib/*" RunSql sql/postgresql/01_schema.sql
 */
public class RunSql {
    public static void main(String[] args) throws Exception {
        String text = Files.readString(Path.of(args[0]));
        StringBuilder cur = new StringBuilder();
        int n = 0;
        try (Connection con = Db.open(); Statement st = con.createStatement()) {
            for (String line : text.split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("--")) continue;
                cur.append(line).append('\n');
                if (t.endsWith(";")) {
                    String sql = cur.toString().strip();
                    st.execute(sql.substring(0, sql.length() - 1));
                    n++;
                    cur.setLength(0);
                }
            }
        }
        System.out.println("RunSql: executed " + n + " statements from " + args[0] + " on " + Db.url());
    }
}
