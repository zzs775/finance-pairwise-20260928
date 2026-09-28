"""本地合成数据读取：CSV 与 JSON（空白容忍、顺序无关）。"""

import csv
import json


def read_rows(path: str) -> list[tuple]:
    """读取 CSV（origin,dev,amount[,version]）或 JSON 行/对象数组。"""
    if path.lower().endswith(".json"):
        return _read_json(path)
    return _read_csv(path)


def _read_csv(path: str) -> list[tuple]:
    rows: list[tuple] = []
    with open(path, "r", encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        for raw in reader:
            if not raw or not raw[0].strip() or raw[0].strip().lower() == "origin":
                continue
            values = [cell.strip() for cell in raw if cell.strip() != ""]
            if len(values) == 3:
                rows.append((values[0], values[1], values[2]))
            elif len(values) == 4:
                rows.append((values[0], values[1], values[2], int(values[3])))
            else:
                raise ValueError(f"CSV 行字段数非法: {raw}")
    return rows


def _read_json(path: str) -> list[tuple]:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    rows: list[tuple] = []
    for item in data:
        if isinstance(item, dict):
            version = item.get("version", 1)
            rows.append((item["origin"], item["dev"], item["amount"], version))
        else:
            rows.append(tuple(item))
    return rows
