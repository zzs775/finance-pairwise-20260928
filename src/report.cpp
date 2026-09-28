#include "varbt/report.hpp"

#include <sstream>

#include "varbt/fixed_point.hpp"

namespace varbt {

namespace {

struct SegmentSummary {
    int64_t first_day = 0;
    int64_t last_day = 0;
    size_t evaluated = 0;
    size_t warmup = 0;
    size_t exceptions = 0;
};

std::string fixed(int64_t value) {
    return formatFixed(value, kFracDigits);
}

} // namespace

ErrorCode renderReport(Engine& engine, std::string& out,
                       std::string& message) {
    std::ostringstream stream;
    const Params& params = engine.params();
    stream << "window=" << params.window
           << " level=" << params.level_num << "/" << params.level_den
           << "\n";

    size_t total_observations = 0;
    size_t total_exceptions = 0;
    size_t total_evaluated = 0;

    for (const std::string& version : engine.versions()) {
        std::vector<Engine::Row> rows;
        ErrorCode error = engine.materializedRows(version, rows);
        if (error != ErrorCode::Ok) {
            message = "version " + version + ": " + errorName(error);
            return error;
        }

        SegmentSummary summary;
        if (!rows.empty()) {
            summary.first_day = rows.front().obs.day;
            summary.last_day = rows.back().obs.day;
        }
        stream << "version " << version << "\n";
        stream << "  effective_day="
               << (rows.empty() ? "-" : std::to_string(summary.first_day))
               << "\n";
        for (const Engine::Row& row : rows) {
            const Observation& obs = row.obs;
            const DayResult& result = row.result;
            if (result.warmup) {
                ++summary.warmup;
                stream << "  day=" << obs.day
                       << " var=" << fixed(obs.var)
                       << " pnl=" << fixed(obs.pnl)
                       << " quantile=- exception=false warmup\n";
            } else {
                ++summary.evaluated;
                if (result.exception) {
                    ++summary.exceptions;
                }
                stream << "  day=" << obs.day
                       << " var=" << fixed(obs.var)
                       << " pnl=" << fixed(obs.pnl)
                       << " quantile=" << fixed(result.quantile)
                       << " exception="
                       << (result.exception ? "true" : "false") << "\n";
            }
        }
        stream << "  summary days=" << rows.size()
               << " evaluated=" << summary.evaluated
               << " warmup=" << summary.warmup
               << " exceptions=" << summary.exceptions << "\n";

        total_observations += rows.size();
        total_exceptions += summary.exceptions;
        total_evaluated += summary.evaluated;
    }

    stream << "total observations=" << total_observations
           << " evaluated=" << total_evaluated
           << " exceptions=" << total_exceptions << "\n";
    out = stream.str();
    return ErrorCode::Ok;
}

} // namespace varbt
