
package fix.recovery;

import java.nio.charset.StandardCharsets;

/** FNV-1a 64-bit rolling digest over the canonical applied business stream. */
public final class Digest64 {
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long state;

    public Digest64() {
        this(OFFSET_BASIS);
    }

    public Digest64(long state) {
        this.state = state;
    }

    public void update(String canonicalLine) {
        byte[] bytes = canonicalLine.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            state ^= b & 0xFFL;
            state *= PRIME;
        }
        state ^= 0x0A;
        state *= PRIME;
    }

    public long value() {
        return state;
    }

    public String hex() {
        return String.format("%016x", state);
    }
}
