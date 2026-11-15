package dao;

import java.util.List;
import model.Course;

public class CourseDao extends JdbcDao<Course, String> {
    public CourseDao(ConnectionSource src) {
        super(src, "course",
              List.of(Column.key("course_code", Course::courseCode), Column.of("title", Course::title), Column.of("credits", Course::credits)),
              rs -> new Course(rs.getString(1), rs.getString(2), rs.getInt(3)),
              code -> new Object[]{code});
    }
}
