import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Task B2 - builds the adversarial pass files in input/ (committed, so the test is repeatable):
 *   damaged_passes.csv        - Task A1 format (8 fields with CRC32), one attack per line
 *   damaged_passes_5field.csv - the 5-field format of the given PassStore, same attacks
 * Lines marked "(valid CRC)" were written by a careless program: the checksum matches the bad content,
 * so only the field validation can catch them.
 */
public class MakeDamagedFiles {
    static String withCrc(String body) { return body + "," + PassCsv.crc(body); }
    static PassCsv.Pass p(String id, String name, int route, String amt) {
        return new PassCsv.Pass(id, "1923" + id.substring(1) + "0".repeat(Math.max(0, 6 - id.length())), name, route, "Main Gate", LocalDate.of(2027, 6, 30), new BigDecimal(amt));
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("input"));
        List<String> a1 = new ArrayList<>();
        a1.add(PassCsv.HEADER);
        a1.add(PassCsv.encode(new PassCsv.Pass("P2001", "192312001", "Arun Kumar", 12, "Main Gate", LocalDate.of(2027, 6, 30), new BigDecimal("4500"))));
        a1.add("P2002,192312002,Divya R,7,Porur,2027-06-30");                                                            // missing fields
        a1.add(withCrc("P2003,192312003,Karthik S,7,Porur,2027-06-30,45OO.00"));                                         // letter O (valid CRC)
        a1.add("");                                                                                                       // empty line
        a1.add("   ");                                                                                                    // blanks only
        a1.add(withCrc("P2004,192312004,Meena S,seven,Guindy,2027-06-30,3800.00"));                                     // route not a number (valid CRC)
        a1.add(withCrc("P2005,192312005,Rahul M,7,Guindy,2027-06-30,-3800.00"));                                        // negative amount (valid CRC)
        a1.add(withCrc("P2006,192312006,Sneha K,7,Guindy,2027-02-30,3800.00"));                                         // 30 February (valid CRC)
        a1.add("P2007,192312007,\"Vignesh, R,7,Guindy,2027-06-30,3800.00,00000000");                                     // unbalanced quote
        a1.add(withCrc("P2008,192312008,Priya,Das,7,Guindy,2027-06-30,3800.00"));                                       // unquoted comma -> 9 fields
        a1.add(PassCsv.encode(new PassCsv.Pass("P2009", "192312009", "Robert'); DROP TABLE bus_pass;--", 9, "Main Gate", LocalDate.of(2027, 6, 30), new BigDecimal("3000"))));  // hostile but VALID
        a1.add("P2010,192312010," + "X".repeat(10_000) + ",7,Guindy,2027-06-30,3800.00,00000000");                       // 10,000-char name
        String good = PassCsv.encode(new PassCsv.Pass("P2011", "192312011", "Harish N", 3, "Avadi", LocalDate.of(2027, 6, 30), new BigDecimal("4100")));
        a1.add(good.replace("4100.00", "1.00"));                                                                          // amount edited by hand
        a1.add(PassCsv.encode(new PassCsv.Pass("P2001", "192312099", "Duplicate ID", 5, "Avadi", LocalDate.of(2027, 6, 30), new BigDecimal("3000"))));          // duplicate pass_id
        a1.add(PassCsv.encode(new PassCsv.Pass("P2012", "192312012", "Nandhini P", 12, "Tambaram", LocalDate.of(2027, 6, 30), new BigDecimal("3900"))));
        a1.add(good.substring(0, 30));                                                                                    // write cut short (power failure)
        Files.write(Path.of("input/damaged_passes.csv"), a1, StandardCharsets.UTF_8);

        List<String> f5 = List.of(
            "P2001,192312001,Arun Kumar,12,4500.0",
            "P2002,192312002,Divya R,7",                       // missing field
            "P2003,192312003,Karthik S,7,45OO.0",              // non-numeric amount
            "",                                                // empty line
            "P2004,192312004,Meena S,seven,3800.0",            // non-numeric route
            "P2005,192312005,Rahul M,7,-3800.0",               // negative amount
            "P2008,192312008,\"Das, Priya\",7,3800.0",         // a legitimate name with a comma
            "P2012,192312012,Nandhini P,12,3900.0");
        Files.write(Path.of("input/damaged_passes_5field.csv"), f5, StandardCharsets.UTF_8);
        System.out.println("wrote input/damaged_passes.csv (" + a1.size() + " lines) and input/damaged_passes_5field.csv (" + f5.size() + " lines)");
    }
}
