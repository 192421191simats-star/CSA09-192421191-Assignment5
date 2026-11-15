package hostel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Room implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String number;
    public final int beds;
    public final Hostel hostel;                 // back-reference: the graph has a cycle Hostel -> Room -> Hostel
    public final List<Resident> residents = new ArrayList<>();

    Room(String number, int beds, Hostel hostel) { this.number = number; this.beds = beds; this.hostel = hostel; }
}
