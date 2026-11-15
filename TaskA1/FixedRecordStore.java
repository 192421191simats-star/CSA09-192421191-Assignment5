import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Task A1 alternative - RandomAccessFile with FIXED-LENGTH 128-byte records. Pass IDs are issued in sequence
 * (P100001, P100002, ...) so record i = pass number - 100001 lives at byte i x 128: find = one seek + one read.
 *
 *  offset size field
 *    0     1   status (1 = valid, 0 = empty / cancelled)
 *    1     4   pass number (int, the digits of the pass ID)
 *    5     4   reg_no (int, 9 digits fit)
 *    9    60   name  - 30 UTF-16 chars, padded with \0 (longer names are REFUSED)
 *   69     1   route_no
 *   70    40   boarding stop - 20 UTF-16 chars, padded
 *  110     4   valid_until (epoch day)
 *  114     8   amount (paise, long)
 *  122     4   CRC32 of bytes 0..121
 *  126     2   reserved
 */
public final class FixedRecordStore implements AutoCloseable {
    public static final int RECORD = 128, NAME_CHARS = 30, STOP_CHARS = 20, FIRST_NO = 100001;
    private final RandomAccessFile raf;

    public FixedRecordStore(Path f) throws IOException { raf = new RandomAccessFile(f.toFile(), "rw"); }

    static int number(String passId) { return Integer.parseInt(passId.substring(1)); }

    public void put(PassCsv.Pass p) throws IOException {
        if (p.name().length() > NAME_CHARS || p.boardingStop().length() > STOP_CHARS)
            throw new IllegalArgumentException("field longer than its fixed size");
        ByteBuffer b = ByteBuffer.allocate(RECORD);
        b.put((byte) 1).putInt(number(p.passId())).putInt(Integer.parseInt(p.regNo()));
        putChars(b, p.name(), NAME_CHARS);
        b.put((byte) p.routeNo());
        putChars(b, p.boardingStop(), STOP_CHARS);
        b.putInt((int) p.validUntil().toEpochDay()).putLong(p.amount().movePointRight(2).longValueExact());
        CRC32 c = new CRC32(); c.update(b.array(), 0, 122);
        b.putInt((int) c.getValue());
        raf.seek((long) (number(p.passId()) - FIRST_NO) * RECORD);           // O(1): position computed, not searched
        raf.write(b.array());
    }

    private static void putChars(ByteBuffer b, String s, int n) {
        for (int i = 0; i < n; i++) b.putChar(i < s.length() ? s.charAt(i) : '\0');
    }
    private static String getChars(ByteBuffer b, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) { char c = b.getChar(); if (c != '\0') sb.append(c); }
        return sb.toString();
    }

    public void putAll(List<PassCsv.Pass> passes) throws IOException {
        // one large write instead of n seeks: same layout
        ByteBuffer all = ByteBuffer.allocate(passes.size() * RECORD);
        for (PassCsv.Pass p : passes) {
            int start = all.position();
            all.put((byte) 1).putInt(number(p.passId())).putInt(Integer.parseInt(p.regNo()));
            putChars(all, p.name(), NAME_CHARS); all.put((byte) p.routeNo()); putChars(all, p.boardingStop(), STOP_CHARS);
            all.putInt((int) p.validUntil().toEpochDay()).putLong(p.amount().movePointRight(2).longValueExact());
            CRC32 c = new CRC32(); c.update(all.array(), start, 122);
            all.putInt((int) c.getValue()).putShort((short) 0);
        }
        raf.seek(0); raf.write(all.array());
    }

    /** One seek + one 128-byte read, independent of how many records the file holds. */
    public Optional<PassCsv.Pass> find(String passId) throws IOException {
        long pos = (long) (number(passId) - FIRST_NO) * RECORD;
        if (pos < 0 || pos + RECORD > raf.length()) return Optional.empty();
        byte[] rec = new byte[RECORD];
        raf.seek(pos);
        raf.readFully(rec);
        ByteBuffer b = ByteBuffer.wrap(rec);
        if (b.get() != 1) return Optional.empty();
        CRC32 c = new CRC32(); c.update(rec, 0, 122);
        if (ByteBuffer.wrap(rec, 122, 4).getInt() != (int) c.getValue()) throw new IOException("record " + passId + " is damaged (CRC)");
        int no = b.getInt(), reg = b.getInt();
        String name = getChars(b, NAME_CHARS);
        int route = b.get();
        String stop = getChars(b, STOP_CHARS);
        LocalDate valid = LocalDate.ofEpochDay(b.getInt());
        BigDecimal amt = BigDecimal.valueOf(b.getLong(), 2);
        return Optional.of(new PassCsv.Pass("P" + no, String.format("%09d", reg), name, route, stop, valid, amt));
    }

    @Override public void close() throws IOException { raf.close(); }
}
