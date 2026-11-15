package dao;

import java.util.List;
import model.Student;

/**
 * Task A3.2/A3.3 - Student <-> table student. This list is the ONLY place that names the columns.
 *   Student.regNo -> reg_no (PRIMARY KEY) | name -> name | dept -> dept | year -> year | email -> email
 * The row mapper reads by position, in the same order as the list.
 */
public class StudentDao extends JdbcDao<Student, String> {
    /** For the rename experiment, the e-mail column name can be supplied with -Dstudent.email.column=... */
    static final String EMAIL = System.getProperty("student.email.column", "email");

    static final List<Column<Student>> MAPPING = List.of(
            Column.key("reg_no", Student::regNo),
            Column.of("name", Student::name),
            Column.of("dept", Student::dept),
            Column.of("year", Student::year),
            Column.of(EMAIL, Student::email));

    public StudentDao(ConnectionSource src) {
        super(src, "student", MAPPING,
              rs -> new Student(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)),
              regNo -> new Object[]{regNo});
    }

    /** A query that is specific to students: one extra method, same machinery. */
    public List<Student> findByDept(String dept) throws java.sql.SQLException {
        return query(selectAllSql.replace(" ORDER BY", " WHERE dept = ? ORDER BY"), dept);
    }
}
