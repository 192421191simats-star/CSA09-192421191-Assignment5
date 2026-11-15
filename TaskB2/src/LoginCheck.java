import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Task A5.2 / B2 - four ways to check a login. Each returns the full name of the user who logged in, or null.
 *   unsafe   : the original - SQL text built by concatenation
 *   safe     : PreparedStatement - the SQL text is fixed; id and pwd are sent separately as VALUES
 *   escaped  : alternative 1 - double every ' in the input, then concatenate
 *   regex    : alternative 2 - reject input that does not match a whitelist pattern, then concatenate
 */
public final class LoginCheck {
    private LoginCheck() { }

    public static String unsafe(Connection con, String id, String pwd) throws SQLException {
        String sql = "SELECT * FROM users WHERE id='" + id + "' AND pwd='" + pwd + "'";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(3) : null;                  // column 3 = full_name in SELECT *
        }
    }

    static final String SAFE_SQL = "SELECT id, pwd, full_name, role FROM users WHERE id = ? AND pwd = ?";

    public static String safe(Connection con, String id, String pwd) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SAFE_SQL)) {
            return safe(ps, id, pwd);
        }
    }

    /** Same, re-using a statement prepared once (what a login screen should keep). */
    public static String safe(PreparedStatement ps, String id, String pwd) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, pwd);
        try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("full_name") : null; }
    }

    public static String escaped(Connection con, String id, String pwd) throws SQLException {
        return unsafe(con, id.replace("'", "''"), pwd.replace("'", "''"));
    }

    static final Pattern ID = Pattern.compile("[A-Za-z0-9_]{1,20}");
    static final Pattern PWD = Pattern.compile("[A-Za-z0-9@#!$%^&*_.\\-]{1,40}");      // no quotes, no spaces

    public static String regex(Connection con, String id, String pwd) throws SQLException {
        if (!ID.matcher(id).matches() || !PWD.matcher(pwd).matches()) throw new IllegalArgumentException("input rejected by the pattern");
        return unsafe(con, id, pwd);
    }
}
