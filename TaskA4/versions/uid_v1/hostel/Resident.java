package hostel;

import java.io.Serializable;
import java.sql.Connection;

/** Version 1 WITH an explicit serialVersionUID. */
public class Resident implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final String regNo;
    public transient String password;
    public transient Connection db;

    public Resident(String name, String regNo, String password, Connection db) { this.name = name; this.regNo = regNo; this.password = password; this.db = db; }
    @Override public String toString() { return name + " (" + regNo + ")"; }
}
