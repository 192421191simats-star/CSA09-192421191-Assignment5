import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A pass as stored in a campus's serialized file (the B4 alternative). */
public record SerialPass(String passId, String regNo, String name, int routeNo, String boardingStop, LocalDate validUntil, BigDecimal amount, int campus)
        implements Serializable { }
