package fix.recovery;

/** One item produced by the send-side resend planner. */
public record ResendEntry(int seqNum, String msgType, boolean gapFill, byte[] payload,
                          String origSendingTime) {
}
