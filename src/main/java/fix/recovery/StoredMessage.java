package fix.recovery;

/** One persisted inbound or outbound message record. */
public record StoredMessage(int seqNum, String msgType, String sendingTime, byte[] body) {
}
