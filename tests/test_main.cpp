#include <cstdint>
#include <cstdlib>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <random>
#include <vector>

#include "varbt/checkpoint.hpp"
#include "varbt/engine.hpp"
#include "varbt/fixed_point.hpp"
#include "varbt/ordered_multiset.hpp"
#include "varbt/oracle.hpp"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                            \
    do {                                                                       \
        if (!(cond)) {                                                         \
            std::cerr << "FAIL: " #cond " at " << __FILE__ << ":" << __LINE__  \
                      << "\n";                                                 \
            ++g_failures;                                                      \
        }                                                                      \
    } while (0)

void testOrderedMultisetDuplicates() {
    varbt::OrderedMultiset set;
    std::vector<int> values = {5, 1, 5, 5, -3, 1, 100, -3};
    for (int value : values) {
        set.insert(value);
    }
    CHECK(set.size() == 8);
    CHECK(set.kth(0) == -3);
    CHECK(set.kth(1) == -3);
    CHECK(set.kth(2) == 1);
    CHECK(set.kth(3) == 1);
    CHECK(set.kth(4) == 5);
    CHECK(set.kth(5) == 5);
    CHECK(set.kth(6) == 5);
    CHECK(set.kth(7) == 100);
    CHECK(set.eraseOne(5));
    CHECK(set.size() == 7);
    CHECK(set.kth(4) == 5);
    CHECK(set.kth(6) == 100);
    CHECK(set.eraseOne(5));
    CHECK(set.eraseOne(5));
    CHECK(!set.eraseOne(5));
    CHECK(set.size() == 5);
    CHECK(set.kth(2) == 1);
}

void testOrderedMultisetRandomVsVector() {
    std::mt19937 rng(12345);
    varbt::OrderedMultiset set;
    std::vector<int64_t> reference;
    for (int round = 0; round < 4000; ++round) {
        int action = rng() % 3;
        int64_t value = static_cast<int64_t>(rng() % 200) - 100;
        if (action == 0 || reference.empty()) {
            set.insert(value);
            reference.push_back(value);
        } else if (action == 1) {
            auto it = std::find(reference.begin(), reference.end(), value);
            bool present = it != reference.end();
            CHECK(set.eraseOne(value) == present);
            if (present) {
                reference.erase(it);
            }
        } else {
            std::vector<int64_t> sorted = reference;
            std::sort(sorted.begin(), sorted.end());
            for (size_t i = 0; i < sorted.size(); ++i) {
                CHECK(set.kth(i) == sorted[i]);
            }
        }
    }
}

void testFixedPoint() {
    int64_t value = 0;
    CHECK(varbt::parseFixed("1.5", 6, value) == varbt::ErrorCode::Ok);
    CHECK(value == 1'500'000);
    CHECK(varbt::parseFixed("-0.000001", 6, value) == varbt::ErrorCode::Ok);
    CHECK(value == -1);
    CHECK(varbt::parseFixed("12", 6, value) == varbt::ErrorCode::Ok);
    CHECK(value == 12'000'000);
    CHECK(varbt::parseFixed("1.500001", 6, value) == varbt::ErrorCode::Ok);
    CHECK(value == 1'500'001);
    CHECK(varbt::parseFixed("1.2345678", 6, value) ==
          varbt::ErrorCode::InvalidNumeric);
    CHECK(varbt::parseFixed("abc", 6, value) ==
          varbt::ErrorCode::InvalidNumeric);
    CHECK(varbt::parseFixed("9223372036854775807.0", 6, value) ==
          varbt::ErrorCode::Overflow);
    CHECK(varbt::formatFixed(1'500'000, 6) == "1.500000");
    CHECK(varbt::formatFixed(-1, 6) == "-0.000001");
    CHECK(varbt::formatFixed(0, 6) == "0.000000");
}

varbt::Observation obs(int64_t day, const std::string& version, int64_t var,
                       int64_t pnl, uint64_t seq) {
    varbt::Observation result;
    result.day = day;
    result.version = version;
    result.var = var;
    result.pnl = pnl;
    result.seq = seq;
    return result;
}

