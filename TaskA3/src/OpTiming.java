import dao.ConnectionSource;
import dao.StudentDao;
import inline.StudentService;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Task A3 alternative - time per findById: inline SQL vs the generic DAO (same connection, same SQL), plus
 * the original classes, which also open a new connection for every call.
 */
public class OpTiming {
    interface Op { void run(int i) throws Exception; }
    static double perOpMicros(Op op, int n) throws Exception {
        double[] t = new double[5];
        for (int r = 0; r < 5; r++) { long t0 = System.nanoTime(); for (int i = 0; i < n; i++) op.run(i); t[r] = (System.nanoTime() - t0) / 1e3 / n; }
        Arrays.sort(t); return t[2];
    }

    public static void main(String[] args) throws Exception {
        try (Connection con = Db.open(); Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM marks"); st.executeUpdate("DELETE FROM student");
            StudentDao dao = new StudentDao(ConnectionSource.single(con));
            for (int i = 0; i < 1000; i++) dao.insert(new model.Student(String.valueOf(192320000 + i), "Student " + i, "CSE", 1 + i % 4, "s" + i + "@x.in"));
            StudentService svc = new StudentService(con);
            before.StudentDB old = new before.StudentDB(Db.url(), Db.props().getProperty("user"), Db.props().getProperty("password"));
            for (int w = 0; w < 3000; w++) { dao.findById(String.valueOf(192320000 + w % 1000)); svc.show(String.valueOf(192320000 + w % 1000)); }
            double[] dd = new double[7], ss = new double[7];
            for (int r = 0; r < 7; r++) {                          // interleaved, so both designs see the same conditions
                dd[r] = perOpMicros(i -> dao.findById(String.valueOf(192320000 + i % 1000)), 1000);
                ss[r] = perOpMicros(i -> svc.show(String.valueOf(192320000 + i % 1000)), 1000);
            }
            Arrays.sort(dd); Arrays.sort(ss);
            double d = dd[3], s = ss[3];
            double o = perOpMicros(i -> old.find(String.valueOf(192320000 + i % 1000)), 40);
            Runtime rt = Runtime.getRuntime();
            System.out.printf("%-58s %12s%n", "design (findById, 1,000-row table, median of 7 interleaved rounds)", "us per call");
            System.out.printf("%-58s %12.1f%n", "inline SQL in the service (prepare + execute + map)", s);
            System.out.printf("%-58s %12.1f%n", "generic Dao<T,K> (same SQL, + lambdas and an Object[])", d);
            System.out.printf("%-58s %12.1f%n", "original StudentDB (new connection + Statement per call)", o);
            System.out.printf("DAO / inline = %.2f ; one-off space of the DAO: 5 SQL strings + 5 Column objects per entity type (< 2 KB)%n", d / s);
            Files.createDirectories(Path.of("data"));
            try (PrintWriter csv = new PrintWriter("data/a3_op_timing.csv")) {
                csv.println("design,us_per_call"); csv.printf("inline,%.2f%ndao,%.2f%noriginal_new_connection,%.2f%n", s, d, o);
            }
            st.executeUpdate("DELETE FROM student");
        }
    }
}
