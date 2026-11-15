package model;

/** Composite primary key (reg_no, course_code). */
public record Marks(Key key, int mark) {
    public record Key(String regNo, String courseCode) { }
}
