#ifndef OMAI_RAW_FILE_VIEW_H
#define OMAI_RAW_FILE_VIEW_H

#include <cstddef>
#include <cstdint>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace omaigenzo {

// Read-only view of a file behind a file descriptor.
// Regular files are mmap'd, so a content:// RAW is never copied; only the pages LibRaw and the
// decoder actually touch are faulted in. Providers that hand out pipes cannot be mapped, so those
// are streamed into memory (never into app storage), bounded by kMaxStreamedBytes.
class RawFileView {
public:
    enum class Access { Random, Sequential };

    static constexpr size_t kMaxStreamedBytes = 512u << 20;

    RawFileView() = default;
    RawFileView(const RawFileView &) = delete;
    RawFileView &operator=(const RawFileView &) = delete;

    ~RawFileView() {
        if (mMapped && mData) munmap(const_cast<uint8_t *>(mData), mSize);
    }

    bool open(int fd, Access access) {
        if (fd < 0) return false;
        struct stat st{};
        if (fstat(fd, &st) != 0) return false;

        if (S_ISREG(st.st_mode) && st.st_size > 0) {
            void *mapped = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
            if (mapped != MAP_FAILED) {
                // Embedded previews live in a few small regions: do not read the whole RAW ahead.
                madvise(mapped, static_cast<size_t>(st.st_size),
                        access == Access::Random ? MADV_RANDOM : MADV_SEQUENTIAL);
                mData = static_cast<const uint8_t *>(mapped);
                mSize = static_cast<size_t>(st.st_size);
                mMapped = true;
                return true;
            }
        }
        return stream(fd);
    }

    const uint8_t *data() const { return mData; }
    size_t size() const { return mSize; }
    bool mapped() const { return mMapped; }

private:
    bool stream(int fd) {
        constexpr size_t kChunk = 1u << 20;
        mOwned.clear();
        while (mOwned.size() < kMaxStreamedBytes) {
            size_t used = mOwned.size();
            mOwned.resize(used + kChunk);
            ssize_t got = read(fd, mOwned.data() + used, kChunk);
            if (got < 0) return false;
            mOwned.resize(used + static_cast<size_t>(got));
            if (got == 0) break;
        }
        if (mOwned.empty() || mOwned.size() >= kMaxStreamedBytes) return false;
        mOwned.shrink_to_fit();
        mData = mOwned.data();
        mSize = mOwned.size();
        return true;
    }

    const uint8_t *mData = nullptr;
    size_t mSize = 0;
    bool mMapped = false;
    std::vector<uint8_t> mOwned;
};

} // namespace omaigenzo

#endif // OMAI_RAW_FILE_VIEW_H
