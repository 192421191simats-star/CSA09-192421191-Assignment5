import java.math.BigDecimal;
import java.sql.Connection;

/** Runs in a SEPARATE JVM: starts a transfer and kills itself (Runtime.halt) between the two updates. */
public class CrashChild {
    public static void main(String[] args) throws Exception {
        boolean tx = args[0].equals("transaction");
        Connection con = Db.open();
        WalletService.FailurePoint crash = () -> { System.out.println("  child: first UPDATE done - halting the JVM now (no finally, no rollback)"); System.out.flush(); Runtime.getRuntime().halt(9); };
        if (tx) WalletService.transfer(con, "H-192311001", "C-192311001", new BigDecimal("500"), crash);
        else WalletService.transferAutoCommit(con, "H-192311001", "C-192311001", new BigDecimal("500"), crash);
    }
}