void testValidationClasses() {
    varbt::Params params{3, 1, 100}; // window 3, level 1%
    varbt::Engine engine(params);
    CHECK(engine.insert(obs(1, "v1", 100, -50, 1)) ==
          varbt::ErrorCode::Ok);
    CHECK(engine.insert(obs(1, "v1", 100, -50, 2)) ==
          varbt::ErrorCode::DuplicateKey);
    CHECK(engine.insert(obs(3, "v1", 100, -50, 2)) ==
          varbt::ErrorCode::DateHole);
    CHECK(engine.insert(obs(2, "v1", -1, -50, 2)) ==
          varbt::ErrorCode::SignConvention);
    CHECK(engine.insert(obs(2, "v1", 100, -50, 1)) ==
          varbt::ErrorCode::BadSeq);
    CHECK(engine.correct(obs(9, "v1", 100, -50, 2)) ==
          varbt::ErrorCode::CorrectionNotFound);

    varbt::Params bad_window{0, 1, 100};
    varbt::Engine bad_engine(bad_window);
    CHECK(bad_engine.insert(obs(1, "v1", 100, -50, 1)) ==
          varbt::ErrorCode::IllegalWindow);
    varbt::Params bad_level{3, 2, 1};
    varbt::Engine level_engine(bad_level);
    CHECK(level_engine.insert(obs(1, "v1", 100, -50, 1)) ==
          varbt::ErrorCode::IllegalWindow);
}

void testWindowAndExceptionsVsOracle() {
    varbt::Params params{3, 50, 100}; // window 3, median
    varbt::Engine engine(params);
    std::vector<varbt::OracleObservation> oracle_rows;
    std::vector<int64_t> pnls = {10, -20, 30, -40, 50, -60};
    for (size_t i = 0; i < pnls.size(); ++i) {
        int64_t day = static_cast<int64_t>(i + 1);
        int64_t var = 25;
        CHECK(engine.insert(obs(day, "v1", var, pnls[i], i + 1)) ==
              varbt::ErrorCode::Ok);
        oracle_rows.push_back({day, var, pnls[i]});
    }
    std::vector<varbt::DayResult> results;
    CHECK(engine.materialize("v1", results) == varbt::ErrorCode::Ok);
    std::vector<varbt::DayResult> expected =
        varbt::oracleRun(params, oracle_rows);
    CHECK(results.size() == expected.size());
    for (size_t i = 0; i < results.size(); ++i) {
        CHECK(results[i].quantile == expected[i].quantile);
        CHECK(results[i].exception == expected[i].exception);
        CHECK(results[i].warmup == expected[i].warmup);
    }
    // Boundary: loss equal to VaR is NOT an exception (pnl = -25, var = 25).
    varbt::Engine boundary(params);
    boundary.insert(obs(1, "v1", 25, -25, 1));
    boundary.insert(obs(2, "v1", 25, 0, 2));
    boundary.insert(obs(3, "v1", 25, 0, 3));
    std::vector<varbt::DayResult> boundary_results;
    boundary.materialize("v1", boundary_results);
    CHECK(boundary_results[2].exception == false);
}

void testEvictionBoundary() {
    varbt::Params params{2, 50, 100};
    varbt::Engine engine(params);
    engine.insert(obs(1, "v1", 0, 10, 1));
    engine.insert(obs(2, "v1", 0, 20, 2));
    std::vector<varbt::DayResult> results;
    engine.materialize("v1", results);
    CHECK(results[1].quantile == 10); // median of {10,20}
    engine.insert(obs(3, "v1", 0, 30, 3));
    engine.materialize("v1", results);
    CHECK(results[2].quantile == 20); // day 1 evicted: {20,30}
}

