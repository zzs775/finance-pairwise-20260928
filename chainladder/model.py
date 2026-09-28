"""三角形数据模型：单元、版本、形状与观察边界校验。"""

from dataclasses import dataclass, field
from decimal import Decimal

from .decimal_utils import D, q2, ZERO
from .errors import (
    DuplicateCellError,
    IllegalVersionError,
    InternalHoleError,
    NegativePaymentError,
    ShapeError,
)


@dataclass(frozen=True)
class Cell:
    """一个观测单元：事故期 origin、发展期 dev、累计赔付 amount、版本 version。"""

    origin: int
    dev: int
    amount: Decimal
    version: int = 1


@dataclass
class Triangle:
    """累计赔付三角形。以 (origin, dev) 索引当前版本单元。"""

    n: int
    cells: dict[tuple[int, int], Cell] = field(default_factory=dict)
    history: dict[tuple[int, int], list[Cell]] = field(default_factory=dict)
    origin_labels: list[int] = field(default_factory=list)

    @classmethod
    def from_rows(cls, rows: list[tuple[int, int, object]] | list[dict]) -> "Triangle":
        """从 (origin, dev, cumulative_amount) 行构建，输入顺序无关。"""
        parsed: list[tuple] = []
        seen: set[tuple[int, int]] = set()
        duplicates: list[tuple[int, int]] = []
        for raw in rows:
            if isinstance(raw, dict):
                origin = raw["origin"]
                dev = raw["dev"]
                amount = raw["amount"]
                version = raw.get("version", 1)
            else:
                origin, dev, amount = raw[:3]
                version = raw[3] if len(raw) > 3 else 1
            origin = int(origin)
            dev = int(dev)
            version = int(version)
            cell = Cell(origin=origin, dev=dev, amount=q2(D(amount)), version=version)
            key = (origin, dev)
            if key in seen:
                duplicates.append(key)
            seen.add(key)
            parsed.append(cell)
        if duplicates:
            first = sorted(duplicates)[0]
            raise DuplicateCellError(f"重复单元: origin={first[0]}, dev={first[1]}")
        origins = sorted({c.origin for c in parsed})
        n = len(origins)
        index = {origin: i for i, origin in enumerate(origins)}
        triangle = cls(n=n)
        triangle.origin_labels = origins
        for cell in parsed:
            i = index[cell.origin]
            stored = Cell(origin=i, dev=cell.dev, amount=cell.amount, version=cell.version)
            triangle.cells[(i, cell.dev)] = stored
            triangle.history[(i, cell.dev)] = [stored]
        triangle.validate()
        return triangle

    def get(self, origin: int, dev: int) -> Cell | None:
        return self.cells.get((origin, dev))

    def label_to_index(self, label: int) -> int:
        return self.origin_labels.index(label)

    def index_to_label(self, index: int) -> int:
        return self.origin_labels[index]

    def observed_boundary(self) -> dict[int, int]:
        """返回每个事故期已观察到的最大发展期。"""
        boundary: dict[int, int] = {}
        for (i, dev) in self.cells:
            if dev > boundary.get(i, 0):
                boundary[i] = dev
        return boundary

    def validate(self) -> None:
        """校验形状、累计非减、无重复、无内部空洞、非负。"""
        if self.n < 1:
            raise ShapeError("三角形至少需要一个事故期")
        boundary = self.observed_boundary()
        if set(boundary) != set(range(self.n)):
            raise ShapeError("存在没有任何观测的事故期")
        for i in sorted(boundary):
            max_dev = boundary[i]
            if max_dev > self.n - i:
                raise ShapeError(
                    f"事故期 {i} 的最大发展期 {max_dev} 超出三角形形状 {self.n - i}"
                )
            prev = None
            for dev in range(1, max_dev + 1):
                cell = self.cells.get((i, dev))
                if cell is None:
                    raise InternalHoleError(
                        f"内部空洞: origin_index={i}, dev={dev}, 边界={max_dev}"
                    )
                if cell.amount < ZERO:
                    raise NegativePaymentError(
                        f"负赔付: origin_index={i}, dev={dev}, amount={cell.amount}"
                    )
                if prev is not None and cell.amount < prev:
                    raise NegativePaymentError(
                        f"累计赔付非减违规: origin_index={i}, dev={dev}, "
                        f"{prev} -> {cell.amount}"
                    )
                prev = cell.amount
                if cell.version < 1:
                    raise IllegalVersionError(
                        f"非法版本号: origin_index={i}, dev={dev}, version={cell.version}"
                    )

    def latest_diagonal(self) -> dict[int, Decimal]:
        """每个事故期最新观察期的累计赔付。"""
        boundary = self.observed_boundary()
        return {i: self.cells[(i, boundary[i])].amount for i in range(self.n)}

    def row(self, origin: int) -> list[Decimal]:
        """按发展期 1..boundary 返回某事故期累计值列表。"""
        max_dev = self.observed_boundary()[origin]
        return [self.cells[(origin, dev)].amount for dev in range(1, max_dev + 1)]
