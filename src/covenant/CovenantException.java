package covenant;

public sealed abstract class CovenantException extends RuntimeException
        permits DuplicateFactException, UnitConflictException, OverlappingPeriodException,
                DivisionByZeroException, MissingDefinitionException, InvalidInputException,
                OverflowException {

    protected CovenantException(String message) {
        super(message);
    }
}
