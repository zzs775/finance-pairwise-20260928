#include "varbt/checkpoint.hpp"

#include <array>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace varbt {

namespace {

namespace fs = std::filesystem;

// Bumped whenever the on-disk frame layout changes incompatibly.
constexpr uint32_t kFormatMagic = 0x56415242; // "VARB"
constexpr uint32_t kFormatVersion = 1;

struct Frame {
    uint32_t magic = kFormatMagic;
    uint32_t format_version = kFormatVersion;
    uint64_t generation = 0;
    uint64_t last_seq = 0;
    uint64_t digest_value = 0;
    uint32_t crc = 0;
};

struct WireParams {
    int64_t window;
    int64_t level_num;
    int64_t level_den;
};

class Writer {
public:
    explicit Writer(std::vector<unsigned char>& buffer) : buffer_(buffer) {}

    template <class T>
    void pod(const T& value) {
        static_assert(std::is_trivially_copyable_v<T>);
        const auto* begin = reinterpret_cast<const unsigned char*>(&value);
        buffer_.insert(buffer_.end(), begin, begin + sizeof(T));
    }

    void u64(uint64_t value) { pod(value); }

    void string(const std::string& value) {
        u64(value.size());
        buffer_.insert(buffer_.end(), value.begin(), value.end());
    }

private:
    std::vector<unsigned char>& buffer_;
};

class Reader {
public:
    Reader(const unsigned char* data, size_t size)
        : data_(data), remaining_(size) {}

    template <class T>
    bool pod(T& value) {
        static_assert(std::is_trivially_copyable_v<T>);
        if (remaining_ < sizeof(T)) {
            return false;
        }
        std::memcpy(&value, data_, sizeof(T));
        data_ += sizeof(T);
        remaining_ -= sizeof(T);
        return true;
    }

    bool u64(uint64_t& value) { return pod(value); }

    bool string(std::string& value) {
        uint64_t length = 0;
        if (!pod(length)) {
            return false;
        }
        if (length > remaining_) {
            return false;
        }
        value.assign(reinterpret_cast<const char*>(data_), length);
        data_ += length;
        remaining_ -= length;
        return true;
    }

    bool ok() const { return remaining_ == 0; }

private:
    const unsigned char* data_;
    size_t remaining_;
};

uint32_t crc32Ieee(const std::vector<unsigned char>& bytes) {
    uint32_t crc = 0xFFFFFFFFu;
    for (unsigned char byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            uint32_t mask = 0u - (crc & 1u);
            crc = (crc >> 1) ^ (0xEDB88320u & mask);
        }
    }
    return ~crc;
}

bool readFile(const fs::path& path, std::vector<unsigned char>& bytes) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) {
        return false;
    }
    stream.seekg(0, std::ios::end);
    std::streamoff length = stream.tellg();
    if (length < 0) {
        return false;
    }
    stream.seekg(0, std::ios::beg);
    bytes.resize(static_cast<size_t>(length));
    if (length > 0 &&
        !stream.read(reinterpret_cast<char*>(bytes.data()), length)) {
        return false;
    }
    return true;
}

bool writeNew(const fs::path& path, const std::vector<unsigned char>& bytes) {
    std::ofstream stream(path, std::ios::binary | std::ios::trunc);
    if (!stream) {
        return false;
    }
    if (!bytes.empty()) {
        stream.write(reinterpret_cast<const char*>(bytes.data()),
                     static_cast<std::streamsize>(bytes.size()));
    }
    stream.flush();
    return static_cast<bool>(stream);
}

bool readCommitGeneration(const fs::path& commit_path,
                          const fs::path& data_path,
                          uint64_t& generation) {
    std::vector<unsigned char> bytes;
    if (!readFile(commit_path, bytes) || bytes.size() != sizeof(uint64_t)) {
        return false;
    }
    std::memcpy(&generation, bytes.data(), sizeof(uint64_t));
    if (generation == 0) {
        return false;
    }
    std::vector<unsigned char> data;
    if (!readFile(data_path, data) || data.size() < sizeof(Frame)) {
        return false;
    }
    Frame frame;
    std::memcpy(&frame, data.data(), sizeof(Frame));
    return frame.magic == kFormatMagic &&
           frame.format_version == kFormatVersion &&
           frame.generation == generation;
}

