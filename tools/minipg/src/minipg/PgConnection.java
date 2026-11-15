package minipg;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** One PostgreSQL session over TCP (frontend/backend protocol 3.0). */
public final class PgConnection {
    final String url, user;
    private final Socket socket;
    private final DataInputStream in;
    private final BufferedOutputStream out;
    private final Map<String, String> params = new HashMap<>();
    private boolean autoCommit = true;
    private char txStatus = 'I';            // I idle, T in transaction, E failed transaction
    private boolean closed;
    private int stmtCounter;
    private int isolation = Connection.TRANSACTION_READ_COMMITTED;
    private Connection proxy;
    private final List<String> pendingCloses = new ArrayList<>();   // statement names closed by the application, sent with the next request

    PgConnection(String url, String host, int port, String database, String user, String password) throws SQLException {
        this.url = url; this.user = user;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
            out = new BufferedOutputStream(socket.getOutputStream(), 65536);
            startup(database, password);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Cannot connect to PostgreSQL at " + host + ":" + port + " - " + e.getMessage(), "08001", e);
        }
    }

    Connection proxy() { if (proxy == null) proxy = Dispatch.proxy(Connection.class, this); return proxy; }

    // =========================================================== low-level messages
    private final ByteArrayOutputStream msg = new ByteArrayOutputStream(256);

    private void begin() { msg.reset(); }
    private void int32(int v) { msg.write(v >>> 24); msg.write(v >>> 16); msg.write(v >>> 8); msg.write(v); }
    private void int16(int v) { msg.write(v >>> 8); msg.write(v); }
    private void cstr(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); msg.write(b, 0, b.length); msg.write(0); }
    private void bytes(byte[] b) { msg.write(b, 0, b.length); }
    private void send(char type) throws IOException {
        out.write(type);
        int len = msg.size() + 4;
        out.write(len >>> 24); out.write(len >>> 16); out.write(len >>> 8); out.write(len);
        msg.writeTo(out);
    }

    private static final class Message { char type; byte[] body; int pos;
        int int32() { int v = ((body[pos] & 255) << 24) | ((body[pos + 1] & 255) << 16) | ((body[pos + 2] & 255) << 8) | (body[pos + 3] & 255); pos += 4; return v; }
        int int16() { int v = ((body[pos] & 255) << 8) | (body[pos + 1] & 255); pos += 2; return (short) v; }
        String cstr() { int s = pos; while (body[pos] != 0) pos++; String r = new String(body, s, pos - s, StandardCharsets.UTF_8); pos++; return r; }
    }

    private Message read() throws IOException {
        Message m = new Message();
        m.type = (char) in.readUnsignedByte();
        int len = in.readInt();
        m.body = new byte[len - 4];
        in.readFully(m.body);
        return m;
    }

    // =========================================================== start-up and authentication
    private void startup(String database, String password) throws IOException, SQLException {
        begin();
        int32(196608);
        cstr("user"); cstr(user); cstr("database"); cstr(database);
        cstr("client_encoding"); cstr("UTF8"); cstr("DateStyle"); cstr("ISO"); cstr("application_name"); cstr("minipg");
        msg.write(0);
        int len = msg.size() + 4;
        out.write(len >>> 24); out.write(len >>> 16); out.write(len >>> 8); out.write(len);
        msg.writeTo(out); out.flush();
        Scram scram = null;
        while (true) {
            Message m = read();
            switch (m.type) {
                case 'R' -> {
                    int code = m.int32();
                    switch (code) {
                        case 0 -> { }
                        case 3 -> { begin(); cstr(password); send('p'); out.flush(); }
                        case 5 -> {
                            byte[] salt = java.util.Arrays.copyOfRange(m.body, 4, 8);
                            String inner = md5Hex((password + user).getBytes(StandardCharsets.UTF_8));
                            ByteArrayOutputStream b = new ByteArrayOutputStream();
                            b.writeBytes(inner.getBytes(StandardCharsets.UTF_8)); b.writeBytes(salt);
                            begin(); cstr("md5" + md5Hex(b.toByteArray())); send('p'); out.flush();
                        }
                        case 10 -> {
                            scram = new Scram(password);
                            byte[] first = scram.clientFirst().getBytes(StandardCharsets.UTF_8);
                            begin(); cstr("SCRAM-SHA-256"); int32(first.length); bytes(first); send('p'); out.flush();
                        }
                        case 11 -> {
                            String serverFirst = new String(m.body, 4, m.body.length - 4, StandardCharsets.UTF_8);
                            begin(); bytes(scram.clientFinal(serverFirst).getBytes(StandardCharsets.UTF_8)); send('p'); out.flush();
                        }
                        case 12 -> {
                            String fin = new String(m.body, 4, m.body.length - 4, StandardCharsets.UTF_8);
                            if (!scram.verifyServer(fin)) throw new SQLNonTransientConnectionException("SCRAM server signature mismatch", "28000");
                        }
                        default -> throw new SQLNonTransientConnectionException("Unsupported authentication method " + code, "28000");
                    }
                }
                case 'S' -> { m.pos = 0; params.put(m.cstr(), m.cstr()); }
                case 'K' -> { }
                case 'E' -> throw error(m);
                case 'N' -> { }
                case 'Z' -> { txStatus = (char) m.body[0]; return; }
                default -> { }
            }
        }
    }

    private static String md5Hex(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** SCRAM-SHA-256 (RFC 5802 / 7677), channel binding not used ("n,,"). */
    private static final class Scram {
        final String password, clientNonce;
        String clientFirstBare, authMessage; byte[] saltedPassword;
        Scram(String password) {
            this.password = password;
            byte[] r = new byte[18]; new SecureRandom().nextBytes(r);
            clientNonce = Base64.getEncoder().encodeToString(r);
        }
        String clientFirst() { clientFirstBare = "n=,r=" + clientNonce; return "n,," + clientFirstBare; }
        String clientFinal(String serverFirst) throws SQLException {
            Map<String, String> a = attrs(serverFirst);
            String nonce = a.get("r");
            if (nonce == null || !nonce.startsWith(clientNonce)) throw new SQLNonTransientConnectionException("SCRAM nonce mismatch", "28000");
            byte[] salt = Base64.getDecoder().decode(a.get("s"));
            int iter = Integer.parseInt(a.get("i"));
            try {
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, 256);
                saltedPassword = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                byte[] clientKey = hmac(saltedPassword, "Client Key");
                byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
                String withoutProof = "c=biws,r=" + nonce;
                authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
                byte[] sig = hmac(storedKey, authMessage);
                byte[] proof = new byte[clientKey.length];
                for (int i = 0; i < proof.length; i++) proof[i] = (byte) (clientKey[i] ^ sig[i]);
                return withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
            } catch (Exception e) { throw new SQLNonTransientConnectionException("SCRAM failed: " + e, "28000", e); }
        }
        boolean verifyServer(String serverFinal) {
            try {
                byte[] serverKey = hmac(saltedPassword, "Server Key");
                String expect = Base64.getEncoder().encodeToString(hmac(serverKey, authMessage));
                return expect.equals(attrs(serverFinal).get("v"));
            } catch (Exception e) { return false; }
        }
        static byte[] hmac(byte[] key, String data) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }
        static Map<String, String> attrs(String s) {
            Map<String, String> m = new HashMap<>();
            for (String p : s.split(",")) if (p.length() > 1 && p.charAt(1) == '=') m.put(p.substring(0, 1), p.substring(2));
            return m;
        }
    }

    // =========================================================== errors
    private SQLException error(Message m) {
        m.pos = 0;
        Map<Character, String> f = new HashMap<>();
        while (m.body[m.pos] != 0) { char k = (char) m.body[m.pos++]; f.put(k, m.cstr()); }
        String state = f.getOrDefault('C', "XX000");
        String text = f.getOrDefault('S', "ERROR") + ": " + f.getOrDefault('M', "?") + (f.containsKey('D') ? " - " + f.get('D') : "");
        if (state.startsWith("23")) return new SQLIntegrityConstraintViolationException(text, state);
        if (state.startsWith("22")) return new SQLDataException(text, state);
        if (state.startsWith("42")) return new SQLSyntaxErrorException(text, state);
        return new SQLException(text, state);
    }

    // =========================================================== results
    /** One statement's outcome: either rows or an update count. */
    static final class Result {
        String[] names; int[] oids, typmods; List<byte[][]> rows; long updateCount = -1; String tag;
    }

    private Result readRowDescription(Message m, Result r) {
        int n = m.int16();
        r.names = new String[n]; r.oids = new int[n]; r.typmods = new int[n];
        for (int i = 0; i < n; i++) {
            r.names[i] = m.cstr(); m.int32(); m.int16();
            r.oids[i] = m.int32(); m.int16(); r.typmods[i] = m.int32(); m.int16();
        }
        r.rows = new ArrayList<>();
        return r;
    }

    private static byte[][] dataRow(Message m) {
        int n = m.int16();
        byte[][] row = new byte[n][];
        for (int i = 0; i < n; i++) {
            int len = m.int32();
            if (len >= 0) { row[i] = java.util.Arrays.copyOfRange(m.body, m.pos, m.pos + len); m.pos += len; }
        }
        return row;
    }

    private static void complete(Message m, Result r) {
        r.tag = m.cstr();
        String[] p = r.tag.split(" ");
        String verb = p[0];
        if (r.rows == null && (verb.equals("INSERT") || verb.equals("UPDATE") || verb.equals("DELETE") || verb.equals("MERGE") || verb.equals("COPY")))
            r.updateCount = Long.parseLong(p[p.length - 1]);
        else if (r.rows == null) r.updateCount = 0;
    }

    /** Queues Close messages for statements the application closed; their CloseComplete replies are ignored. */
    private void writePendingCloses() throws IOException {
        for (String n : pendingCloses) { begin(); msg.write('S'); cstr(n); send('C'); }
        if (!pendingCloses.isEmpty()) { begin(); send('S'); pendingZ++; }
        pendingCloses.clear();
    }
    private int pendingZ;
    boolean lastParseCompleted;                             // set when ParseComplete arrives in execute()                                   // ReadyForQuery messages owed by queued Close+Sync
    private void drainPendingZ() throws IOException { while (pendingZ > 0) { Message m = read(); if (m.type == 'Z') { txStatus = (char) m.body[0]; pendingZ--; } } }

    private void ensureOpen() throws SQLException { if (closed) throw new SQLNonTransientConnectionException("Connection is closed", "08003"); }

    /** If auto-commit is off and no transaction is open, the next statement must start one. */
    private boolean needsBegin() { return !autoCommit && txStatus == 'I'; }

    // ---------------------------------------------------- simple query protocol (Statement)
    synchronized List<Result> simpleQuery(String sql) throws SQLException {
        ensureOpen();
        try {
            writePendingCloses();
            boolean begun = needsBegin();
            if (begun) { begin(); cstr("BEGIN"); send('Q'); }
            begin(); cstr(sql); send('Q'); out.flush();
            drainPendingZ();
            List<Result> results = new ArrayList<>();
            SQLException err = null;
            Result cur = new Result();
            int zLeft = begun ? 2 : 1;
            while (zLeft > 0) {
                Message m = read();
                switch (m.type) {
                    case 'T' -> cur = readRowDescription(m, new Result());
                    case 'D' -> cur.rows.add(dataRow(m));
                    case 'C' -> { complete(m, cur); if (!(begun && results.isEmpty() && "BEGIN".equals(cur.tag) && zLeft == 2)) results.add(cur); cur = new Result(); }
                    case 'I' -> cur = new Result();
                    case 'E' -> { if (err == null) err = error(m); }
                    case 'Z' -> { txStatus = (char) m.body[0]; zLeft--; }
                    case 'G' -> { begin(); cstr("COPY FROM STDIN is only supported through copyIn()"); send('f'); out.flush(); }
                    default -> { }
                }
            }
            if (err != null) throw err;
            return results;
        } catch (IOException e) { closed = true; throw new SQLNonTransientConnectionException("I/O error: " + e.getMessage(), "08006", e); }
    }

    // ---------------------------------------------------- extended protocol (PreparedStatement)
    String newStatementName() { return "mp_" + (++stmtCounter); }

    synchronized void parse(String name, String pgSql) throws SQLException {
        ensureOpen();
        try {
            begin(); cstr(name); cstr(pgSql); int16(0); send('P');
            begin(); send('S'); out.flush();
            SQLException err = null;
            while (true) {
                Message m = read();
                if (m.type == 'E' && err == null) err = error(m);
                if (m.type == 'Z') { txStatus = (char) m.body[0]; break; }
            }
            if (err != null) throw err;
        } catch (IOException e) { closed = true; throw new SQLNonTransientConnectionException("I/O error: " + e.getMessage(), "08006", e); }
    }

    /**
     * Binds and executes the named statement once per parameter set; all messages are pipelined (written
     * before any reply is read). In auto-commit mode every entry is followed by its own Sync, so each one
     * commits on its own (JDBC semantics; a failed entry does not undo the others). Inside a transaction a
     * single Sync ends the pipeline and the first error aborts the rest.
     */
    synchronized List<Result> execute(String name, String parseSql, List<String[]> paramSets, boolean wantRows, boolean batch) throws SQLException {
        ensureOpen();
        try {
            writePendingCloses();
            lastParseCompleted = false;
            boolean perEntrySync = autoCommit;
            boolean begun = needsBegin();
            if (parseSql != null) { begin(); cstr(name); cstr(parseSql); int16(0); send('P'); }   // parsed with the first execution, no extra round trip
            if (begun) { begin(); cstr(""); cstr("BEGIN"); int16(0); send('P'); begin(); cstr(""); cstr(""); int16(0); int16(0); int16(0); send('B'); begin(); cstr(""); int32(0); send('E'); }
            for (String[] ps : paramSets) {
                begin(); cstr(""); cstr(name);
                int16(0);                                   // all parameters in text format
                int16(ps.length);
                for (String v : ps) {
                    if (v == null) int32(-1);
                    else { byte[] b = v.getBytes(StandardCharsets.UTF_8); int32(b.length); bytes(b); }
                }
                int16(0);                                   // results in text format
                send('B');
                if (wantRows) { begin(); msg.write('P'); cstr(""); send('D'); }
                begin(); cstr(""); int32(0); send('E');
                if (perEntrySync) { begin(); send('S'); }
            }
            if (!perEntrySync) { begin(); send('S'); }
            out.flush();
            drainPendingZ();
            List<Result> results = new ArrayList<>();
            SQLException firstErr = null;
            Result cur = null;
            int skip = begun ? 1 : 0;                       // CommandComplete of our own BEGIN
            int zLeft = perEntrySync ? paramSets.size() : 1;
            while (zLeft > 0) {
                Message m = read();
                switch (m.type) {
                    case '1' -> lastParseCompleted = true;
                    case '2', 'n', '3' -> { }
                    case 'T' -> cur = readRowDescription(m, new Result());
                    case 'D' -> cur.rows.add(dataRow(m));
                    case 'C' -> {
                        if (skip > 0) { skip--; break; }
                        if (cur == null) cur = new Result();
                        complete(m, cur); results.add(cur); cur = null;
                    }
                    case 'I' -> { results.add(new Result()); cur = null; }
                    case 'E' -> {
                        SQLException e = error(m);
                        if (firstErr == null) firstErr = e;
                        Result failed = new Result(); failed.updateCount = Statement.EXECUTE_FAILED; failed.tag = "FAILED"; results.add(failed); cur = null;
                    }
                    case 'Z' -> { txStatus = (char) m.body[0]; zLeft--; }
                    default -> { }
                }
            }
            if (firstErr != null) {
                if (batch) {
                    int[] counts = new int[results.size()];
                    for (int i = 0; i < counts.length; i++) counts[i] = (int) results.get(i).updateCount;
                    int failedAt = 0; while (failedAt < counts.length && counts[failedAt] != Statement.EXECUTE_FAILED) failedAt++;
                    throw new BatchUpdateException("Batch entry " + failedAt + " failed: " + firstErr.getMessage(), firstErr.getSQLState(), 0, counts, firstErr);
                }
                throw firstErr;
            }
            return results;
        } catch (IOException e) { closed = true; throw new SQLNonTransientConnectionException("I/O error: " + e.getMessage(), "08006", e); }
    }

    synchronized void closeStatement(String name, boolean wasParsed) {
        if (!closed && wasParsed) pendingCloses.add(name);
    }

    /** COPY ... FROM STDIN: the PostgreSQL equivalent of MySQL's LOAD DATA LOCAL INFILE. Reached via unwrap(PgConnection.class). */
    public synchronized long copyIn(String copySql, Reader data) throws SQLException {
        ensureOpen();
        try {
            writePendingCloses(); out.flush(); drainPendingZ();
            if (needsBegin()) { begin(); cstr("BEGIN"); send('Q'); out.flush(); waitReady(); }
            begin(); cstr(copySql); send('Q'); out.flush();
            Message m = read();
            while (m.type != 'G') { if (m.type == 'E') { SQLException e = error(m); waitReady(); throw e; } m = read(); }
            char[] buf = new char[32768];
            int n;
            while ((n = data.read(buf)) > 0) {
                byte[] b = new String(buf, 0, n).getBytes(StandardCharsets.UTF_8);
                begin(); bytes(b); send('d');
            }
            begin(); send('c'); out.flush();
            long count = -1; SQLException err = null;
            while (true) {
                m = read();
                if (m.type == 'C') { String tag = m.cstr(); count = Long.parseLong(tag.substring(tag.lastIndexOf(' ') + 1)); }
                else if (m.type == 'E' && err == null) err = error(m);
                else if (m.type == 'Z') { txStatus = (char) m.body[0]; break; }
            }
            if (err != null) throw err;
            return count;
        } catch (IOException e) { closed = true; throw new SQLNonTransientConnectionException("I/O error: " + e.getMessage(), "08006", e); }
    }

    private void waitReady() throws IOException { while (true) { Message m = read(); if (m.type == 'Z') { txStatus = (char) m.body[0]; return; } } }

    // =========================================================== java.sql.Connection (called through the proxy)
    public Statement createStatement() throws SQLException { ensureOpen(); return new PgStatement(this).proxy(); }
    public Statement createStatement(int type, int concurrency) throws SQLException { return createStatement(); }
    public PreparedStatement prepareStatement(String sql) throws SQLException { ensureOpen(); return new PgPreparedStatement(this, sql).proxyPs(); }
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return prepareStatement(sql); }
    public PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException { return prepareStatement(sql); }

    public void setAutoCommit(boolean ac) throws SQLException {
        if (ac == autoCommit) return;
        if (ac && txStatus != 'I') commit();          // JDBC: switching auto-commit on commits the open transaction
        autoCommit = ac;
    }
    public boolean getAutoCommit() { return autoCommit; }
    public void commit() throws SQLException {
        if (autoCommit) throw new SQLException("commit() called while auto-commit is on", "25000");
        if (txStatus != 'I') { boolean failed = txStatus == 'E'; simpleQueryNoBegin("COMMIT"); if (failed) throw new SQLException("Transaction was aborted; COMMIT performed a ROLLBACK", "40000"); }
    }
    public void rollback() throws SQLException {
        if (autoCommit) throw new SQLException("rollback() called while auto-commit is on", "25000");
        if (txStatus != 'I') simpleQueryNoBegin("ROLLBACK");
    }
    private void simpleQueryNoBegin(String sql) throws SQLException {
        boolean saved = autoCommit; autoCommit = true;
        try { simpleQuery(sql); } finally { autoCommit = saved; }
    }
    public void setTransactionIsolation(int level) throws SQLException {
        String name = switch (level) {
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ";
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED";
            default -> "READ COMMITTED";
        };
        simpleQueryNoBegin("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + name);
        isolation = level;
    }
    public int getTransactionIsolation() { return isolation; }
    public DatabaseMetaData getMetaData() { return Dispatch.proxy(DatabaseMetaData.class, new PgDatabaseMetaData(this)); }
    public boolean isClosed() { return closed; }
    public boolean isValid(int timeout) { if (closed) return false; try { simpleQueryNoBegin("SELECT 1"); return true; } catch (SQLException e) { return false; } }
    public void close() {
        if (closed) return;
        try { begin(); send('X'); out.flush(); socket.close(); } catch (IOException ignored) { }
        closed = true;
    }
    public String nativeSQL(String sql) { return PgPreparedStatement.toDollarParams(sql).sql(); }
    public SQLWarning getWarnings() { return null; }
    public void clearWarnings() { }
    public void setReadOnly(boolean ro) { }
    public boolean isReadOnly() { return false; }
    public String getCatalog() { return params.getOrDefault("database", null); }
    public void setCatalog(String c) { }
    public String getSchema() { return "public"; }
    public void setSchema(String s) { }
    public int getHoldability() { return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    public void setHoldability(int h) { }
    public void setNetworkTimeout(java.util.concurrent.Executor e, int ms) throws SQLException { try { socket.setSoTimeout(ms); } catch (IOException x) { throw new SQLException(x); } }
    public int getNetworkTimeout() throws SQLException { try { return socket.getSoTimeout(); } catch (IOException x) { throw new SQLException(x); } }
    public void abort(java.util.concurrent.Executor e) { close(); }

    String serverVersion() { return params.getOrDefault("server_version", "?"); }
    char transactionStatus() { return txStatus; }
    @Override public String toString() { return "MiniPgConnection[" + url + "]"; }
}
