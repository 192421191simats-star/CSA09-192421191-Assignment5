import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Task A1 text format (implemented): one pass per line, 8 comma-separated fields
 *   pass_id,reg_no,name,route_no,boarding_stop,valid_until,amount,crc32
 * - a field containing a comma or a double quote is written in double quotes and inner
 *   quotes are doubled (RFC 4180), e.g.  "Rao, Arun"   "Arun ""AK"" Kumar"
 * - crc32 = 8 hex digits of CRC32 over the first 7 encoded fields: detects garbage AND a line cut short
 * - the first line is a header that names the format version.
 */
public final class PassCsv {
    public static final String HEADER = "#BUSPASS v1 pass_id,reg_no,name,route_no,boarding_stop,valid_until,amount,crc32";

    public record Pass(String passId, String regNo, String name, int routeNo, String boardingStop, LocalDate validUntil, BigDecimal amount) { }

    /** Thrown for a line that cannot be trusted; carries the reason for the report. */
    public static final class DamagedLineException extends Exception {
        public DamagedLineException(String reason) { super(reason); }
    }

    private PassCsv() { }

    public static String encode(Pass p) {
        String body = String.join(",", quote(p.passId()), quote(p.regNo()), quote(p.name()), Integer.toString(p.routeNo()),
                quote(p.boardingStop()), p.validUntil().toString(), p.amount().setScale(2).toPlainString());
        return body + "," + crc(body);
    }

    static String crc(String body) {
        CRC32 c = new CRC32();
        c.update(body.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", c.getValue());
    }

    /** Line breaks are rejected by PassFileStore.add(), so a record is always exactly one line. */
    static String quote(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Splits one line into fields, honouring quotes. Unbalanced quotes = damaged. */
    static List<String> split(String line) throws DamagedLineException {
        List<String> out = new ArrayList<>(8);
        StringBuilder f = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { f.append('"'); i++; }
                    else inQ = false;
                } else f.append(c);
            } else if (c == '"') {
                if (f.length() != 0) throw new DamagedLineException("quote in the middle of a field");
                inQ = true;
            } else if (c == ',') { out.add(f.toString()); f.setLength(0); }
            else f.append(c);
        }
        if (inQ) throw new DamagedLineException("unbalanced quote (line cut short?)");
        out.add(f.toString());
        return out;
    }

    /** Only the pass ID, without validating the rest: what a scan for one ID needs. */
    static String idOf(String line) {
        int comma = line.indexOf(',');
        return comma < 0 ? line : line.substring(0, comma);
    }

    public static Pass decode(String line) throws DamagedLineException {
        if (line.isBlank()) throw new DamagedLineException("empty line");
        List<String> f = split(line);
        if (f.size() != 8) throw new DamagedLineException("expected 8 fields, found " + f.size());
        String body = line.substring(0, line.lastIndexOf(','));
        if (!crc(body).equals(f.get(7))) throw new DamagedLineException("checksum mismatch (stored " + f.get(7) + ", computed " + crc(body) + ")");
        String id = f.get(0), reg = f.get(1), name = f.get(2).trim(), stop = f.get(4).trim();
        if (!id.matches("P\\d{4,9}")) throw new DamagedLineException("bad pass_id '" + id + "'");
        if (!reg.matches("\\d{9}")) throw new DamagedLineException("bad reg_no '" + reg + "'");
        if (name.isEmpty() || name.length() > 60) throw new DamagedLineException("bad name");
        int route;
        try { route = Integer.parseInt(f.get(3)); } catch (NumberFormatException e) { throw new DamagedLineException("route_no not a number: '" + f.get(3) + "'"); }
        if (route < 1 || route > 99) throw new DamagedLineException("route_no out of range: " + route);
        LocalDate valid;
        try { valid = LocalDate.parse(f.get(5)); } catch (DateTimeParseException e) { throw new DamagedLineException("bad valid_until '" + f.get(5) + "'"); }
        BigDecimal amt;
        try { amt = new BigDecimal(f.get(6)); } catch (NumberFormatException e) { throw new DamagedLineException("amount not a number: '" + f.get(6) + "'"); }
        if (amt.signum() < 0 || amt.scale() > 2) throw new DamagedLineException("amount out of range: " + amt);
        return new Pass(id, reg, name, route, stop, valid, amt);
    }
}
