package model;

/** A student - maps to table student (see StudentDao.MAPPING). */
public record Student(String regNo, String name, String dept, int year, String email) { }
