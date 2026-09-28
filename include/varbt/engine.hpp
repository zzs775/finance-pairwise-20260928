#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "varbt/ordered_multiset.hpp"
#include "varbt/types.hpp"

namespace varbt {

ErrorCode validateParams(const Params& params);

class Engine {
public:
    explicit Engine(Params params);
    ~Engine();

    // Strict insert: rejects duplicate keys, forward date holes, sign errors,
    // sequence regressions and overflow.
    ErrorCode insert(const Observation& obs);

    // Historical correction: replaces var/pnl of an existing key.
    ErrorCode correct(const Observation& obs);

    // Removes an observation from the index (revision flow).
    ErrorCode erase(int64_t day, const std::string& version);

    const Params& params() const;
    size_t observationCount() const;
    bool has(int64_t day, const std::string& version) const;
    const Observation* find(int64_t day, const std::string& version) const;
    uint64_t lastSeq() const;
    std::vector<std::string> versions() const;

    // FNV-1a digest over every accepted observation (day/version/var/pnl/seq),
    // order-independent across versions, deterministic on the canonical
    // per-version day ordering. Used as the checkpoint input summary.
    uint64_t digest() const;

    // Materialises results for one version, recomputing only windows affected
    // by revisions. Returns a classified error (e.g. DateHole) without
    // publishing partial results.
    ErrorCode materialize(const std::string& version,
                          std::vector<DayResult>& out);

    // Const snapshot access for reporting / checkpointing.
    struct Row {
        Observation obs;
        DayResult result;
    };
    ErrorCode materializedRows(const std::string& version,
                               std::vector<Row>& out);

    // Reconstructs cache/window state. Used by checkpoint restoration.
    void primeAfterLoad(uint64_t last_seq);

    // Iterates canonical (version, day) observation pairs for serialization.
    void forEachObservation(
        const std::function<void(const Observation&)>& fn) const;

private:
    struct VersionState;
    Params params_;
    uint64_t last_seq_ = 0;
    std::map<std::string, std::unique_ptr<VersionState>> versions_;

    VersionState& stateFor(const std::string& version);
    const VersionState* findState(const std::string& version) const;
    VersionState* findState(const std::string& version);

    friend ErrorCode restoreEngine(
        Params params, uint64_t last_seq,
        const std::vector<Observation>& observations,
        std::unique_ptr<Engine>& out);
};

} // namespace varbt
