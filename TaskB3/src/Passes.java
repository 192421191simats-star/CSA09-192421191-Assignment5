import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generated passes as the five String fields PassLoader expects: pass_id, reg_no, name, route_no, amount. */
public final class Passes {
    static final String[] FIRST = {"Arun", "Divya", "Karthik", "Meena", "Rahul", "Sneha", "Vignesh", "Priya", "Harish", "Keerthana"};
    static final String[] LAST = {"Kumar", "R", "S", "Raj", "Krishnan", "Iyer", "D'Souza", "M", "Pillai", "Das"};
    private Passes() { }

    public static List<String[]> generate(int n, int firstNo) {
        List<String[]> l = new ArrayList<>(n);
        Random r = new Random(n + firstNo);
        for (int i = 0; i < n; i++) {
            int no = firstNo + i;
            l.add(new String[]{"P" + no, String.valueOf(192300000 + no % 700000), FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)],
                    String.valueOf(1 + r.nextInt(40)), (3000 + 100 * r.nextInt(30)) + ".0"});
        }
        return l;
    }
}
