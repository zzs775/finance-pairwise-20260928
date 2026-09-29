package csm.model;

/** 单个科目在单个步骤上的期初 / 当期变动 / 期末。 */
public record BalanceTriple(Amount opening, Amount movement, Amount closing) {

    public static BalanceTriple openingOnly(Amount opening) {
        return new BalanceTriple(opening, Amount.zero(), opening);
    }

    public static BalanceTriple roll(Amount opening, Amount movement) {
        return new BalanceTriple(opening, movement, opening.add(movement));
    }
}
