import hostel.Hostel;
import hostel.Resident;
import hostel.Room;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task A4 alternative - the same hostel saved as CSV and as JSON. Only the persistent fields are written
 * (no password, no connection - "transient" is simply not writing them). Readers look fields up BY NAME,
 * so a missing column/key (old file) gets a default and an unknown one (newer file) is ignored.
 */
public final class HostelFormats {
    private HostelFormats() { }

    // ------------------------------------------------------------------ CSV
    static void saveCsv(Hostel h, Path f) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("hostel,room,beds,name,reg_no\n");
            for (Room r : h.rooms) for (Resident x : r.residents)
                w.write(csv(h.name) + "," + csv(r.number) + "," + r.beds + "," + csv(x.name) + "," + x.regNo + "\n");
        }
    }
    static String csv(String v) { return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v; }

    /** Header-driven reader. Returns the hostel; 'phones' receives the phone column when the file has one. */
    static Hostel loadCsv(Path f, Map<String, String> phones) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String[] head = r.readLine().split(",");
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < head.length; i++) col.put(head[i], i);
            Hostel h = null; Map<String, Room> rooms = new HashMap<>();
            String line;
            while ((line = r.readLine()) != null) {
                List<String> v = split(line);
                if (h == null) h = new Hostel(v.get(col.get("hostel")));
                final Hostel hh = h;
                Room room = rooms.computeIfAbsent(v.get(col.get("room")), k -> hh.addRoom(k, Integer.parseInt(v.get(col.get("beds")))));
                Resident res = new Resident(v.get(col.get("name")), v.get(col.get("reg_no")), null, null);
                room.residents.add(res);
                if (phones != null) phones.put(res.regNo, col.containsKey("phone") ? v.get(col.get("phone")) : "(not recorded)");
            }
            return h;
        }
    }
    static List<String> split(String line) {
        List<String> out = new java.util.ArrayList<>(6); StringBuilder b = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) { if (c == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { b.append('"'); i++; } else q = false; } else b.append(c); }
            else if (c == '"') q = true; else if (c == ',') { out.add(b.toString()); b.setLength(0); } else b.append(c);
        }
        out.add(b.toString());
        return out;
    }

    // ------------------------------------------------------------------ JSON
    static void saveJson(Hostel h, Path f) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("{\"hostel\":" + JsonMini.quote(h.name) + ",\"rooms\":[");
            for (int i = 0; i < h.rooms.size(); i++) {
                Room r = h.rooms.get(i);
                if (i > 0) w.write(",");
                w.write("{\"number\":" + JsonMini.quote(r.number) + ",\"beds\":" + r.beds + ",\"residents\":[");
                for (int j = 0; j < r.residents.size(); j++) {
                    Resident x = r.residents.get(j);
                    if (j > 0) w.write(",");
                    w.write("{\"name\":" + JsonMini.quote(x.name) + ",\"regNo\":" + JsonMini.quote(x.regNo) + "}");
                }
                w.write("]}");
            }
            w.write("]}");
        }
    }

    @SuppressWarnings("unchecked")
    static Hostel loadJson(Path f, Map<String, String> phones) throws IOException {
        Map<String, Object> root = (Map<String, Object>) JsonMini.parse(Files.readString(f, StandardCharsets.UTF_8));
        Hostel h = new Hostel((String) root.get("hostel"));
        for (Object ro : (List<Object>) root.get("rooms")) {
            Map<String, Object> rm = (Map<String, Object>) ro;
            Room room = h.addRoom((String) rm.get("number"), ((Number) rm.get("beds")).intValue());
            for (Object xo : (List<Object>) rm.get("residents")) {
                Map<String, Object> x = (Map<String, Object>) xo;
                Resident res = new Resident((String) x.get("name"), (String) x.get("regNo"), null, null);
                room.residents.add(res);
                if (phones != null) phones.put(res.regNo, (String) x.getOrDefault("phone", "(not recorded)"));
            }
        }
        return h;
    }
}
