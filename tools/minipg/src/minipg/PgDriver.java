package minipg;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * MiniPg - a minimal JDBC driver for PostgreSQL (protocol v3), written for CSA09 Assignment 5 because no
 * JDBC driver could be downloaded on the machine where the evidence was produced. It talks to a REAL
 * PostgreSQL server: SCRAM-SHA-256 login, simple queries (Statement), server-side prepared statements
 * (PreparedStatement, parsed once and bound many times), pipelined batches, transactions, ResultSetMetaData,
 * DatabaseMetaData and COPY FROM STDIN. It is NOT a production driver: for the submission environment use
 * MySQL Connector/J (or the official PostgreSQL JDBC driver) - only db.properties changes.
 *
 * URL: jdbc:minipg://host[:port]/database
 */
public final class PgDriver implements Driver {
    public static final String PREFIX = "jdbc:minipg://";
    static {
        try { DriverManager.registerDriver(new PgDriver()); }
        catch (SQLException e) { throw new ExceptionInInitializerError(e); }
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(PREFIX); }

    @Override public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        String rest = url.substring(PREFIX.length());
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String db = slash < 0 ? "" : rest.substring(slash + 1);
        String host = hostPort;
        int port = 5432;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) { host = hostPort.substring(0, colon); port = Integer.parseInt(hostPort.substring(colon + 1)); }
        String user = info.getProperty("user", "");
        String password = info.getProperty("password", "");
        PgConnection c = new PgConnection(url, host, port, db.isEmpty() ? user : db, user, password);
        return c.proxy();
    }

    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 0; }
    @Override public int getMinorVersion() { return 1; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
}
