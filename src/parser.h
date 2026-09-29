#pragma once

#include <string>

#include "types.h"

namespace blockalloc {

struct ParseReport {
    bool ok = false;
    std::string error;
    Instance instance;
};

ParseReport parse_instance(const std::string& text);

}  // namespace blockalloc
