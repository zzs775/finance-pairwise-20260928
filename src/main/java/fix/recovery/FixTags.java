package fix.recovery;

/** Fixed FIX tag numbers used by the offline recovery engine. */
public final class FixTags {
    public static final int BEGIN_STRING = 8;
    public static final int BODY_LENGTH = 9;
    public static final int MSG_TYPE = 35;
    public static final int SENDER_COMP_ID = 49;
    public static final int TARGET_COMP_ID = 56;
    public static final int POSS_DUP_FLAG = 43;
    public static final int SENDING_TIME = 52;
    public static final int MSG_SEQ_NUM = 34;
    public static final int ORIG_SENDING_TIME = 122;
    public static final int GAP_FILL_FLAG = 123;
    public static final int BEGIN_SEQ_NO = 7;
    public static final int END_SEQ_NO = 16;
    public static final int NEW_SEQ_NO = 36;
    public static final int CHECK_SUM = 10;

    private FixTags() {
    }
}
