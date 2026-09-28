#include "gaussian.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace mcopt {

double normalCdf(double x) {
    return 0.5 * std::erfc(-x * 0.707106781186547524400844362104849);
}

double inverseNormalCdf(double p) {
    constexpr double kA1 = -3.969683028665376e+01;
    constexpr double kA2 = 2.209460984245205e+02;
    constexpr double kA3 = -2.759285104469687e+02;
    constexpr double kA4 = 1.383577518672690e+02;
    constexpr double kA5 = -3.066479806614716e+01;
    constexpr double kA6 = 2.506628277459239e+00;

    constexpr double kB1 = -5.447609879822406e+01;
    constexpr double kB2 = 1.615858368580409e+02;
    constexpr double kB3 = -1.556989798598866e+02;
    constexpr double kB4 = 6.680131188771972e+01;
    constexpr double kB5 = -1.328068155288572e+01;

    constexpr double kC1 = -7.784894002430293e-03;
    constexpr double kC2 = -3.223964580411365e-01;
    constexpr double kC3 = -2.400758277161838e+00;
    constexpr double kC4 = -2.549732539343734e+00;
    constexpr double kC5 = 4.374664141464968e+00;
    constexpr double kC6 = 2.938163982698783e+00;

    constexpr double kD1 = 7.784695709041462e-03;
    constexpr double kD2 = 3.224671290700398e-01;
    constexpr double kD3 = 2.445134137142996e+00;
    constexpr double kD4 = 3.754408661907416e+00;

    constexpr double kPLow = 0.02425;
    constexpr double kPHigh = 1.0 - kPLow;

    double q;
    double r;
    double x;

    if (p < kPLow) {
        q = std::sqrt(-2.0 * std::log(p));
        x = (((((kC1 * q + kC2) * q + kC3) * q + kC4) * q + kC5) * q + kC6) /
            ((((kD1 * q + kD2) * q + kD3) * q + kD4) * q + 1.0);
    } else if (p <= kPHigh) {
        q = p - 0.5;
        r = q * q;
        x = (((((kA1 * r + kA2) * r + kA3) * r + kA4) * r + kA5) * r + kA6) *
            q /
            (((((kB1 * r + kB2) * r + kB3) * r + kB4) * r + kB5) * r + 1.0);
    } else {
        q = std::sqrt(-2.0 * std::log(1.0 - p));
        x = -(((((kC1 * q + kC2) * q + kC3) * q + kC4) * q + kC5) * q + kC6) /
            ((((kD1 * q + kD2) * q + kD3) * q + kD4) * q + 1.0);
    }

    // One Halley/Newton-style refinement against the CDF.
    const double e = normalCdf(x) - p;
    const double u = e * 2.5066282746310002 * std::exp(x * x * 0.5);
    x -= u / (1.0 + x * u * 0.5);
    return x;
}

}  // namespace mcopt
