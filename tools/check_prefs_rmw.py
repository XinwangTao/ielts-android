#!/usr/bin/env python3
"""静态检查：JSON 类设置的「读-改-写」必须包在 Prefs.editJson {} 里。

背景：`prefs.studyAiCache = put(prefs.studyAiCache, ...)` 在多线程下会丢更新
（两个评分/生成同时完成，后写者覆盖先写者）。约定写在 CLAUDE.md，这里把它变成闸门。

判定：赋值行（含其后两行）出现同一个 `prefs.<字段>`，且赋值行或上一行没有 `editJson`
→ 报错。只覆盖直接写法；跨多行先读后写（BankRepo.saveAnswers 那种）仍需人工遵守约定。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/txw/ielts"
ASSIGN = re.compile(r"^\s*prefs\.([A-Za-z_]\w*)\s*=\s*(.*)$")


def is_first_argument(rhs: str, field: str) -> bool:
    """引用是否出现在第一层参数的最前面。

    `prefs.x = put(prefs.x, ...)` 是读改写；`snapBand(input, prefs.x)` 只是拿旧值兜底，
    两者都引用自己，但只有前者会丢更新——用逗号位置区分，避免误报。
    """
    m = re.search(r"prefs\." + field + r"\b", rhs)
    if not m:
        return False
    depth = 0
    comma_at_depth_one = -1
    for i, ch in enumerate(rhs[: m.start()]):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 1:
            comma_at_depth_one = i
    return comma_at_depth_one < 0


def main() -> int:
    problems = []
    for f in sorted(SRC.rglob("*.kt")):
        if f.name == "Prefs.kt":
            continue
        lines = f.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            m = ASSIGN.match(line)
            if not m:
                continue
            field, rhs = m.group(1), m.group(2)
            window = [rhs] + lines[i + 1 : i + 3]
            if not any(re.search(r"prefs\." + field + r"\b", w) for w in window):
                continue
            context = (lines[i - 1] if i > 0 else "") + "\n" + line + "\n" + "\n".join(window)
            if "editJson" in context or "prefs-rmw-ok" in context:
                continue
            if not is_first_argument(rhs, field):
                # 只把旧值当兜底参数（如 snapBand(input, prefs.targetBand)），不是读改写。
                continue
            problems.append((f.relative_to(ROOT), i + 1, field))

    if not problems:
        print("   prefs 读改写检查 OK")
        return 0

    print("   prefs 读改写未加锁（必须包在 Prefs.editJson {} 里）：")
    for path, line, field in problems:
        print(f"   - {path}:{line}  prefs.{field}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
