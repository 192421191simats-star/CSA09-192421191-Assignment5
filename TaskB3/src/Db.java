import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens JDBC connections from db.properties (url, user, password, dialect).
 * The file is looked up at -Ddb=..., then ../db.properties (run from a task folder), then ./db.properties.
 * The JDBC driver is found automatically by DriverManager (ServiceLoader) when its jar is on the classpath.
 */
public final class Db {
    private static Properties props;

    private Db() { }

    public static synchronized Properties props() {
        if (props != null) return props;
        Path p = System.getProperty("db") != null ? Path.of(System.getProperty("db"))
               : Files.exists(Path.of("../db.properties")) ? Path.of("../db.properties") : Path.of("db.properties");
        Properties pr = new Properties();
        try (Reader r = Files.newBufferedReader(p)) { pr.load(r); }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p.toAbsolutePath() + ": " + e.getMessage(), e); }
        props = pr;
        return pr;
    }

    public static Connection open() throws SQLException {
        Properties p = props();
        return DriverManager.getConnection(p.getProperty("url"), p.getProperty("user"), p.getProperty("password"));
    }

    public static boolean isMySql() { return "mysql".equalsIgnoreCase(props().getProperty("dialect", "")); }
    public static String url() { return props().getProperty("url"); }
}
