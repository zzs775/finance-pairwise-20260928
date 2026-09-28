#pragma once

#include <cstdint>
#include <vector>

namespace varbt {

// Self-contained ordered multiset (AVL tree with subtree sizes).
// Supports duplicate values, insertion, deletion and 0-based order-statistic
// access (kth). No approximate operations are ever used.
class OrderedMultiset {
public:
    OrderedMultiset() = default;
    ~OrderedMultiset();
    OrderedMultiset(const OrderedMultiset&) = delete;
    OrderedMultiset& operator=(const OrderedMultiset&) = delete;

    void insert(int64_t value);
    bool eraseOne(int64_t value); // removes one occurrence; false if absent
    void clear();
    size_t size() const;
    bool empty() const;
    int64_t kth(size_t index) const; // 0-based ascending, requires index < size

public:
    struct Node;

private:
    Node* root_ = nullptr;
};

} // namespace varbt
