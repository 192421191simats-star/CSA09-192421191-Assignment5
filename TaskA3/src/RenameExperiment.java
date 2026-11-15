import dao.ConnectionSource;
import dao.StudentDao;
import inline.StudentScreen;
import inline.StudentService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import model.Student;

/**
 * Task A3 alternative - column 'email' is renamed to 'email_id'.
 *   phase "before": count the places that name the column in each design, rename it, run every operation
 *   phase "after" : run with -Dstudent.email.column=email_id (the ONE edit the DAO design needs), then rename back
 */
public class RenameExperiment {
    public static void main(String[] args) throws Exception {
        String phase = args.length > 0 ? args[0] : "before";
        try (Connection con = Db.open(); Statement st = con.createStatement()) {
            if (phase.equals("before")) {
                System.out.println("=== Places that name the column 'email' (string literals in the source) ===");
                count("inline design: src/inline/*", Path.of("src/inline"));
                count("DAO design:    src/dao/*", Path.of("src/dao"));
                System.out.println();
                st.executeUpdate("DELETE FROM marks"); st.executeUpdate("DELETE FROM student");
                st.executeUpdate("INSERT INTO student (reg_no, name, dept, year, email) VALUES ('192311010', 'Meena S', 'IT', 2, 'meena@saveetha.ac.in')");
                st.executeUpdate("ALTER TABLE student RENAME COLUMN email TO email_id");
                System.out.println("ALTER TABLE student RENAME COLUMN email TO email_id   -- done");
                runInline(con);
                runDao(con, "DAO, mapping unchanged");
                System.out.println("\n(the column stays renamed for the next run)");
            } else {
                System.out.println("Run with -Dstudent.email.column=" + System.getProperty("student.email.column") + "  (StudentDao.EMAIL - the one place that names the column)");
                runDao(con, "DAO after the one-line change");
                st.executeUpdate("ALTER TABLE student RENAME COLUMN email_id TO email");
                System.out.println("ALTER TABLE student RENAME COLUMN email_id TO email   -- restored");
            }
        }
    }

    static void count(String label, Path dir) throws IOException {
        Pattern lit = Pattern.compile("\"[^\"]*\\bemail\\b[^\"]*\"");
        int places = 0; List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.sorted().toList()) {
                int n = 0;
                for (String line : Files.readAllLines(f)) { Matcher m = lit.matcher(line); while (m.find()) { if (!line.trim().startsWith("*") && !line.trim().startsWith("//")) n++; } }
                if (n > 0) files.add(f.getFileName() + " (" + n + ")");
                places += n;
            }
        }
        System.out.printf("%-30s %2d string literals in %d file(s): %s%n", label, places, files.size(), files);
    }

    interface Op { Object run() throws Exception; }
    static void op(String name, Op o, int[] fails) {
        try { Object r = o.run(); System.out.printf("  %-34s ok      %s%n", name, r); }
        catch (Exception e) { fails[0]++; System.out.printf("  %-34s FAILED  %s%n", name, e.getMessage().replaceAll("\\s+", " ").replaceAll("^(.{90}).*", "$1...")); }
    }

    static void runInline(Connection con) {
        System.out.println("\nInline SQL design (StudentService + StudentScreen):");
        StudentService svc = new StudentService(con); StudentScreen scr = new StudentScreen(con);
        int[] f = {0};
        op("StudentService.register", () -> { svc.register(new Student("192311011", "Ravi K", "IT", 1, "ravi@x.in")); return "inserted"; }, f);
        op("StudentService.show", () -> svc.show("192311010"), f);
        op("StudentService.listDept", () -> svc.listDept("IT").size() + " rows", f);
        op("StudentService.changeEmail", () -> svc.changeEmail("192311010", "m2@x.in"), f);
        op("StudentService.remove", () -> svc.remove("192311011"), f);
        op("StudentScreen.profileCard", () -> scr.profileCard("192311010"), f);
        op("StudentScreen.whoOwnsEmail", () -> scr.whoOwnsEmail("meena@saveetha.ac.in"), f);
        op("StudentScreen.missingEmailCount", scr::missingEmailCount, f);
        System.out.println("  -> " + f[0] + " of 8 operations broken; each must be found and edited by hand");
    }

    static void runDao(Connection con, String label) {
        System.out.println("\n" + label + ":");
        StudentDao d = new StudentDao(ConnectionSource.single(con));
        int[] f = {0};
        op("StudentDao.insert", () -> { d.insert(new Student("192311012", "Kavya P", "IT", 3, "kavya@x.in")); return "inserted"; }, f);
        op("StudentDao.findById", () -> d.findById("192311010").orElse(null), f);
        op("StudentDao.findAll", () -> d.findAll().size() + " rows", f);
        op("StudentDao.findByDept", () -> d.findByDept("IT").size() + " rows", f);
        op("StudentDao.update", () -> d.update(new Student("192311010", "Meena S", "IT", 3, "meena.s@x.in")), f);
        op("StudentDao.delete", () -> d.delete("192311012"), f);
        System.out.println("  -> " + f[0] + " of 6 operations broken");
    }
}
