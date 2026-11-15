package hostel;

import java.io.Serializable;
import java.sql.Connection;

/** The WRONG design, for the experiment: nothing is transient. */
public class ResidentNoTransient implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name, regNo;
    public String password;
    public Connection db;

    public ResidentNoTransient(String name, String regNo, String password, Connection db) {
        this.name = name; this.regNo = regNo; this.password = password; this.db = db;
    }
}
