package hostel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Root of the object graph that is saved. */
public class Hostel implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final List<Room> rooms = new ArrayList<>();
    public Resident warden;                     // the same Resident object is also inside a Room (shared reference)

    public Hostel(String name) { this.name = name; }

    public Room addRoom(String no, int beds) { Room r = new Room(no, beds, this); rooms.add(r); return r; }
    public int residentCount() { return rooms.stream().mapToInt(r -> r.residents.size()).sum(); }
}
