package csm.model;
/** 机械 oracle 校验失败：滚动不闭合或分录不平衡。 */
public class OracleException extends RuntimeException {

    public OracleException(String message) {
        super(message);
    }
}
