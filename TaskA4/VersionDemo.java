import hostel.Hostel;
import hostel.Resident;
import hostel.Room;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Task A4.2 - compiled four times against versions/<variant>/hostel/Resident.java.
 *   java -cp out_<variant> VersionDemo write <file>   |   java -cp out_<variant> VersionDemo read <file>
 */
public class VersionDemo {
    public static void main(String[] args) throws Exception {
        Path f = Path.of(args[1]);
        long uid = ObjectStreamClass.lookup(Resident.class).getSerialVersionUID();
        boolean hasPhone = java.util.Arrays.stream(Resident.class.getFields()).anyMatch(x -> x.getName().equals("phone"));
        String me = "Resident " + (hasPhone ? "v2 (with phone)" : "v1 (no phone)") + ", serialVersionUID = " + uid;
        if (args[0].equals("write")) {
            Hostel h = new Hostel("Vivekananda Hostel");
            Room r = h.addRoom("101", 2);
            r.residents.add(new Resident("Arun Kumar", "192311001", "pw", null));
            r.residents.add(new Resident("Divya R", "192311002", "pw", null));
            if (hasPhone) Resident.class.getField("phone").set(r.residents.get(0), "98400 12345");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(f))) { out.writeObject(h); }
            System.out.println("WRITE with " + me + " -> " + f.getFileName() + " (" + Files.size(f) + " bytes)");
        } else {
            System.out.print("READ  with " + me + " -> ");
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(f))) {
                Hostel h = (Hostel) in.readObject();
                System.out.println("OK: " + h.rooms.get(0).residents);
            } catch (InvalidClassException e) {
                System.out.println("FAILED: InvalidClassException: " + e.getMessage());
            }
        }
    }
}
