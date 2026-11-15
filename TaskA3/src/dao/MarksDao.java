package dao;

import java.util.List;
import model.Marks;

/** Composite key: K = Marks.Key(regNo, courseCode). */
public class MarksDao extends JdbcDao<Marks, Marks.Key> {
    public MarksDao(ConnectionSource src) {
        super(src, "marks",
              List.of(Column.key("reg_no", m -> m.key().regNo()), Column.key("course_code", m -> m.key().courseCode()), Column.of("mark", Marks::mark)),
              rs -> new Marks(new Marks.Key(rs.getString(1), rs.getString(2)), rs.getInt(3)),
              k -> new Object[]{k.regNo(), k.courseCode()});
    }
}
