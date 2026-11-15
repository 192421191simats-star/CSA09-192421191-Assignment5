import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Task B2 - how each file store responds to the adversarial files in input/. */
public class DamagedFileLab {
    public static void main(String[] args) throws Exception {
        System.out.println("=== (1) The A1-design store (PassFileStore) on input/damaged_passes.csv ===");
        Path f = Path.of("input/damaged_passes.csv");
        PassFileStore s = new PassFileStore(f);
        PassFileStore.ScanResult all = s.loadAll();
        System.out.println("valid records (" + all.passes().size() + "):");
        for (PassCsv.Pass p : all.passes()) System.out.printf("  %-6s %-34s route %2d  Rs %s%n", p.passId(), p.name(), p.routeNo(), p.amount());
        System.out.println("damaged lines (" + all.damaged().size() + ") - reported, skipped, the scan continues:");
        for (PassFileStore.Damaged d : all.damaged()) {
            String t = d.text().length() > 46 ? d.text().substring(0, 43) + "..." : d.text();
            System.out.printf("  line %2d  %-48s %s%n", d.lineNo(), "'" + t + "'", d.reason().length() > 70 ? d.reason().substring(0, 67) + "..." : d.reason());
        }
        List<PassFileStore.Damaged> why = new ArrayList<>();
        System.out.println("find(P2012) after all the damage -> " + s.find("P2012").map(p -> p.passId() + " " + p.name()).orElse("not found"));
        System.out.println("find(P2003) (amount '45OO.00')   -> " + s.find("P2003", why).map(PassCsv.Pass::passId).orElse("not found") + "  reason: " + why.get(0).reason());
        System.out.println("find(P2001) (ID appears twice)   -> " + s.find("P2001").map(p -> p.name()).orElse("not found") + "  (first valid record wins - duplicates must be prevented when adding, see B4)");
        System.out.println("P2009's hostile name is just text in a file - it cannot run anywhere unless a program concatenates it into SQL (B4 uses PreparedStatement)");

        System.out.println();
        System.out.println("=== (2) The given PassStore (Complete the Code, 5 fields) on input/damaged_passes_5field.csv ===");
        Path tmp = Files.createTempFile("p5", ".csv");
        Files.copy(Path.of("input/damaged_passes_5field.csv"), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        PassStore old = new PassStore(tmp);
        for (String id : new String[]{"P2001", "P2002", "P2003", "P2004", "P2005", "P2008", "P2012"}) {
            String[] r = old.find(id);
            String verdict = switch (id) {
                case "P2002" -> "damaged line skipped - correct";
                case "P2003", "P2004", "P2005" -> r != null ? "ACCEPTED although invalid: only the field COUNT is checked" : "skipped";
                case "P2008" -> r == null ? "a GENUINE pass is lost: the comma in the name makes 6 fields" : "found";
                default -> "ok";
            };
            System.out.printf("  find(%s) -> %-46s %s%n", id, Arrays.toString(r), verdict);
        }
        Files.delete(tmp);
        Optional<String> none = Optional.empty();
        System.out.println(none.orElse("Conclusion: counting fields detects truncation, not wrong content; delimiters need quoting; values need type/range checks; a checksum detects hand edits and cut writes."));
    }
}