void testCheckpointRoundTrip() {
    std::filesystem::path dir =
        std::filesystem::temp_directory_path() / "varbt-ckpt-roundtrip";
    std::filesystem::remove_all(dir);
    varbt::Params params{3, 99, 100};
    varbt::Engine engine(params);
    for (int64_t day = 1; day <= 8; ++day) {
        engine.insert(obs(day, "v1", day * 10, (day % 2 ? 1 : -1) * day,
                          static_cast<uint64_t>(day)));
    }
    std::string message;
    CHECK(varbt::saveCheckpoint(engine, dir.string(), message) ==
          varbt::ErrorCode::Ok);

    std::unique_ptr<varbt::Engine> loaded;
    CHECK(varbt::loadCheckpoint(dir.string(), loaded, message) ==
          varbt::ErrorCode::Ok);
    CHECK(loaded != nullptr);
    CHECK(loaded->observationCount() == 8);
    CHECK(loaded->lastSeq() == 8);
    CHECK(loaded->digest() == engine.digest());
    std::vector<varbt::DayResult> a;
    std::vector<varbt::DayResult> b;
    engine.materialize("v1", a);
    loaded->materialize("v1", b);
    CHECK(a.size() == b.size());
    for (size_t i = 0; i < a.size(); ++i) {
        CHECK(a[i].quantile == b[i].quantile);
        CHECK(a[i].exception == b[i].exception);
    }

    // Idempotent restore: load twice without mutation.
    std::unique_ptr<varbt::Engine> again;
    CHECK(varbt::loadCheckpoint(dir.string(), again, message) ==
          varbt::ErrorCode::Ok);
    CHECK(again->digest() == engine.digest());
    std::filesystem::remove_all(dir);
}

void testCheckpointCorruptionFallback() {
    std::filesystem::path dir =
        std::filesystem::temp_directory_path() / "varbt-ckpt-fallback";
    std::filesystem::remove_all(dir);
    varbt::Params params{2, 50, 100};

    varbt::Engine first(params);
    first.insert(obs(1, "v1", 10, 10, 1));
    first.insert(obs(2, "v1", 10, 20, 2));
    std::string message;
    CHECK(varbt::saveCheckpoint(first, dir.string(), message) ==
          varbt::ErrorCode::Ok);

    varbt::Engine second(params);
    second.insert(obs(1, "v1", 10, 10, 1));
    second.insert(obs(2, "v1", 10, 20, 2));
    second.insert(obs(3, "v1", 10, 30, 3));
    CHECK(varbt::saveCheckpoint(second, dir.string(), message) ==
          varbt::ErrorCode::Ok);
    uint64_t good_digest = second.digest();

    // Locate the newest committed slot and corrupt its data bytes.
    namespace fs = std::filesystem;
    fs::path newest_data;
    auto committed_gen = [&](const char* commit, const char* data) {
        fs::path cp = dir / commit;
        fs::path dp = dir / data;
        if (!fs::exists(cp) || !fs::exists(dp) ||
            fs::file_size(cp) != 8) {
            return uint64_t{0};
        }
        uint64_t gen = 0;
        std::ifstream(cp, std::ios::binary).read(
            reinterpret_cast<char*>(&gen), 8);
        newest_data = dp;
        return gen;
    };
    uint64_t ga = committed_gen("commit-a", "data-a.bin");
    uint64_t gb = committed_gen("commit-b", "data-b.bin");
    if (gb > ga) {
        newest_data = dir / "data-b.bin";
    }
    {
        std::fstream stream(newest_data,
                            std::ios::in | std::ios::out | std::ios::binary);
        char damaged = 0;
        stream.seekp(sizeof(uint32_t) * 2 + sizeof(uint64_t) * 2 + 4);
        stream.write(&damaged, 1);
    }

    std::unique_ptr<varbt::Engine> loaded;
    CHECK(varbt::loadCheckpoint(dir.string(), loaded, message) ==
          varbt::ErrorCode::Ok);
    CHECK(loaded != nullptr);
    CHECK(loaded->digest() == first.digest());
    (void)good_digest;
    std::filesystem::remove_all(dir);
}

