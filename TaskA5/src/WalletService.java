import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Task A5.1 - moving money between two wallets.
 *   transfer()            : both UPDATEs in ONE transaction - commit both or roll back both
 *   transferAutoCommit()  : the alternative - two independent auto-commit statements
 * The debit statement itself refuses to go below zero (balance >= ?), and the database CHECK (balance >= 0)
 * is a second guard. Updates are applied in wallet-id order so two opposite transfers cannot deadlock.
 */
public final class WalletService {
    static final String DEBIT = "UPDATE wallet SET balance = balance - ? WHERE wallet_id = ? AND balance >= ?";
    static final String CREDIT = "UPDATE wallet SET balance = balance + ? WHERE wallet_id = ?";

    /** Called between the two updates - lets the experiments inject a failure at the worst moment. */
    public interface FailurePoint { void between() throws SQLException; }
    public static final FailurePoint NONE = () -> { };

    public static class InsufficientFundsException extends SQLException {
        public InsufficientFundsException(String m) { super(m, "45000"); }
    }

    private WalletService() { }

    public static void transfer(Connection con, String from, String to, BigDecimal amount, FailurePoint fp) throws SQLException {
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        boolean old = con.getAutoCommit();
        con.setAutoCommit(false);                                   // BEGIN
        try (PreparedStatement debit = con.prepareStatement(DEBIT); PreparedStatement credit = con.prepareStatement(CREDIT)) {
            boolean debitFirst = from.compareTo(to) < 0;            // fixed lock order
            if (debitFirst) { doDebit(debit, from, amount); fp.between(); doCredit(credit, to, amount); }
            else { doCredit(credit, to, amount); fp.between(); doDebit(debit, from, amount); }
            con.commit();                                           // both changes become permanent together
        } catch (SQLException | RuntimeException e) {
            con.rollback();                                         // neither change survives
            throw e;
        } finally {
            con.setAutoCommit(old);
        }
    }

    public static void transferAutoCommit(Connection con, String from, String to, BigDecimal amount, FailurePoint fp) throws SQLException {
        con.setAutoCommit(true);
        try (PreparedStatement debit = con.prepareStatement(DEBIT); PreparedStatement credit = con.prepareStatement(CREDIT)) {
            doDebit(debit, from, amount);                           // committed immediately
            fp.between();                                           // a failure here: the money has left and not arrived
            doCredit(credit, to, amount);                           // committed immediately
        }
    }

    static void doDebit(PreparedStatement ps, String wallet, BigDecimal amount) throws SQLException {
        ps.setBigDecimal(1, amount); ps.setString(2, wallet); ps.setBigDecimal(3, amount);
        if (ps.executeUpdate() != 1) throw new InsufficientFundsException("wallet " + wallet + " missing or balance below Rs " + amount);
    }
    static void doCredit(PreparedStatement ps, String wallet, BigDecimal amount) throws SQLException {
        ps.setBigDecimal(1, amount); ps.setString(2, wallet);
        if (ps.executeUpdate() != 1) throw new SQLException("wallet " + wallet + " not found", "02000");
    }
}
