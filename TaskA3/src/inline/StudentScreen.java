package inline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Task A3 alternative - a "GUI" class that also talks to the database directly (console stand-in for a Swing screen). */
public class StudentScreen {
    private final Connection con;
    public StudentScreen(Connection con) { this.con = con; }

    public String profileCard(String regNo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT name, dept, year, email FROM student WHERE reg_no = ?")) {
            ps.setString(1, regNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") + " | " + rs.getString("dept") + " yr " + rs.getInt("year") + " | " + rs.getString("email") : "(none)";
            }
        }
    }

    public String whoOwnsEmail(String email) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT reg_no FROM student WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : "(none)"; }
        }
    }

    public int missingEmailCount() throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM student WHERE email IS NULL OR email = ''");
             ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getInt(1);
        }
    }
}
