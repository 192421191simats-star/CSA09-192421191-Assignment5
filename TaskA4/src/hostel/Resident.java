package hostel;

import java.io.Serializable;
import java.sql.Connection;

/**
 * Task A4.1 - what is persisted:
 *   name, regNo        : data that must survive      -> serialized
 *   password           : secret; must never be written to a file in clear text   -> transient
 *   db (Connection)    : a live socket/session, meaningless after a restart and NOT Serializable -> transient
 */
public class Resident implements Serializable {
    private static final long serialVersionUID = 1L;    // fixed: the class can evolve compatibly (A4.2)
    public final String name;
    public final String regNo;
    public transient String password;
    public transient Connection db;

    public Resident(String name, String regNo, String password, Connection db) {
        this.name = name; this.regNo = regNo; this.password = password; this.db = db;
    }

    @Override public String toString() { return name + " (" + regNo + ")"; }
}
