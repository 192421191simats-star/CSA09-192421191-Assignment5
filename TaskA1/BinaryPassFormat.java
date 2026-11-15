import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Task A1.1(b) - binary layout written with DataOutputStream (variable length, no delimiter at all):
 *   file header : int magic 0x42505331 ("BPS1"), short version 1
 *   per record  : short length L of the body, body (L bytes), int CRC32 of the body
 *   body        : UTF passId | int regNo | UTF name | byte routeNo | UTF boardingStop | int validUntil (epoch day) | long amount (paise)
 * writeUTF stores a 2-byte length before the characters, so a comma, quote or line break in a name is just data.
 * A record cut short by a power failure ends in EOFException or a CRC mismatch and is reported, not returned.
 */
public final class BinaryPassFormat {
    static final int MAGIC = 0x42505331;
    private BinaryPassFormat() { }

    public static void writeAll(Path f, List<PassCsv.Pass> passes) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(f)))) {
            out.writeInt(MAGIC); out.writeShort(1);
            java.io.ByteArrayOutputStream bodyBytes = new java.io.ByteArrayOutputStream(128);
            DataOutputStream body = new DataOutputStream(bodyBytes);
            for (PassCsv.Pass p : passes) {
                bodyBytes.reset();
                body.writeUTF(p.passId()); body.writeInt(Integer.parseInt(p.regNo())); body.writeUTF(p.name());
                body.writeByte(p.routeNo()); body.writeUTF(p.boardingStop());
                body.writeInt((int) p.validUntil().toEpochDay()); body.writeLong(p.amount().movePointRight(2).longValueExact());
                byte[] b = bodyBytes.toByteArray();
                CRC32 c = new CRC32(); c.update(b);
                out.writeShort(b.length); out.write(b); out.writeInt((int) c.getValue());
            }
        }
    }

    public record ReadResult(List<PassCsv.Pass> passes, List<String> problems) { }

    public static ReadResult readAll(Path f) throws IOException {
        List<PassCsv.Pass> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a bus-pass file (bad magic number)");
            in.readShort();
            int rec = 0;
            while (true) {
                int len;
                try { len = in.readUnsignedShort(); } catch (EOFException e) { break; }        // clean end of file
                rec++;
                byte[] b = new byte[len];
                try { in.readFully(b); int crc = in.readInt();
                    CRC32 c = new CRC32(); c.update(b);
                    if ((int) c.getValue() != crc) { problems.add("record " + rec + ": CRC mismatch"); continue; }
                } catch (EOFException e) { problems.add("record " + rec + ": cut short (" + len + "-byte body expected) - incomplete write"); break; }
                DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(b));
                out.add(new PassCsv.Pass(d.readUTF(), String.format("%09d", d.readInt()), d.readUTF(), d.readByte(), d.readUTF(),
                        LocalDate.ofEpochDay(d.readInt()), BigDecimal.valueOf(d.readLong(), 2)));
            }
        }
        return new ReadResult(out, problems);
    }
}
