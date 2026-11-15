package before;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import model.Student;

/** The student project as found (Task A3): every class repeats the same JDBC steps. */
public class StudentDB {
    private final String url, user, pwd;
    public StudentDB(String url, String user, String pwd) { this.url = url; this.user = user; this.pwd = pwd; }

    public Student find(String regNo) throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);                        // 1 open
        String sql = "SELECT reg_no, name, dept, year, email FROM student WHERE reg_no = '" + regNo + "'";   // 2 build SQL
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(sql);                                                  // 3 execute
        Student s = null;
        if (rs.next()) s = new Student(rs.getString("reg_no"), rs.getString("name"), rs.getString("dept"),
                                       rs.getInt("year"), rs.getString("email"));             // 4 map row -> object
        rs.close(); st.close(); con.close();                                                  // 5 close (skipped on an exception!)
        return s;
    }

    public List<Student> all() throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("SELECT reg_no, name, dept, year, email FROM student ORDER BY reg_no");
        List<Student> list = new ArrayList<>();
        while (rs.next()) list.add(new Student(rs.getString("reg_no"), rs.getString("name"), rs.getString("dept"),
                                               rs.getInt("year"), rs.getString("email")));
        rs.close(); st.close(); con.close();
        return list;
    }

    public int add(Student s) throws SQLException {
        Connection con = DriverManager.getConnection(url, user, pwd);
        Statement st = con.createStatement();
        int n = st.executeUpdate("INSERT INTO student (reg_no, name, dept, year, email) VALUES ('" + s.regNo() + "', '"
                + s.name() + "', '" + s.dept() + "', " + s.year() + ", '" + s.email() + "')");
        st.close(); con.close();
        return n;
    }
}
