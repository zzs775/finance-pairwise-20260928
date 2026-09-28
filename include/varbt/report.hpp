#pragma once

#include <string>

#include "varbt/engine.hpp"

namespace varbt {

// Deterministic, stable report segmented by model version.
ErrorCode renderReport(Engine& engine, std::string& out,
                       std::string& message);

} // namespace varbt