void testLateInsertionAndVersionSwitch() {
    varbt::Params params{3, 95, 100};
    varbt::Engine engine(params);
    // Days 2,3,4 arrive first (seqs 1..3); day 1 is late (seq 4).
    CHECK(engine.insert(obs(2, "v1", 100, -10, 1)) ==
          varbt::ErrorCode::Ok);
    CHECK(engine.insert(obs(3, "v1", 100, -20, 2)) ==
          varbt::ErrorCode::Ok);
    CHECK(engine.insert(obs(4, "v1", 100, -30, 3)) ==
          varbt::ErrorCode::Ok);
    CHECK(engine.insert(obs(1, "v1", 100, -5, 4)) ==
          varbt::ErrorCode::Ok);
    std::vector<varbt::DayResult> results;
    CHECK(engine.materialize("v1", results) == varbt::ErrorCode::Ok);
    CHECK(results.size() == 4);
    CHECK(results[3].quantile == -10); // 95% nearest-rank of {-10,-20,-30}

    // A new model version starts its own timeline; pre-effective-day records
    // of v1 are never reinterpreted under v2's rules.
    CHECK(engine.insert(obs(1, "v2", 50, -5, 5)) == varbt::ErrorCode::Ok);
    CHECK(engine.insert(obs(2, "v2", 50, -60, 6)) == varbt::ErrorCode::Ok);
    CHECK(engine.insert(obs(3, "v2", 50, -60, 7)) == varbt::ErrorCode::Ok);
    std::vector<varbt::DayResult> v2_results;
    CHECK(engine.materialize("v2", v2_results) == varbt::ErrorCode::Ok);
    CHECK(v2_results[0].warmup);
    CHECK(v2_results[1].warmup);
    CHECK(!v2_results[2].warmup);
    CHECK(v2_results[2].exception); // first evaluated day: loss 60 > VaR 50
}

void testRandomRevisionSequenceVsOracle() {
    std::mt19937 rng(777);
    varbt::Params params{4, 90, 100};
    for (int trial = 0; trial < 40; ++trial) {
        varbt::Engine engine(params);
        const int64_t span = 6 + static_cast<int64_t>(rng() % 6);
        std::map<int64_t, std::pair<int64_t, int64_t>> truth;
        uint64_t seq = 0;
        for (int step = 0; step < 80; ++step) {
            int action = rng() % 3;
            int64_t day = 1 + static_cast<int64_t>(rng() %
                                                   static_cast<uint64_t>(span));
            int64_t var = static_cast<int64_t>(rng() % 100);
            int64_t pnl = static_cast<int64_t>(rng() % 200) - 100;
            ++seq;
            if (action == 2 && truth.count(day)) {
                varbt::Observation correction =
                    obs(day, "v1", var, pnl, seq);
                CHECK(engine.correct(correction) == varbt::ErrorCode::Ok);
                truth[day] = {var, pnl};
            } else if (!truth.count(day)) {
                // Insert only contiguous forward to avoid DateHole rejection;
                // any lower day is a legal late arrival.
                varbt::ErrorCode inserted =
                    engine.insert(obs(day, "v1", var, pnl, seq));
                if (inserted == varbt::ErrorCode::Ok) {
                    truth[day] = {var, pnl};
                } else {
                    CHECK(inserted == varbt::ErrorCode::DateHole);
                    --seq; // rejected input never consumes sequence state
                }
            } else {
                --seq; // no-op step
            }
        }
        std::vector<varbt::OracleObservation> oracle_rows;
        for (const auto& [day, values] : truth) {
            oracle_rows.push_back({day, values.first, values.second});
        }
        std::vector<varbt::DayResult> actual;
        varbt::ErrorCode materialized =
            engine.materialize("v1", actual);
        std::vector<varbt::DayResult> expected =
            varbt::oracleRun(params, oracle_rows);
        if (materialized == varbt::ErrorCode::Ok) {
            CHECK(actual.size() == expected.size());
            for (size_t i = 0; i < actual.size() && i < expected.size(); ++i) {
                CHECK(actual[i].quantile == expected[i].quantile);
                CHECK(actual[i].exception == expected[i].exception);
                CHECK(actual[i].warmup == expected[i].warmup);
            }
        } else {
            CHECK(materialized == varbt::ErrorCode::DateHole);
        }
    }
}