void replaceFile(const fs::path& source, const fs::path& target,
                 std::error_code& error) {
    // POSIX rename(2) atomically replaces an existing destination; on Windows
    // we request MOVEFILE_REPLACE_EXISTING explicitly.
#if !defined(_WIN32)
    fs::rename(source, target, error);
#else
    // Windows: MoveFileExW(MOVEFILE_REPLACE_EXISTING) is atomic for same-volume
    // files. std::filesystem cannot request that flag, so emit it directly.
    std::wstring source_w = source.wstring();
    std::wstring target_w = target.wstring();
    if (!MoveFileExW(source_w.c_str(), target_w.c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        error.assign(static_cast<int>(GetLastError()),
                     std::system_category());
    }
#endif
}

// Parses and authenticates one data file. Returns nullptr on any damage or
// version mismatch.
std::unique_ptr<Engine> decode(const std::vector<unsigned char>& bytes,
                               ErrorCode& error_out,
                               std::string& message) {
    error_out = ErrorCode::Ok;
    if (bytes.size() < sizeof(Frame)) {
        message = "checkpoint truncated";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    Frame frame;
    std::memcpy(&frame, bytes.data(), sizeof(Frame));
    if (frame.magic != kFormatMagic) {
        message = "bad checkpoint magic";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    if (frame.format_version != kFormatVersion) {
        message = "checkpoint format version mismatch";
        error_out = ErrorCode::CheckpointVersionMismatch;
        return nullptr;
    }
    std::vector<unsigned char> payload(
        bytes.begin() + sizeof(Frame), bytes.end());
    if (crc32Ieee(payload) != frame.crc) {
        message = "checkpoint checksum mismatch";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }

    Reader reader(payload.data(), payload.size());
    WireParams wire{};
    if (!reader.pod(wire)) {
        message = "checkpoint truncated";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    uint64_t observation_count = 0;
    if (!reader.u64(observation_count)) {
        message = "checkpoint truncated";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    if (observation_count > payload.size()) {
        message = "checkpoint corrupt";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    std::vector<Observation> observations;
    observations.reserve(static_cast<size_t>(observation_count));
    for (uint64_t i = 0; i < observation_count; ++i) {
        Observation obs;
        if (!reader.pod(obs.day) || !reader.string(obs.version) ||
            !reader.pod(obs.var) || !reader.pod(obs.pnl) ||
            !reader.pod(obs.seq)) {
            message = "checkpoint truncated";
            error_out = ErrorCode::CorruptCheckpoint;
            return nullptr;
        }
        observations.push_back(obs);
    }
    if (!reader.ok()) {
        message = "checkpoint has trailing bytes";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }

    Params params{wire.window, wire.level_num, wire.level_den};
    if (validateParams(params) != ErrorCode::Ok) {
        message = "checkpoint contains illegal window parameters";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    std::unique_ptr<Engine> engine;
    if (restoreEngine(params, frame.last_seq, observations, engine) !=
        ErrorCode::Ok) {
        message = "checkpoint contains duplicate keys";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    if (engine->digest() != frame.digest_value) {
        message = "checkpoint digest mismatch";
        error_out = ErrorCode::CorruptCheckpoint;
        return nullptr;
    }
    return engine;
}

bool loadSlot(const fs::path& data_path,
              std::unique_ptr<Engine>& engine, ErrorCode& error_out,
              std::string& message) {
    std::vector<unsigned char> bytes;
    if (!readFile(data_path, bytes)) {
        return false;
    }
    engine = decode(bytes, error_out, message);
    return engine != nullptr;
}

} // namespace

ErrorCode saveCheckpoint(const Engine& engine, const std::string& dir,
                         std::string& message) {
    std::error_code error;
    fs::create_directories(dir, error);

    // Choose the slot that is NOT the newest committed one so a crash during
    // writing never touches the last complete checkpoint.
    fs::path commit_a = fs::path(dir) / "commit-a";
    fs::path commit_b = fs::path(dir) / "commit-b";
    fs::path data_a = fs::path(dir) / "data-a.bin";
    fs::path data_b = fs::path(dir) / "data-b.bin";

    uint64_t gen_a = 0;
    uint64_t gen_b = 0;
    readCommitGeneration(commit_a, data_a, gen_a);
    readCommitGeneration(commit_b, data_b, gen_b);
    bool overwrite_a = gen_a <= gen_b; // overwrite older slot
    uint64_t next_generation = std::max(gen_a, gen_b) + 1;

    fs::path target_data = overwrite_a ? data_a : data_b;
    fs::path target_commit = overwrite_a ? commit_a : commit_b;
    fs::path temp_data = fs::path(dir) / (overwrite_a ? "data-a.tmp"
                                                      : "data-b.tmp");
    fs::path temp_commit = fs::path(dir) / (overwrite_a ? "commit-a.tmp"
                                                        : "commit-b.tmp");

    std::vector<unsigned char> payload;
    Writer writer(payload);
    WireParams wire{engine.params().window, engine.params().level_num,
                    engine.params().level_den};
    writer.pod(wire);
    std::vector<Observation> all;
    engine.forEachObservation([&](const Observation& obs) { all.push_back(obs); });
    writer.u64(all.size());
    for (const Observation& obs : all) {
        writer.pod(obs.day);
        writer.string(obs.version);
        writer.pod(obs.var);
        writer.pod(obs.pnl);
        writer.pod(obs.seq);
    }

    Frame frame;
    frame.generation = next_generation;
    frame.last_seq = engine.lastSeq();
    frame.digest_value = engine.digest();
    frame.crc = crc32Ieee(payload);

    std::vector<unsigned char> whole(sizeof(Frame) + payload.size());
    std::memcpy(whole.data(), &frame, sizeof(Frame));
    std::copy(payload.begin(), payload.end(), whole.begin() + sizeof(Frame));

    if (!writeNew(temp_data, whole)) {
        std::error_code ignore;
        fs::remove(temp_data, ignore);
        message = "failed to stage checkpoint data";
        return ErrorCode::IoError;
    }
    std::vector<unsigned char> commit_bytes(sizeof(uint64_t));
    std::memcpy(commit_bytes.data(), &next_generation, sizeof(uint64_t));
    if (!writeNew(temp_commit, commit_bytes)) {
        std::error_code ignore;
        fs::remove(temp_data, ignore);
        fs::remove(temp_commit, ignore);
        message = "failed to stage checkpoint commit";
        return ErrorCode::IoError;
    }

    // Publication: rename committed data and commit marker into place. On
    // POSIX rename is atomic; on Windows it replaces the destination. Both
    // moves target the *older* slot, so the newest good checkpoint is safe
    // even if the process dies between the two renames.
    std::error_code rename_error;
    replaceFile(temp_data, target_data, rename_error);
    if (rename_error) {
        std::error_code ignore;
        fs::remove(temp_data, ignore);
        fs::remove(temp_commit, ignore);
        message = "failed to publish checkpoint data";
        return ErrorCode::IoError;
    }
    replaceFile(temp_commit, target_commit, rename_error);
    if (rename_error) {
        // The data landed but the commit marker did not. The other slot's
        // commit still points at the previous complete checkpoint.
        std::error_code ignore;
        fs::remove(temp_commit, ignore);
        message = "failed to publish checkpoint commit marker";
        return ErrorCode::IoError;
    }
    message = "generation " + std::to_string(next_generation);
    return ErrorCode::Ok;
}

ErrorCode loadCheckpoint(const std::string& dir,
                         std::unique_ptr<Engine>& out, std::string& message) {
    out.reset();
    ErrorCode newest_error = ErrorCode::CorruptCheckpoint;
    std::string newest_message;
    fs::path commit_a = fs::path(dir) / "commit-a";
    fs::path commit_b = fs::path(dir) / "commit-b";
    fs::path data_a = fs::path(dir) / "data-a.bin";
    fs::path data_b = fs::path(dir) / "data-b.bin";

    uint64_t gen_a = 0;
    uint64_t gen_b = 0;
    readCommitGeneration(commit_a, data_a, gen_a);
    readCommitGeneration(commit_b, data_b, gen_b);

    // Newest committed slot first; fall back to the other one if the newest
    // data file is corrupt or unreadable.
    if (gen_a >= gen_b && gen_a != 0) {
        if (loadSlot(data_a, out, newest_error, newest_message)) {
            return ErrorCode::Ok;
        }
        out.reset();
        if (gen_b != 0 && loadSlot(data_b, out, newest_error, message)) {
            return ErrorCode::Ok;
        }
    } else if (gen_b != 0) {
        if (loadSlot(data_b, out, newest_error, newest_message)) {
            return ErrorCode::Ok;
        }
        std::string other_message;
        ErrorCode other_error = ErrorCode::CorruptCheckpoint;
        out.reset();
        if (gen_a != 0 && loadSlot(data_a, out, other_error, other_message)) {
            return ErrorCode::Ok;
        }
    }
    if (newest_message.empty()) {
        newest_message = "no complete checkpoint found";
    }
    message = newest_message;
    return newest_error;

}

} // namespace varbt
