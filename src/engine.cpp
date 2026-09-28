#include "varbt/engine.hpp"

#include <functional>
#include <limits>

namespace varbt {

namespace {

struct U128 {
    uint64_t lo = 0;
    uint64_t hi = 0;
};

U128 mul64(uint64_t a, uint64_t b) {
    uint64_t a0 = a & 0xFFFFFFFFULL;
    uint64_t a1 = a >> 32;
    uint64_t b0 = b & 0xFFFFFFFFULL;
    uint64_t b1 = b >> 32;
    uint64_t p00 = a0 * b0;
    uint64_t p01 = a0 * b1;
    uint64_t p10 = a1 * b0;
    uint64_t p11 = a1 * b1;
    uint64_t mid = (p00 >> 32) + (p01 & 0xFFFFFFFFULL) +
                   (p10 & 0xFFFFFFFFULL);
    U128 result;
    result.lo = (p00 & 0xFFFFFFFFULL) | (mid << 32);
    result.hi = p11 + (p01 >> 32) + (p10 >> 32) + (mid >> 32);
    return result;
}

// Frozen nearest-rank quantile index: rank = ceil(p*n/q), converted to a
// 0-based index. p,q positive int64 with p <= q, n <= INT64_MAX.
size_t quantileIndex(int64_t p, int64_t q, uint64_t n) {
    // Smallest i (0-based) satisfying (i+1)*q >= p*n. Products compared as
    // exact 128-bit values (no floating point, no approximation).
    U128 right = mul64(static_cast<uint64_t>(p), n);
    uint64_t lo = 0;
    uint64_t hi = n - 1;
    auto ge = [&](uint64_t i) {
        U128 left = mul64(i + 1, static_cast<uint64_t>(q));
        if (left.hi != right.hi) {
            return left.hi > right.hi;
        }
        return left.lo >= right.lo;
    };
    while (lo < hi) {
        uint64_t mid = lo + (hi - lo) / 2;
        if (ge(mid)) {
            hi = mid;
        } else {
            lo = mid + 1;
        }
    }
    return static_cast<size_t>(lo);
}

bool isException(int64_t pnl, int64_t var) {
    // Loss = -pnl; exception iff loss > var. Equivalent: pnl < -var, which
    // cannot overflow (-var is representable because var >= 0 and
    // var != INT64_MIN).
    return pnl < -var;
}

uint64_t fnvMix(uint64_t hash, const void* data, size_t bytes) {
    const auto* bytes_ptr = static_cast<const unsigned char*>(data);
    for (size_t i = 0; i < bytes; ++i) {
        hash ^= bytes_ptr[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

template <class T>
uint64_t fnvValue(uint64_t hash, const T& value) {
    return fnvMix(hash, &value, sizeof(T));
}

} // namespace

ErrorCode validateParams(const Params& params) {
    if (params.window < 1) {
        return ErrorCode::IllegalWindow;
    }
    if (params.level_den <= 0 || params.level_num <= 0 ||
        params.level_num > params.level_den) {
        return ErrorCode::IllegalWindow;
    }
    return ErrorCode::Ok;
}

struct Engine::VersionState {
    std::map<int64_t, Observation> rows;
    std::map<int64_t, DayResult> cached;
    int64_t clean_through = std::numeric_limits<int64_t>::min();
};

Engine::Engine(Params params) : params_(params) {}

Engine::~Engine() = default;

Engine::VersionState& Engine::stateFor(const std::string& version) {
    auto it = versions_.find(version);
    if (it == versions_.end()) {
        it = versions_
                 .emplace(version, std::make_unique<VersionState>())
                 .first;
    }
    return *it->second;
}

Engine::VersionState* Engine::findState(const std::string& version) {
    auto it = versions_.find(version);
    return it == versions_.end() ? nullptr : it->second.get();
}

const Engine::VersionState* Engine::findState(
    const std::string& version) const {
    auto it = versions_.find(version);
    return it == versions_.end() ? nullptr : it->second.get();
}

namespace {

ErrorCode validateObservation(const Observation& obs, uint64_t last_seq) {
    if (obs.version.empty()) {
        return ErrorCode::InvalidCommand;
    }
    if (obs.var < 0) {
        return ErrorCode::SignConvention;
    }
    if (obs.seq == 0 || obs.seq <= last_seq) {
        return ErrorCode::BadSeq;
    }
    if (obs.day == std::numeric_limits<int64_t>::min()) {
        return ErrorCode::DateHole;
    }
    return ErrorCode::Ok;
}

} // namespace

ErrorCode Engine::insert(const Observation& obs) {
    ErrorCode base = validateParams(params_);
    if (base != ErrorCode::Ok) {
        return base;
    }
    ErrorCode validation = validateObservation(obs, last_seq_);
    if (validation != ErrorCode::Ok) {
        return validation;
    }
    VersionState& state = stateFor(obs.version);
    if (state.rows.count(obs.day) > 0) {
        return ErrorCode::DuplicateKey;
    }
    if (!state.rows.empty()) {
        int64_t max_day = state.rows.rbegin()->first;
        if (obs.day > max_day && obs.day != max_day + 1) {
            return ErrorCode::DateHole;
        }
    }
    auto [it, inserted] = state.rows.emplace(obs.day, obs);
    (void)inserted;
    last_seq_ = obs.seq;

    int64_t dirty_start = obs.day;
    auto cached_it = state.cached.lower_bound(dirty_start);
    state.cached.erase(cached_it, state.cached.end());
    if (state.clean_through >= dirty_start) {
        state.clean_through = dirty_start - 1;
    }
    return ErrorCode::Ok;
}

ErrorCode Engine::correct(const Observation& obs) {
    ErrorCode validation = validateObservation(obs, last_seq_);
    if (validation != ErrorCode::Ok) {
        return validation;
    }
    VersionState* state = findState(obs.version);
    if (state == nullptr) {
        return ErrorCode::CorrectionNotFound;
    }
    auto it = state->rows.find(obs.day);
    if (it == state->rows.end()) {
        return ErrorCode::CorrectionNotFound;
    }
    it->second.var = obs.var;
    it->second.pnl = obs.pnl;
    it->second.seq = obs.seq;
    last_seq_ = obs.seq;

    auto cached_it = state->cached.lower_bound(obs.day);
    state->cached.erase(cached_it, state->cached.end());
    if (state->clean_through >= obs.day) {
        state->clean_through = obs.day - 1;
    }
    return ErrorCode::Ok;
}

ErrorCode Engine::erase(int64_t day, const std::string& version) {
    VersionState* state = findState(version);
    if (state == nullptr) {
        return ErrorCode::CorrectionNotFound;
    }
    auto it = state->rows.find(day);
    if (it == state->rows.end()) {
        return ErrorCode::CorrectionNotFound;
    }
    state->rows.erase(it);

    int64_t dirty_start =
        state->rows.empty() ? day : state->rows.lower_bound(day)->first;
    auto cached_it = state->cached.lower_bound(dirty_start);
    state->cached.erase(cached_it, state->cached.end());
    if (state->clean_through >= dirty_start) {
        state->clean_through = dirty_start - 1;
    }
    return ErrorCode::Ok;
}

const Params& Engine::params() const {
    return params_;
}

size_t Engine::observationCount() const {
    size_t total = 0;
    for (const auto& [name, state] : versions_) {
        (void)name;
        total += state->rows.size();
    }
    return total;
}

bool Engine::has(int64_t day, const std::string& version) const {
    const VersionState* state = findState(version);
    return state && state->rows.count(day) > 0;
}

const Observation* Engine::find(int64_t day,
                                const std::string& version) const {
    const VersionState* state = findState(version);
    if (!state) {
        return nullptr;
    }
    auto it = state->rows.find(day);
    return it == state->rows.end() ? nullptr : &it->second;
}

uint64_t Engine::lastSeq() const {
    return last_seq_;
}

std::vector<std::string> Engine::versions() const {
    std::vector<std::string> names;
    names.reserve(versions_.size());
    for (const auto& [name, state] : versions_) {
        (void)state;
        names.push_back(name);
    }
    return names;
}

uint64_t Engine::digest() const {
    uint64_t hash = 1469598103934665603ULL;
    for (const auto& [name, state] : versions_) {
        for (const auto& [day, obs] : state->rows) {
            hash = fnvValue(hash, day);
            hash = fnvMix(hash, obs.version.data(), obs.version.size());
            hash = fnvValue(hash, obs.var);
            hash = fnvValue(hash, obs.pnl);
            hash = fnvValue(hash, obs.seq);
        }
    }
    return hash;
}

void Engine::primeAfterLoad(uint64_t last_seq) {
    last_seq_ = last_seq;
    for (auto& [name, state] : versions_) {
        (void)name;
        state->cached.clear();
        state->clean_through = std::numeric_limits<int64_t>::min();
    }
}

ErrorCode Engine::materialize(const std::string& version,
                              std::vector<DayResult>& out) {
    VersionState* state = findState(version);
    if (state == nullptr) {
        out.clear();
        return ErrorCode::Ok;
    }

    // Contiguity check: days within a version must be consecutive from the
    // version's effective (first) day.
    if (!state->rows.empty()) {
        int64_t expected = state->rows.begin()->first;
        for (const auto& [day, obs] : state->rows) {
            (void)obs;
            if (day != expected) {
                return ErrorCode::DateHole;
            }
            ++expected;
        }
    }

    int64_t window = params_.window;
    int64_t dirty_start = state->clean_through + 1;
    auto row_it = state->rows.lower_bound(dirty_start);

    // Rebuild the exact window contents immediately preceding dirty_start.
    OrderedMultiset values;
    std::deque<int64_t> window_days;
    if (row_it != state->rows.begin()) {
        auto begin_it = row_it;
        --begin_it;
        int64_t left_bound =
            dirty_start > (std::numeric_limits<int64_t>::min)() + window
                ? dirty_start - window
                : (std::numeric_limits<int64_t>::min)();
        for (auto walk = begin_it;;) {
            if (walk->first >= left_bound) {
                window_days.push_front(walk->first);
                values.insert(walk->second.pnl);
            }
            if (walk == state->rows.begin()) {
                break;
            }
            --walk;
        }
    }

    for (; row_it != state->rows.end(); ++row_it) {
        int64_t day = row_it->first;
        const Observation& obs = row_it->second;
        values.insert(obs.pnl);
        window_days.push_back(day);
        int64_t cutoff = day - window + 1;
        while (!window_days.empty() && window_days.front() < cutoff) {
            auto expired = state->rows.find(window_days.front());
            values.eraseOne(expired->second.pnl);
            window_days.pop_front();
        }

        DayResult result;
        if (static_cast<int64_t>(values.size()) < window) {
            result.warmup = true;
        } else {
            size_t index = quantileIndex(params_.level_num,
                                         params_.level_den,
                                         static_cast<uint64_t>(values.size()));
            result.quantile = values.kth(index);
            result.exception = isException(obs.pnl, obs.var);
        }
        state->cached[day] = result;
    }
    if (!state->rows.empty()) {
        state->clean_through = state->rows.rbegin()->first;
    }

    out.clear();
    out.reserve(state->rows.size());
    for (const auto& [day, result] : state->cached) {
        (void)day;
        out.push_back(result);
    }
    return ErrorCode::Ok;
}

ErrorCode Engine::materializedRows(const std::string& version,
                                   std::vector<Row>& out) {
    std::vector<DayResult> results;
    ErrorCode error = materialize(version, results);
    if (error != ErrorCode::Ok) {
        return error;
    }
    out.clear();
    const VersionState* state = findState(version);
    if (state == nullptr) {
        return ErrorCode::Ok;
    }
    auto result_it = state->cached.begin();
    for (const auto& [day, obs] : state->rows) {
        Row row{obs, result_it->second};
        ++result_it;
        out.push_back(row);
    }
    return ErrorCode::Ok;
}

void Engine::forEachObservation(
    const std::function<void(const Observation&)>& fn) const {
    for (const auto& [name, state] : versions_) {
        (void)name;
        for (const auto& [day, obs] : state->rows) {
            (void)day;
            fn(obs);
        }
    }
}

ErrorCode restoreEngine(Params params, uint64_t last_seq,
                        const std::vector<Observation>& observations,
                        std::unique_ptr<Engine>& out) {
    auto engine = std::make_unique<Engine>(params);
    for (const Observation& observation : observations) {
        // Bypass input validation: bytes are already authenticated by the
        // checkpoint checksum and format check. Insertion order follows the
        // canonical (version, day) ordering so no forward-hole guard fires.
        Engine::VersionState& state =
            engine->stateFor(observation.version);
        auto [it, inserted] =
            state.rows.emplace(observation.day, observation);
        if (!inserted) {
            return ErrorCode::CorruptCheckpoint;
        }
    }
    engine->last_seq_ = last_seq;
    out = std::move(engine);
    return ErrorCode::Ok;
}

} // namespace varbt
