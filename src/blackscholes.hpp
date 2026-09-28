#pragma once

#include "types.hpp"

namespace mcopt {

double blackScholesPrice(double spot, double strike, double rate,
                         double dividend, double volatility, double maturity,
                         OptionRight right);
double blackScholesPrice(const Contract& contract);

}  // namespace mcopt
