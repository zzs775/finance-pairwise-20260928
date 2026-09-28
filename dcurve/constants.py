"""Fixed numeric policy: precision, tolerances and solver limits.

All Decimal arithmetic in the package runs under :data:`DECIMAL_CONTEXT`
(50 significant digits) so intermediates never lose scale.  The constants
here are the single source of truth and are documented in the README.
"""

from decimal import Context, Decimal, getcontext

DECIMAL_PRECISION = 50
DECIMAL_CONTEXT = Context(prec=DECIMAL_PRECISION)

# Mirror the policy onto the process-global context for direct Decimal ops.
getcontext().prec = DECIMAL_PRECISION

MAX_BISECTION_ITERATIONS = 200

# Stop bisection once the ln(df) bracket shrinks below this width; residual
# tolerance stops earlier in practice.
BISECTION_WIDTH_TOLERANCE = Decimal("1e-20")

# Residual (unit-notional) threshold for accepting a bisection midpoint.
SOLVER_RESIDUAL_TOLERANCE = Decimal("1e-18")

# Absolute unit-notional repricing tolerance (see README "Scale").
REPRICING_TOLERANCE = Decimal("1e-12")

# Bracket: ln(df) is searched within +/- this many natural-log units around
# the previous node's ln(df); e^-6 ~= 0.00247 / e^6 ~= 403.
BRACKET_LOG_HALF_WIDTH = Decimal(6)

ZERO = Decimal(0)
ONE = Decimal(1)
TWO = Decimal(2)
