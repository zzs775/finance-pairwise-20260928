#include "blackscholes.hpp"

#include <algorithm>
#include <cmath>

#include "gaussian.hpp"

namespace mcopt {

double blackScholesPrice(double spot, double strike, double rate,
                         double dividend, double volatility, double maturity,
                         OptionRight right) {
    const double discount = std::exp(-rate * maturity);
    const double forward = spot * std::exp((rate - dividend) * maturity);
    if (volatility == 0.0 || maturity == 0.0) {
        const double intrinsic = right == OptionRight::Call
                                     ? forward - strike
                                     : strike - forward;
        return discount * std::max(intrinsic, 0.0);
    }
    const double sqrtT = std::sqrt(maturity);
    const double d1 =
        (std::log(spot / strike) +
         (rate - dividend + 0.5 * volatility * volatility) * maturity) /
        (volatility * sqrtT);
    const double d2 = d1 - volatility * sqrtT;
    const double discountedStrike = strike * discount;
    const double discountedDividend =
        spot * std::exp(-dividend * maturity);
    if (right == OptionRight::Call) {
        return discountedDividend * normalCdf(d1) -
               discountedStrike * normalCdf(d2);
    }
    return discountedStrike * normalCdf(-d2) -
           discountedDividend * normalCdf(-d1);
}

double blackScholesPrice(const Contract& contract) {
    return blackScholesPrice(contract.spot, contract.strike, contract.rate,
                             contract.dividend, contract.volatility,
                             contract.maturity, contract.right);
}

}  // namespace mcopt
