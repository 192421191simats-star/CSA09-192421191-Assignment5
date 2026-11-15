import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a year's pass file (A1 format) for one campus: n passes with about 1% realistic damage
 * (hand edits, truncated line, letters in numbers, bad dates, duplicates, empty lines).
 * Run: java -cp out MakeExistingFile <out file> <n passes> <first pass number> <seed>
 */
public class MakeExistingFile {
    static final String[] FIRST = {"Arun", "Divya", "Karthik", "Meena", "Rahul", "Sneha", "Vignesh", "Priya", "Harish", "Keerthana", "Surya", "Nandhini"};
    static final String[] LAST = {"Kumar", "R", "S", "Raj", "Krishnan", "Iyer", "D'Souza", "M", "Pillai", "Das"};
    static final String[] STOPS = {"Main Gate", "Poonamallee", "Porur", "Guindy", "Tambaram", "Avadi", "Koyambedu", "Vadapalani", "T. Nagar", "Ambattur"};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); int n = Integer.parseInt(args[1]); int first = Integer.parseInt(args[2]); long seed = Long.parseLong(args[3]);
        Random r = new Random(seed);
        List<String> lines = new ArrayList<>(n + 2);
        lines.add(PassCsv.HEADER);
        int damaged = 0;
        for (int i = 0; i < n; i++) {
            int no = first + i;
            String name = FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
            if (r.nextInt(40) == 0) name = LAST[r.nextInt(LAST.length)] + ", " + FIRST[r.nextInt(FIRST.length)];
            PassCsv.Pass p = new PassCsv.Pass("P" + no, String.valueOf(192300000 + (no % 690000)), name, 1 + r.nextInt(40), STOPS[r.nextInt(STOPS.length)],
                    LocalDate.of(2027, 6, 30), BigDecimal.valueOf(3000 + 100L * r.nextInt(30)).setScale(2));
            String line = PassCsv.encode(p);
            int dice = r.nextInt(1000);
            if (dice < 10) {                                   // about 1% damaged, in different ways
                damaged++;
                line = switch (dice % 6) {
                    case 0 -> line.replace(p.amount().toPlainString(), "9" + p.amount().toPlainString());          // hand edit -> CRC fails
                    case 1 -> line.substring(0, line.length() / 2);                                                // cut short
                    case 2 -> { String b = line.substring(0, line.lastIndexOf(',')).replace("," + p.routeNo() + ",", ",R" + p.routeNo() + ","); yield b + "," + PassCsv.crc(b); }
                    case 3 -> { String b = line.substring(0, line.lastIndexOf(',')).replace("2027-06-30", "2027-13-01"); yield b + "," + PassCsv.crc(b); }
                    case 4 -> "";
                    default -> lines.get(lines.size() - 1);                                                        // the previous record written twice
                };
            }
            lines.add(line);
        }
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.printf("wrote %s: %,d passes, %,d lines deliberately damaged or duplicated%n", out, n, damaged);
    }
}
