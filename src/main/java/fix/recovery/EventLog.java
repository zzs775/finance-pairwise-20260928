
package fix.recovery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Collects graded events and optionally mirrors them to an appendable. */
public final class EventLog {
    private final List<LogEvent> events = new ArrayList<>();
    private final Appendable mirror;

    public EventLog() {
        this(null);
    }

    public EventLog(Appendable mirror) {
        this.mirror = mirror;
    }

    public void record(long lineNo, RejectCode code, String detail) {
        LogEvent event = new LogEvent(lineNo, code.severity(), code, detail);
        events.add(event);
        if (mirror != null) {
            try {
                mirror.append(lineNo + "\t" + code.severity() + "\t" + code + "\t" + detail)
                        .append(System.lineSeparator());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void info(long lineNo, String detail) {
        LogEvent event = new LogEvent(lineNo, Severity.INFO, null, detail);
        events.add(event);
        if (mirror != null) {
            try {
                mirror.append(lineNo + "\tINFO\t-\t" + detail).append(System.lineSeparator());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    public void flush() {
        if (mirror instanceof java.io.Flushable flushable) {
            try {
                flushable.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