void testEraseRecomputesOnlySuffix() {
    varbt::Params params{2, 50, 100};
    varbt::Engine engine(params);
    engine.insert(obs(1, "v1", 0, 10, 1));
    engine.insert(obs(2, "v1", 0, 20, 2));
    engine.insert(obs(3, "v1", 0, 30, 3));
    std::vector<varbt::DayResult> before;
    engine.materialize("v1", before);
    CHECK(before[2].quantile == 20);
    CHECK(engine.erase(2, "v1") == varbt::ErrorCode::Ok);
    std::vector<varbt::OracleObservation> rows = {
        {1, 0, 10}, {3, 0, 30}};
    std::vector<varbt::DayResult> expected =
        varbt::oracleRun(params, std::move(rows));
    std::vector<varbt::DayResult> after;
    CHECK(engine.materialize("v1", after) == varbt::ErrorCode::DateHole);
    // Fill the hole with a correction-style reinsertion (late record).
    engine.insert(obs(2, "v1", 0, 25, 4));
    std::vector<varbt::OracleObservation> rows2 = {
        {1, 0, 10}, {2, 0, 25}, {3, 0, 30}};
    expected = varbt::oracleRun(params, std::move(rows2));
    CHECK(engine.materialize("v1", after) == varbt::ErrorCode::Ok);
    CHECK(after.size() == expected.size());
    for (size_t i = 0; i < after.size(); ++i) {
        CHECK(after[i].quantile == expected[i].quantile);
    }
    CHECK(after[2].quantile == 25); // window {25,30}
}

void testSaveFailureKeepsPreviousCheckpoint() {
    namespace fs = std::filesystem;
    fs::path dir =
        fs::temp_directory_path() / "varbt-ckpt-savefail";
    std::filesystem::remove_all(dir);
    varbt::Params params{2, 50, 100};
    varbt::Engine first(params);
    first.insert(obs(1, "v1", 10, 10, 1));
    std::string message;
    CHECK(varbt::saveCheckpoint(first, dir.string(), message) ==
          varbt::ErrorCode::Ok);

    // The next save targets the other slot; block its temp data path with a
    // directory so staging fails. Generation 1's slot must stay loadable.
    fs::create_directories(dir / "data-b.tmp");
    varbt::Engine second(params);
    second.insert(obs(1, "v1", 10, 10, 1));
    CHECK(varbt::saveCheckpoint(second, dir.string(), message) ==
          varbt::ErrorCode::IoError);

    std::unique_ptr<varbt::Engine> loaded;
    CHECK(varbt::loadCheckpoint(dir.string(), loaded, message) ==
          varbt::ErrorCode::Ok);
    CHECK(loaded != nullptr);
    CHECK(loaded->observationCount() == 1);
    std::filesystem::remove_all(dir);
}

void testCheckpointFormatMismatch() {
    namespace fs = std::filesystem;
    fs::path dir =
        fs::temp_directory_path() / "varbt-ckpt-version";
    std::filesystem::remove_all(dir);
    varbt::Params params{2, 50, 100};
    varbt::Engine engine(params);
    engine.insert(obs(1, "v1", 10, 10, 1));
    std::string message;
    CHECK(varbt::saveCheckpoint(engine, dir.string(), message) ==
          varbt::ErrorCode::Ok);

    // Tamper the format-version word (second uint32) in every data slot.
    for (const char* name : {"data-a.bin", "data-b.bin"}) {
        fs::path data = dir / name;
        if (!fs::exists(data)) {
            continue;
        }
        std::fstream stream(data,
                            std::ios::in | std::ios::out | std::ios::binary);
        uint32_t bogus = 999999;
        stream.seekp(sizeof(uint32_t));
        stream.write(reinterpret_cast<const char*>(&bogus), 4);
    }
    std::unique_ptr<varbt::Engine> loaded;
    varbt::ErrorCode error =
        varbt::loadCheckpoint(dir.string(), loaded, message);
    CHECK(error == varbt::ErrorCode::CheckpointVersionMismatch);
    CHECK(loaded == nullptr);
    std::filesystem::remove_all(dir);
}

} // namespace

int main() {
    testOrderedMultisetDuplicates();
    testOrderedMultisetRandomVsVector();
    testFixedPoint();
    testValidationClasses();
    testWindowAndExceptionsVsOracle();
    testEvictionBoundary();
    testCheckpointRoundTrip();
    testCheckpointCorruptionFallback();
    testLateInsertionAndVersionSwitch();
    testRandomRevisionSequenceVsOracle();
    testEraseRecomputesOnlySuffix();
    testSaveFailureKeepsPreviousCheckpoint();
    testCheckpointFormatMismatch();
    if (g_failures == 0) {
        std::cout << "all tests passed\n";
        return 0;
    }
    std::cout << g_failures << " test(s) failed\n";
    return 1;
}
