package loanmonitor.model;

import java.math.BigDecimal;

/**
 * Directly interpretable formula tree. Leaves are fact references; internal
 * nodes are arithmetic operations. The tree is the canonical explanation
 * rendered into the provenance output.
 */
public sealed interface FormulaNode
        permits FormulaNode.FactRef, FormulaNode.Literal,
                FormulaNode.BinaryOp, FormulaNode.Annualize {

    enum Operator {
        ADD, SUBTRACT, MULTIPLY, DIVIDE
    }

    record FactRef(String account, Unit expectedUnit) implements FormulaNode {
    }

    record Literal(BigDecimal value, Unit unit) implements FormulaNode {
    }

    record BinaryOp(Operator operator, FormulaNode left, FormulaNode right)
            implements FormulaNode {
    }

    record Annualize(FormulaNode inner) implements FormulaNode {
    }
}
