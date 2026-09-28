package fix.recovery;

/** Session message types and the business types understood by the engine. */
public final class MsgTypes {
    public static final String HEARTBEAT = "0";
    public static final String TEST_REQUEST = "1";
    public static final String RESEND_REQUEST = "2";
    public static final String REJECT = "3";
    public static final String SEQUENCE_RESET = "4";
    public static final String LOGOUT = "5";
    public static final String LOGON = "A";
    public static final String NEW_ORDER_SINGLE = "D";
    public static final String EXECUTION_REPORT = "8";

    private MsgTypes() {
    }

    public static boolean isSessionType(String type) {
        return HEARTBEAT.equals(type) || TEST_REQUEST.equals(type) || RESEND_REQUEST.equals(type)
                || REJECT.equals(type) || SEQUENCE_RESET.equals(type)
                || LOGOUT.equals(type) || LOGON.equals(type);
    }

    public static boolean isBusinessType(String type) {
        return NEW_ORDER_SINGLE.equals(type) || EXECUTION_REPORT.equals(type);
    }
}
