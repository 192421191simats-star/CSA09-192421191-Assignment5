import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic test passes: P100001, P100002, ... */
public final class PassData {
    static final String[] FIRST = {"Arun", "Divya", "Karthik", "Meena", "Rahul", "Sneha", "Vignesh", "Priya", "Harish", "Keerthana", "Surya", "Nandhini"};
    static final String[] LAST = {"Kumar", "R", "S", "Raj", "Krishnan", "Iyer", "Natarajan", "M", "Pillai", "Das"};
    static final String[] STOPS = {"Main Gate", "Poonamallee", "Porur", "Guindy", "Tambaram", "Avadi", "Koyambedu", "Vadapalani", "T. Nagar", "Ambattur"};

    private PassData() { }

    public static PassCsv.Pass pass(int i) {
        Random r = new Random(i * 31L + 7);
        String name = FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
        if (i % 97 == 0) name = LAST[r.nextInt(LAST.length)] + ", " + FIRST[r.nextInt(FIRST.length)];   // a comma inside the name
        return new PassCsv.Pass("P" + (100000 + i), String.valueOf(192300000 + i), name, 1 + r.nextInt(40),
                STOPS[r.nextInt(STOPS.length)], LocalDate.of(2027, 6, 30), BigDecimal.valueOf(3000 + 100L * r.nextInt(30)).setScale(2));
    }

    public static List<PassCsv.Pass> passes(int n) {
        List<PassCsv.Pass> l = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) l.add(pass(i));
        return l;
    }
}
