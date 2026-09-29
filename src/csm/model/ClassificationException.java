package csm.model;

/** 分类失败：如覆盖单位为零或负数，导致当期服务释放无法分配。 */
public class ClassificationException extends ValidationException {

    public ClassificationException(String message) {
        super(message);
    }
}
