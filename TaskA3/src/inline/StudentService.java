package inline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import model.Student;

/** Task A3 alternative - SQL written directly in the service class (safe PreparedStatements, but no DAO). */
public class StudentService {
    private final Connection con;
    public StudentService(Connection con) { this.con = con; }

    public void register(Student s) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO student (reg_no, name, dept, year, email) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, s.regNo()); ps.setString(2, s.name()); ps.setString(3, s.dept()); ps.setInt(4, s.year()); ps.setString(5, s.email());
            ps.executeUpdate();
        }
    }

    public Student show(String regNo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT reg_no, name, dept, year, email FROM student WHERE reg_no = ?")) {
            ps.setString(1, regNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Student(rs.getString("reg_no"), rs.getString("name"), rs.getString("dept"), rs.getInt("year"), rs.getString("email")) : null;
            }
        }
    }

    public List<Student> listDept(String dept) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT reg_no, name, dept, year, email FROM student WHERE dept = ? ORDER BY reg_no")) {
            ps.setString(1, dept);
            List<Student> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Student(rs.getString("reg_no"), rs.getString("name"), rs.getString("dept"), rs.getInt("year"), rs.getString("email")));
            }
            return out;
        }
    }

    public boolean changeEmail(String regNo, String email) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE student SET email = ? WHERE reg_no = ?")) {
            ps.setString(1, email); ps.setString(2, regNo);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean remove(String regNo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM student WHERE reg_no = ?")) {
            ps.setString(1, regNo);
            return ps.executeUpdate() == 1;
        }
    }
}
