package dao;

import java.sql.Connection;
import java.sql.SQLException;

/** Where a DAO gets its connection: one per operation, a shared one (transactions, tests) or a pool. */
public interface ConnectionSource {
    Connection borrow() throws SQLException;
    default void giveBack(Connection c) throws SQLException { c.close(); }

    /** Every operation uses the same open connection (it is not closed after each operation). */
    static ConnectionSource single(Connection c) {
        return new ConnectionSource() {
            public Connection borrow() { return c; }
            public void giveBack(Connection x) { }
        };
    }
}
