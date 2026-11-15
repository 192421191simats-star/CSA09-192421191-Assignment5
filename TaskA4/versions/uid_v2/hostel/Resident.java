package hostel;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.sql.Connection;

/** Version 2: phone added, serialVersionUID UNCHANGED (compatible change). */
public class Resident implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final String regNo;
    public String phone;                                   // NEW in version 2
    public transient String password;
    public transient Connection db;

    public Resident(String name, String regNo, String password, Connection db) { this.name = name; this.regNo = regNo; this.password = password; this.db = db; }

    /** Old files have no phone: defaultReadObject leaves it null, so give it a meaningful default. */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (phone == null) phone = "(not recorded)";
    }
    @Override public String toString() { return name + " (" + regNo + ", phone " + phone + ")"; }
}
