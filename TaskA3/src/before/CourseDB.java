package before;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import model.Course;

/** Same five steps as StudentDB - only the table, columns and mapping differ. */
public class CourseDB {
    private final String url, user, pwd;
    public CourseDB(String url, String user, String pwd) { this.url = url; this.user = user; this.pwd = pwd; }

    public Course find(String code) throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        String sql = "SELECT course_code, title, credits FROM course WHERE course_code = '" + code + "'";
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(sql);
        Course c = null;
        if (rs.next()) c = new Course(rs.getString("course_code"), rs.getString("title"), rs.getInt("credits"));
        rs.close(); st.close(); con.close();
        return c;
    }

    public List<Course> all() throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("SELECT course_code, title, credits FROM course ORDER BY course_code");
        List<Course> list = new ArrayList<>();
        while (rs.next()) list.add(new Course(rs.getString("course_code"), rs.getString("title"), rs.getInt("credits")));
        rs.close(); st.close(); con.close();
        return list;
    }

    public int add(Course c) throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        Statement st = con.createStatement();
        int n = st.executeUpdate("INSERT INTO course (course_code, title, credits) VALUES ('" + c.courseCode() + "', '"
                + c.title() + "', " + c.credits() + ")");
        st.close(); con.close();
        return n;
    }
}
