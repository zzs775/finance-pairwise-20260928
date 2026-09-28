package loanengine.core;

public class LoanException extends RuntimeException {

    public enum Category {
        INVALID_DATE,
        MISSING_FIXING,
        NEGATIVE_PRINCIPAL,
        SCALE_OVERFLOW
    }

    private final Category category;

    public LoanException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
