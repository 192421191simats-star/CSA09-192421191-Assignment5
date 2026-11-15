import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Task B1.1 - test table for add / find / list, including damaged data. Run: java -cp out PassFileStoreTest */
public class PassFileStoreTest {
    static final List<String[]> rows = new ArrayList<>();
    static void check(String id, String test, String verifies, String expected, String actual) {
        rows.add(new String[]{id, test, verifies, expected, actual, expected.equals(actual) ? "PASS" : "FAIL"});
    }
    static PassCsv.Pass p(String id, String reg, String name, int route, String amt) {
        return new PassCsv.Pass(id, reg, name, route, "Main Gate", LocalDate.of(2027, 6, 30), new BigDecimal(amt));
    }
    static String show(Optional<PassCsv.Pass> o) { return o.map(x -> x.passId() + "/" + x.name() + "/" + x.routeNo() + "/" + x.amount()).orElse("not found"); }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("b1test");
        Path f = dir.resolve("passes.csv");
        PassFileStore s = new PassFileStore(f);

        // T1-T3: normal operation
        s.add(p("P1001", "192311001", "Arun Kumar", 12, "4500"));
        s.add(p("P1002", "192311002", "Rao, Arun", 7, "3800"));                 // comma in the name
        s.add(p("P1003", "192311003", "Divya \"DJ\" R", 12, "4200.50"));        // quotes in the name
        check("T1", "find P1001 (normal)", "a stored pass is found with all fields", "P1001/Arun Kumar/12/4500.00", show(s.find("P1001")));
        check("T2", "find P9999 (does not exist)", "a missing ID returns 'not found', no exception", "not found", show(s.find("P9999")));
        check("T3", "list route 12", "only route-12 passes, in file order", "[P1001, P1003]", s.listByRoute(12).passes().stream().map(PassCsv.Pass::passId).toList().toString());
        check("T4", "find P1002 - name 'Rao, Arun'", "a delimiter inside a field survives (quoting)", "P1002/Rao, Arun/7/3800.00", show(s.find("P1002")));
        check("T5", "find P1003 - name with quotes", "quotes are doubled and restored", "P1003/Divya \"DJ\" R/12/4200.50", show(s.find("P1003")));

        // T6-T9: damaged data written behind the store's back
        Files.writeString(f, "P1004,DAMAGED LINE\n", StandardOpenOption.APPEND);                            // wrong field count
        Files.writeString(f, "\n", StandardOpenOption.APPEND);                                                // empty line
        String good = PassCsv.encode(p("P1005", "192311005", "Meena S", 12, "3000"));
        Files.writeString(f, good.replace(",3000.00,", ",9000.00,") + "\n", StandardOpenOption.APPEND);      // edited by hand
        s.add(p("P1006", "192311006", "Karthik R", 12, "3900"));
        List<PassFileStore.Damaged> bad = new ArrayList<>();
        check("T6", "find P1006 after a damaged line", "a damaged line does not stop the search", "P1006/Karthik R/12/3900.00", show(s.find("P1006", bad)));
        bad.clear();
        check("T7", "find P1005 (amount edited, checksum wrong)", "tampered/corrupted record is not trusted", "not found", show(s.find("P1005", bad)));
        check("T7b", "reason reported for P1005", "the damage is explained, with line number", "line 7: checksum mismatch", bad.isEmpty() ? "none" : "line " + bad.get(0).lineNo() + ": " + bad.get(0).reason().replaceAll(" \\(.*", ""));
        PassFileStore.ScanResult all = s.loadAll();
        check("T8", "load all: valid / damaged", "every bad line is reported, valid lines kept", "4 valid, 3 damaged", all.passes().size() + " valid, " + all.damaged().size() + " damaged");
        check("T8b", "damage report", "line numbers and reasons", "[5: expected 8 fields, found 2, 6: empty line, 7: checksum mismatch]",
                all.damaged().stream().map(d -> d.lineNo() + ": " + d.reason().replaceAll(" \\(.*", "")).toList().toString());

        // T9: power failure in the middle of a write - the record is cut short and has no newline
        String half = PassCsv.encode(p("P1007", "192311007", "Sneha M", 3, "4100"));
        Files.writeString(f, half.substring(0, half.length() / 2), StandardOpenOption.APPEND);
        s.add(p("P1008", "192311008", "Vignesh K", 3, "4300"));
        check("T9", "write cut short, then add P1008", "next add starts a new line; the new record is intact", "P1008/Vignesh K/3/4300.00", show(s.find("P1008")));
        bad.clear();
        check("T9b", "find P1007 (the cut-short record)", "a partial record is detected, not read as data", "not found", show(s.find("P1007", bad)));
        check("T9c", "reason for the cut-short record", "detected by field count / checksum", "line 9: expected 8 fields, found 5", bad.isEmpty() ? "none" : "line " + bad.get(0).lineNo() + ": " + bad.get(0).reason());

        // T10-T11: invalid input is refused before it reaches the file
        long size = Files.size(f);
        String r10;
        try { s.add(p("P1009", "192311009", "Bad Route", 150, "3000")); r10 = "added"; } catch (IllegalArgumentException e) { r10 = "refused: " + e.getMessage(); }
        check("T10", "add route 150", "validation on write; file unchanged", "refused: invalid pass: route_no out of range: 150 / file unchanged",
                r10 + (Files.size(f) == size ? " / file unchanged" : " / FILE CHANGED"));
        String r11;
        try { s.add(p("P1010", "192311010", "Two\nLines", 3, "3000")); r11 = "added"; } catch (IllegalArgumentException e) { r11 = "refused: " + e.getMessage(); }
        check("T11", "add a name with a line break", "one record = one line is guaranteed", "refused: line breaks are not allowed in a field", r11);

        // T12-T13: missing file
        PassFileStore none = new PassFileStore(dir.resolve("missing.csv"));
        bad.clear();
        check("T12", "find in a missing file", "missing file = no passes, reported, no crash", "not found / file missing.csv does not exist",
                show(none.find("P1001", bad)) + " / " + (bad.isEmpty() ? "-" : bad.get(0).reason()));
        none.add(p("P2001", "192312001", "First Pass", 1, "3000"));
        check("T13", "add to a missing file", "the file is created with its header line", "header + 1 record",
                Files.readAllLines(dir.resolve("missing.csv")).get(0).equals(PassCsv.HEADER) && Files.readAllLines(dir.resolve("missing.csv")).size() == 2 ? "header + 1 record" : "wrong");

        System.out.println("File after the tests (" + f.getFileName() + "):");
        int n = 1;
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) System.out.printf("  %2d | %s%n", n++, l);
        System.out.println();
        System.out.printf("%-4s %-42s %-52s %-48s %s%n", "ID", "test", "verifies", "actual", "result");
        int pass = 0;
        for (String[] r : rows) {
            System.out.printf("%-4s %-42s %-52s %-48s %s%n", r[0], r[1], r[2], r[4], r[5]);
            if (r[5].equals("PASS")) pass++; else System.out.println("      expected: " + r[3]);
        }
        System.out.println(pass + " / " + rows.size() + " tests passed");
        for (Path x : new Path[]{f, dir.resolve("missing.csv")}) Files.deleteIfExists(x);
        Files.delete(dir);
    }
}
