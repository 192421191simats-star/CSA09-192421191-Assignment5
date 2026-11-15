package before;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import model.Marks;

/** Same five steps again, for the marks table (composite key). */
public class MarksDB {
    private final String url, user, pwd;
    public MarksDB(String url, String user, String pwd) { this.url = url; this.user = user; this.pwd = pwd; }

    public Marks find(String regNo, String code) throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        String sql = "SELECT reg_no, course_code, mark FROM marks WHERE reg_no = '" + regNo + "' AND course_code = '" + code + "'";
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(sql);
        Marks m = null;
        if (rs.next()) m = new Marks(new Marks.Key(rs.getString("reg_no"), rs.getString("course_code")), rs.getInt("mark"));
        rs.close(); st.close(); con.close();
        return m;
    }

    public List<Marks> all() throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("SELECT reg_no, course_code, mark FROM marks ORDER BY reg_no, course_code");
        List<Marks> list = new ArrayList<>();
        while (rs.next()) list.add(new Marks(new Marks.Key(rs.getString("reg_no"), rs.getString("course_code")), rs.getInt("mark")));
        rs.close(); st.close(); con.close();
        return list;
    }

    public int add(Marks m) throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        Statement st = con.createStatement();
        int n = st.executeUpdate("INSERT INTO marks (reg_no, course_code, mark) VALUES ('" + m.key().regNo() + "', '"
                + m.key().courseCode() + "', " + m.mark() + ")");
        st.close(); con.close();
        return n;
    }
}
