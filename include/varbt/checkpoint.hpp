#pragma once
#include <cstdint>
#include <memory>
#include <string>

#include "varbt/engine.hpp"
#include "varbt/types.hpp"

namespace varbt {

// Atomically persists active windows, the ordered index and the input digest.
// A failed save never destroys the previous complete checkpoint.
ErrorCode saveCheckpoint(const Engine& engine, const std::string& dir,
                         std::string& message);

// Loads the newest complete checkpoint. Corruption / format mismatch leave the
// engine unset and a classified error.
ErrorCode loadCheckpoint(const std::string& dir,
                         std::unique_ptr<Engine>& out,
                         std::string& message);

} // namespace varbt
