import hostel.Hostel;
import hostel.Resident;
import hostel.ResidentNoTransient;
import hostel.Room;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/** Task A4.1 - which fields are written, and what happens to the object graph. */
public class GraphDemo {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of("work"); Files.createDirectories(dir);
        try (Connection con = Db.open()) {
            System.out.println("=== (1) Wrong design: nothing transient ===");
            ResidentNoTransient bad = new ResidentNoTransient("Arun Kumar", "192311001", "Secret#2026", con);
            try { serialize(bad); System.out.println("serialized?!"); }
            catch (NotSerializableException e) { System.out.println("writeObject -> NotSerializableException: " + e.getMessage() + "   (the live Connection cannot be written)"); }
            bad.db = null;
            byte[] withPwd = serialize(bad);
            System.out.println("with db set to null it serializes (" + withPwd.length + " bytes) - and the file contains the password in clear: "
                    + contains(withPwd, "Secret#2026"));

            System.out.println();
            System.out.println("=== (2) Correct design: password and db are transient ===");
            Hostel h = new Hostel("Vivekananda Hostel");
            Room r101 = h.addRoom("101", 3), r102 = h.addRoom("102", 2);
            Resident a = new Resident("Arun Kumar", "192311001", "Secret#2026", con);
            Resident d = new Resident("Divya R", "192311002", "Dv@12345", con);
            Resident k = new Resident("Karthik S", "192311003", "K!pass99", con);
            r101.residents.add(a); r101.residents.add(d); r102.residents.add(k);
            h.warden = a;                                       // shared reference: warden is also in room 101
            Path f = dir.resolve("hostel.ser");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(f))) { out.writeObject(h); }
            byte[] bytes = Files.readAllBytes(f);
            System.out.println("writeObject(hostel) -> " + f + ", " + bytes.length + " bytes; contains a password? " + contains(bytes, "Secret#2026") + " / " + contains(bytes, "Dv@12345"));
            Hostel back;
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(f))) { back = (Hostel) in.readObject(); }
            Resident a2 = back.rooms.get(0).residents.get(0);
            System.out.println("readObject -> hostel '" + back.name + "', " + back.rooms.size() + " rooms, " + back.residentCount() + " residents");
            System.out.println("  every reachable object was saved (Hostel -> List -> Room -> List -> Resident) without listing them");
            System.out.println("  resident 1: name=" + a2.name + ", regNo=" + a2.regNo + ", password=" + a2.password + ", db=" + a2.db + "   <- transient fields come back as null");
            System.out.println("  shared reference kept: back.warden == room101.resident[0] ? " + (back.warden == a2) + "  (written once, the second use is a back-reference)");
            System.out.println("  cycle handled: back.rooms[0].hostel == back ? " + (back.rooms.get(0).hostel == back));
            System.out.println("  new objects: back == h ? " + (back == h) + " ; constructors were NOT run for Serializable classes");
            System.out.println("  after loading, the program must re-attach a Connection and ask for passwords again (or keep only a hash)");
        }
    }

    static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(b)) { out.writeObject(o); }
        return b.toByteArray();
    }

    static boolean contains(byte[] hay, String needle) {
        return new String(hay, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
