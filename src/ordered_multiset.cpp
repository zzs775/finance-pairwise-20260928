#include "varbt/ordered_multiset.hpp"

#include <algorithm>
#include <stdexcept>

namespace varbt {

struct OrderedMultiset::Node {
    int64_t value;
    int height = 1;
    size_t count = 1;
    size_t subtree = 1;
    Node* left = nullptr;
    Node* right = nullptr;
    explicit Node(int64_t v) : value(v) {}
};

namespace {

int heightOf(const OrderedMultiset::Node* node) {
    return node ? node->height : 0;
}

size_t sizeOf(const OrderedMultiset::Node* node) {
    return node ? node->subtree : 0;
}

void refresh(OrderedMultiset::Node* node) {
    node->height = 1 + std::max(heightOf(node->left), heightOf(node->right));
    node->subtree = node->count + sizeOf(node->left) + sizeOf(node->right);
}

int balanceOf(const OrderedMultiset::Node* node) {
    return node ? heightOf(node->left) - heightOf(node->right) : 0;
}

OrderedMultiset::Node* rotateRight(OrderedMultiset::Node* y) {
    OrderedMultiset::Node* x = y->left;
    OrderedMultiset::Node* t2 = x->right;
    x->right = y;
    y->left = t2;
    refresh(y);
    refresh(x);
    return x;
}

OrderedMultiset::Node* rotateLeft(OrderedMultiset::Node* x) {
    OrderedMultiset::Node* y = x->right;
    OrderedMultiset::Node* t2 = y->left;
    y->left = x;
    x->right = t2;
    refresh(x);
    refresh(y);
    return y;
}

OrderedMultiset::Node* rebalance(OrderedMultiset::Node* node) {
    refresh(node);
    int bf = balanceOf(node);
    if (bf > 1) {
        if (balanceOf(node->left) < 0) {
            node->left = rotateLeft(node->left);
        }
        return rotateRight(node);
    }
    if (bf < -1) {
        if (balanceOf(node->right) > 0) {
            node->right = rotateRight(node->right);
        }
        return rotateLeft(node);
    }
    return node;
}

OrderedMultiset::Node* insertNode(OrderedMultiset::Node* node, int64_t value) {
    if (!node) {
        return new OrderedMultiset::Node(value);
    }
    if (value == node->value) {
        ++node->count;
        ++node->subtree;
        return node;
    }
    if (value < node->value) {
        node->left = insertNode(node->left, value);
    } else {
        node->right = insertNode(node->right, value);
    }
    return rebalance(node);
}

OrderedMultiset::Node* minNode(OrderedMultiset::Node* node) {
    while (node->left) {
        node = node->left;
    }
    return node;
}

OrderedMultiset::Node* eraseNode(OrderedMultiset::Node* node, int64_t value) {
    if (!node) {
        return nullptr;
    }
    if (value < node->value) {
        node->left = eraseNode(node->left, value);
    } else if (value > node->value) {
        node->right = eraseNode(node->right, value);
    } else {
        if (node->count > 1) {
            --node->count;
            --node->subtree;
            return node;
        }
        if (!node->left || !node->right) {
            OrderedMultiset::Node* child = node->left ? node->left : node->right;
            delete node;
            return child;
        }
        OrderedMultiset::Node* successor = minNode(node->right);
        node->value = successor->value;
        node->count = successor->count;
        successor->count = 1; // erase exactly the promoted occurrence
        node->right = eraseNode(node->right, successor->value);
    }
    return rebalance(node);
}

bool contains(const OrderedMultiset::Node* node, int64_t value) {
    while (node) {
        if (value == node->value) {
            return true;
        }
        node = value < node->value ? node->left : node->right;
    }
    return false;
}

void destroy(OrderedMultiset::Node* node) {
    if (!node) {
        return;
    }
    destroy(node->left);
    destroy(node->right);
    delete node;
}

} // namespace

OrderedMultiset::~OrderedMultiset() {
    clear();
}

void OrderedMultiset::insert(int64_t value) {
    root_ = insertNode(root_, value);
}

bool OrderedMultiset::eraseOne(int64_t value) {
    if (!contains(root_, value)) {
        return false;
    }
    root_ = eraseNode(root_, value);
    return true;
}

void OrderedMultiset::clear() {
    destroy(root_);
    root_ = nullptr;
}

size_t OrderedMultiset::size() const {
    return sizeOf(root_);
}

bool OrderedMultiset::empty() const {
    return root_ == nullptr;
}

int64_t OrderedMultiset::kth(size_t index) const {
    const Node* node = root_;
    while (node) {
        size_t left_size = sizeOf(node->left);
        if (index < left_size) {
            node = node->left;
        } else if (index < left_size + node->count) {
            return node->value;
        } else {
            index -= left_size + node->count;
            node = node->right;
        }
    }
    throw std::out_of_range("kth index out of range");
}

} // namespace varbt
