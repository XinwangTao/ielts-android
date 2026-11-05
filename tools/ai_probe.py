#!/usr/bin/env python3
"""雅思 App AI 功能 PC 端探针。

用途：不装机，直接用电脑上的 CC Switch（Anthropic 兼容代理）跑 App 里的真实提示词，
检查每类 AI 输出的 JSON 结构、分项分、字数与字段完整性。手机实测前先在这里收口。

关键点：提示词不是手抄的，from_kotlin() 直接从仓库源码抽取，避免测试与线上漂移。

用法：
  python tools/ai_probe.py                      # 全部用例
  python tools/ai_probe.py --only writing       # 只跑名字含 writing 的
  python tools/ai_probe.py --report docs/ai-probe-report.md
  python tools/ai_probe.py --base http://127.0.0.1:15721 --model <model>

退出码：全部通过 0；有失败 1。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import textwrap
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LLM = ROOT / "app/src/main/java/com/txw/ielts/llm"
DEFAULT_BASE = "http://127.0.0.1:15721"
DEFAULT_MODEL = "ccs-claude-openai--deepseek-v4.1-flash[1m]"


def from_kotlin(filename: str, name: str) -> str:
    """从 Kotlin 源码抽取 `val NAME = \"\"\"...\"\"\"` 的内容（等价 trimIndent）。"""
    src = (LLM / filename).read_text(encoding="utf-8")
    m = re.search(
        r"(?:private|internal)\s+val\s+" + re.escape(name) + r'\s*=\s*"""(.*?)"""',
        src,
        re.S,
    )
    if not m:
        raise SystemExit(f"找不到提示词 {name} @ {filename}")
    return textwrap.dedent(m.group(1)).strip()


def call_model(base: str, model: str, system: str, user: str, max_tokens: int = 4000) -> str:
    payload = {
        "model": model,
        "max_tokens": max_tokens,
        "temperature": 0.2,
        # 与 App 的 LlmClient.chat 对齐：Zhipu/DeepSeek 的思考 token 会吃满 max_tokens，
        # 正文还没开始就被截断（实测写作/口语评分返回空或半截 JSON）。
        "thinking": {"type": "disabled"},
        "system": system,
        "messages": [{"role": "user", "content": user}],
    }
    req = urllib.request.Request(
        base.rstrip("/") + "/v1/messages",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "content-type": "application/json",
            "x-api-key": "local-test",
            "anthropic-version": "2023-06-01",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=240) as r:
        obj = json.load(r)
    return "".join(b.get("text", "") for b in obj.get("content", []) if b.get("type") == "text")


def extract_json_like_kotlin(text: str) -> str:
    """镜像 ScoreSanitizer.extractJson：剥围栏、按括号配对取对象、把字符串内裸换行换成空格。"""
    cleaned = text.replace("```json", "").replace("```", "").strip()
    start = cleaned.find("{")
    if start < 0:
        raise ValueError("没有 JSON 对象")
    depth = 0
    in_str = False
    esc = False
    end = -1
    for i in range(start, len(cleaned)):
        c = cleaned[i]
        if esc:
            esc = False
        elif c == "\\":
            esc = True
        elif c == '"':
            in_str = not in_str
        elif not in_str and c == "{":
            depth += 1
        elif not in_str and c == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end < 0:
        raise ValueError("JSON 不完整（可能被 max_tokens 截断）")
    raw = cleaned[start : end + 1]
    out = []
    in_str = False
    esc = False
    for c in raw:
        if esc:
            out.append(c)
            esc = False
        elif c == "\\":
            out.append(c)
            esc = True
        elif c == '"':
            in_str = not in_str
            out.append(c)
        elif in_str and c in "\n\r\t":
            out.append(" ")
        else:
            out.append(c)
    return "".join(out)


def json_body(text: str) -> dict:
    return json.loads(extract_json_like_kotlin(text))


def half(v) -> bool:
    return isinstance(v, (int, float)) and 0 <= float(v) <= 9 and abs(float(v) * 2 - round(float(v) * 2)) < 1e-6


def words(s: str) -> int:
    return len([w for w in re.split(r"\s+", s.strip()) if w])


def has_cjk(s: str) -> bool:
    return bool(re.search(r"[一-鿿]", s))


def check_keys(d: dict, names: list[str], issues: list[str], prefix: str = "") -> None:
    for n in names:
        if n not in d:
            issues.append(f"缺字段 {prefix}{n}")


def check_band(d: dict, names: list[str], issues: list[str]) -> None:
    for n in names:
        v = d.get(n)
        if not half(v):
            issues.append(f"{n}={v!r} 不是 0-9 的 0.5 步长")


# ---------------------------------------------------------------- 用例数据

ESSAY_T2 = """Some people believe that university students should be free to study whatever they are passionate about, while others argue that they should only choose subjects with clear employment value. In my opinion, both perspectives have merit, but a balanced approach is the most sensible.

On the one hand, allowing students to follow their interests can lead to greater motivation and academic success. When people study a subject they genuinely enjoy, they are more likely to engage deeply with the material, persist through difficulties, and produce original work. For example, a student who loves history may spend extra hours in the library and eventually become a researcher who contributes to public understanding of the past. If universities force everyone into a narrow set of useful courses, this kind of curiosity may be lost.

On the other hand, higher education is expensive, and graduates need to repay loans and find work. Subjects such as medicine, engineering and computer science provide clear routes into employment, while some arts courses may not. Governments and universities therefore have a responsibility to offer guidance about labour-market trends and to ensure that students understand the financial consequences of their choices.

However, the idea that only vocational subjects are valuable is too simplistic. Critical thinking, communication and cultural awareness are also important for society, and these skills are developed through a wide range of disciplines. A better solution is to combine flexibility with support: students should be able to choose their major, but universities should provide career counselling, internships and opportunities to combine subjects.

In conclusion, I believe students should be allowed to study what they love, provided that they receive honest information about employment prospects. A system that respects both personal interest and practical responsibility is likely to produce happier graduates and a more adaptable workforce."""

REPORT_T1 = """The table compares the percentage of household spending in six categories in a European country in 1995 and 2015.

Overall, housing became the largest expense by 2015, while the share spent on food fell significantly. Most other categories changed only slightly.

In 1995, food accounted for the largest proportion at 24%, followed by housing at 18% and transport at 15%. Entertainment and healthcare each represented 9% and 6% respectively, while other items made up 28%.

By 2015, the pattern had changed. Housing rose to 26%, overtaking food, which dropped to 18%. Transport remained almost stable at 16%. Entertainment and healthcare increased modestly to 11% and 8%. The other category fell from 28% to 21%.

In summary, the main changes were the sharp rise in housing costs and the decline in food spending, while transport and most other categories remained relatively stable."""

# 弱作文：约 80 词、简单句、零具体例证。用来验评分是否有区分度（应明显低于 ESSAY_T2）。
ESSAY_WEAK = """Some people think students should study what they like. Others think they should study useful subjects. I agree with the second view. First, useful subjects help students find jobs. For example, computer science graduates can work in big companies. Second, useful subjects are good for the economy. If students study art, they may not find a job. So I think students should study useful subjects. In conclusion, useful subjects are better."""

TRANSCRIPT_SPEAKING = """I would like to talk about learning to swim, which was really difficult for me at the beginning. I learned it when I was 22 years old, during a summer holiday at the coast. My friend Tom taught me the basics in a shallow pool before we moved to the sea. At first I was terrified of putting my face under the water, and I could not float at all. The main reason it was difficult is that I had a bad experience as a child, so I panicked easily. I practiced for about half an hour every morning for two weeks, and I watched short videos to copy the arm movements. Slowly I became confident, and by the end of the holiday I could swim 50 meters without stopping. I feel proud of it now, because it taught me that patience and small daily steps can overcome fear."""

TABLE_DATA = """Food: 24% (1995) → 18% (2015)
Housing: 18% → 26%
Transport: 15% → 16%
Entertainment: 9% → 11%
Healthcare: 6% → 8%
Other: 28% → 21%"""

VOCAB_TEXT = """Many people believe that universities should only offer courses that lead directly to jobs. I disagree with this view. First, education is not only about employment; it also helps people become responsible citizens. Second, students who study subjects they enjoy are more motivated and less likely to drop out. For example, a friend of mine studied history and now works in a museum, where she explains the past to children. Her job is useful and she is happy."""

VOCAB_FIXES = [
    ("Many people believe that universities should only offer courses that lead directly to jobs.", "表达正确但略平", "Many people argue that universities should offer only courses with direct employment value."),
    ("students who study subjects they enjoy are more motivated", "主谓一致与搭配", "students who study subjects they enjoy tend to be more motivated"),
    ("Her job is useful and she is happy.", "并列句过于简单", "Her work is both socially useful and personally fulfilling."),
]


def cases() -> list[dict]:
    sys_w = from_kotlin("LocalLlmEngine.kt", "WRITING_SYSTEM")
    sys_t1 = from_kotlin("LocalLlmEngine.kt", "TASK1_SYSTEM")
    sys_sp = from_kotlin("LocalLlmEngine.kt", "SPEAKING_SYSTEM")
    sys_out2 = from_kotlin("StudyAi.kt", "TASK2_OUTLINE_SYSTEM")
    sys_out1 = from_kotlin("StudyAi.kt", "TASK1_OUTLINE_SYSTEM")
    sys_sample = from_kotlin("StudyAi.kt", "SPEAKING_SYSTEM")
    sys_vocab = from_kotlin("StudyAi.kt", "VOCAB_SYSTEM")
    sys_read = from_kotlin("LlmClient.kt", "READING_GEN_SYSTEM")
    sys_listen = from_kotlin("LlmClient.kt", "LISTENING_GEN_SYSTEM")
    sys_explain = from_kotlin("LlmClient.kt", "BANK_EXPLAIN_SYSTEM")

    weakness = ("\n\nThis student's recurring weaknesses recorded from past attempts: 字数与展开; 主谓一致. "
                "Check specifically whether these problems appear in this attempt. If they do, list them FIRST in top_fixes "
                "and state clearly whether the student is improving on them.")
    topic2 = ("Some people believe university students should study whatever they like. Others think they should only "
              "study subjects that will be useful. Discuss both views and give your own opinion.")
    t1_topic = "The table below shows the percentage of household spending in a European country in 1995 and 2015.\n\n" + TABLE_DATA

    return [
        dict(name="scoring_writing_task2", system=sys_w,
             user=f"Task: {topic2}\nWord count: {words(ESSAY_T2)}\n\nEssay:\n{ESSAY_T2}{weakness}",
             validate="writing"),
        dict(name="scoring_writing_weak", system=sys_w,
             user=f"Task: {topic2}\nWord count: {words(ESSAY_WEAK)}\n\nEssay:\n{ESSAY_WEAK}",
             validate="writing"),
        dict(name="scoring_writing_task1", system=sys_t1,
             user=f"Chart data:\n{t1_topic}\n\nWord count: {words(REPORT_T1)}\n\nReport:\n{REPORT_T1}",
             validate="writing_task1"),
        dict(name="scoring_speaking", system=sys_sp,
             user=("Part: Part 2\nQuestion: Describe a skill you learned that was difficult at first.\n"
                   f"Speaking time: 95s\nWord count: {words(TRANSCRIPT_SPEAKING)}\nWords per minute: 94\n"
                   f"Filler words (um/uh/er): 0\n\nTranscript (from offline ASR, punctuation unreliable):\n{TRANSCRIPT_SPEAKING}{weakness}"),
             validate="speaking"),
        dict(name="outline_task2", system=sys_out2, user=f"Task: {topic2}", validate="outline"),
        dict(name="outline_task1", system=sys_out1, user=f"Task: {t1_topic}", validate="outline"),
        dict(name="speaking_sample", system=sys_sample,
             user="Part: Part 2\nQuestion: Describe a skill you learned that was difficult at first. "
                  "You should say what it was, how you learned it, why it was difficult, and explain how you feel about it now.",
             validate="sample"),
        dict(name="vocab", system=sys_vocab,
             user="Essay:\n" + VOCAB_TEXT + "\n\nCorrections:\n" + "\n".join(
                 f"- {s} -> {b} ({i})" for s, i, b in VOCAB_FIXES),
             validate="vocab"),
        dict(name="reading_generation", system=sys_read,
             user="Difficulty: 7.0\nQuestion types: choice, tfng, gap\nQuestion count: 6",
             validate="reading", count=6),
        dict(name="reading_generation_8", system=sys_read,
             user="Difficulty: 7.0\nQuestion types: choice, tfng, gap\nQuestion count: 8",
             validate="reading", count=8),
        dict(name="listening_generation", system=sys_listen,
             user="Difficulty: 6.5\nQuestion types: choice, gap\nQuestion count: 6",
             validate="listening", count=6),
        dict(name="listening_generation_8", system=sys_listen,
             user="Difficulty: 6.5\nQuestion types: choice, gap\nQuestion count: 8",
             validate="listening", count=8),
        dict(name="bank_explain", system=sys_explain,
             user=("Question: The writer says the museum was originally built to store private collections.\n"
                   "Correct answer: NOT GIVEN\nYour answer: TRUE\n"
                   "Brief hint: 原文只提到 19 世纪建造，没有说明最初用途。"),
             validate="explain"),
        dict(name="speaking_follow_up",
             system="You are an IELTS Speaking Part 3 examiner. Ask ONE natural follow-up question based on the candidate's answer. Output only the question sentence, no quotes, no explanation.",
             user=("Previous question: Do you think schools should teach practical skills?\n"
                   "Candidate's answer: Yes, I think schools focus too much on exams. Students should learn cooking, "
                   "personal finance and basic repair because these skills are useful in daily life."),
             validate="followup"),
        dict(name="chart_prompt_polish",
             system="You are an IELTS Writing Task 1 examiner. Rewrite the given Task 1 prompt in natural exam-authentic English. Keep every number, category and unit exactly as given; do not add or remove data. Output only the prompt sentence(s), no explanation.",
             user="Chart type: table\nTitle: household spending by category in 1995 and 2015\nUnit: %\nData:\n" + TABLE_DATA,
             validate="polish", expect_numbers=["1995", "2015"]),
    ]


# ---------------------------------------------------------------- 校验器

def validate(case: dict, text: str) -> list[str]:
    issues: list[str] = []
    if not text.strip():
        return ["模型没有输出"]
    v = case["validate"]
    if v == "followup":
        q = text.strip().strip('"').strip()
        if len(q) > 240:
            issues.append(f"追问过长（{len(q)} 字符）")
        if "?" not in q:
            issues.append("追问没有问号")
        return issues
    if v == "polish":
        for n in case.get("expect_numbers", []):
            if n not in text:
                issues.append(f"润色丢了数字 {n}%")
        if re.search(r"[{}]", text):
            issues.append("润色输出里出现 JSON/花括号")
        return issues

    try:
        d = json_body(text)
    except Exception as e:
        return [f"JSON 解析失败：{e}；原文 {text[:160]!r}"]

    if v in ("writing", "writing_task1"):
        check_keys(d, ["sentence_fixes", "GRA", "LR", "CC", "overall", "top_fixes", "polished"], issues)
        check_keys(d, ["TA"] if v == "writing_task1" else ["TR"], issues)
        check_band(d, ["GRA", "LR", "CC", "overall", "TA" if v == "writing_task1" else "TR"], issues)
        sf = d.get("sentence_fixes")
        if not isinstance(sf, list) or not (2 <= len(sf) <= 5):
            issues.append(f"sentence_fixes 条数={len(sf) if isinstance(sf, list) else 'N/A'}（要求 2-5）")
        else:
            for i, item in enumerate(sf):
                if not all(item.get(k) for k in ("sentence", "issue", "better")):
                    issues.append(f"sentence_fixes[{i}] 字段不全")
                if not has_cjk(item.get("issue", "")):
                    issues.append(f"sentence_fixes[{i}].issue 不是中文")
        fixes = d.get("top_fixes")
        if not isinstance(fixes, list) or len(fixes) != 3:
            issues.append(f"top_fixes 条数={len(fixes) if isinstance(fixes, list) else 'N/A'}（要求 3）")
        if not str(d.get("polished", "")).strip():
            issues.append("polished 为空")
        # overall 不校验：App 的 ScoreSanitizer 会按四项均值重算，模型自报值只作参考。
    elif v == "speaking":
        check_keys(d, ["sentence_fixes", "GRA", "LR", "FC", "P", "overall", "top_fixes", "improved"], issues)
        check_band(d, ["GRA", "LR", "FC", "P", "overall"], issues)
        sf = d.get("sentence_fixes")
        if not isinstance(sf, list) or not (2 <= len(sf) <= 5):
            issues.append(f"sentence_fixes 条数={len(sf) if isinstance(sf, list) else 'N/A'}（要求 2-5）")
        fixes = d.get("top_fixes")
        if not isinstance(fixes, list) or len(fixes) != 3:
            issues.append(f"top_fixes 条数={len(fixes) if isinstance(fixes, list) else 'N/A'}（要求 3）")
    elif v == "outline":
        check_keys(d, ["stance", "arguments", "phrases"], issues)
        if not str(d.get("stance", "")).strip():
            issues.append("stance 为空")
        args = d.get("arguments")
        if not isinstance(args, list) or len(args) != 3:
            issues.append(f"arguments 条数={len(args) if isinstance(args, list) else 'N/A'}（要求 3）")
        else:
            for i, a in enumerate(args):
                if not str(a.get("point", "")).strip():
                    issues.append(f"arguments[{i}].point 为空")
                if not str(a.get("explain", "")).strip():
                    issues.append(f"arguments[{i}].explain 为空")
        phrases = d.get("phrases")
        if not isinstance(phrases, list) or len(phrases) != 5:
            issues.append(f"phrases 条数={len(phrases) if isinstance(phrases, list) else 'N/A'}（要求 5）")
    elif v == "sample":
        check_keys(d, ["answer", "keywords", "structure"], issues)
        ans = str(d.get("answer", ""))
        wc = words(ans)
        if not (150 <= wc <= 260):
            issues.append(f"answer 词数={wc}（Part 2 期望 180-220，容忍 150-260）")
        kw = d.get("keywords")
        if not isinstance(kw, list) or len(kw) != 5:
            issues.append(f"keywords 条数={len(kw) if isinstance(kw, list) else 'N/A'}（要求 5）")
        st = d.get("structure")
        if not isinstance(st, list) or len(st) != 3:
            issues.append(f"structure 条数={len(st) if isinstance(st, list) else 'N/A'}（要求 3）")
        elif not all(has_cjk(x) for x in st):
            issues.append("structure 提示不是中文")
    elif v == "vocab":
        items = d.get("items")
        if not isinstance(items, list) or not (5 <= len(items) <= 8):
            issues.append(f"items 条数={len(items) if isinstance(items, list) else 'N/A'}（要求 5-8）")
        else:
            for i, it in enumerate(items):
                if not str(it.get("phrase", "")).strip():
                    issues.append(f"items[{i}].phrase 为空")
                if not has_cjk(it.get("meaning", "")):
                    issues.append(f"items[{i}].meaning 不是中文")
                if not str(it.get("example", "")).strip():
                    issues.append(f"items[{i}].example 为空")
                if not (2 <= words(str(it.get("phrase", ""))) <= 6):
                    issues.append(f"items[{i}].phrase 词数不是 2-6")
    elif v in ("reading", "listening"):
        check_keys(d, ["title", "questions"], issues)
        check_keys(d, ["passage"] if v == "reading" else ["transcript"], issues)
        body = str(d.get("passage") if v == "reading" else d.get("transcript", ""))
        wc = words(body)
        lo, hi = (300, 550) if v == "reading" else (200, 450)
        if not (lo <= wc <= hi):
            issues.append(f"{'passage' if v == 'reading' else 'transcript'} 词数={wc}（期望 {lo}-{hi}）")
        qs = d.get("questions")
        expected_count = case.get("count", 6)
        if not isinstance(qs, list) or len(qs) != expected_count:
            issues.append(f"questions 条数={len(qs) if isinstance(qs, list) else 'N/A'}（要求 {expected_count}）")
        else:
            for i, q in enumerate(qs):
                for k in ("type", "prompt", "answers", "explain", "explain_en", "keys"):
                    if k not in q:
                        issues.append(f"questions[{i}] 缺 {k}")
                if not has_cjk(str(q.get("explain", ""))):
                    issues.append(f"questions[{i}].explain 不是中文")
                if not str(q.get("explain_en", "")).strip():
                    issues.append(f"questions[{i}].explain_en 为空")
                if q.get("type") == "choice" and len(q.get("options") or []) != 4:
                    issues.append(f"questions[{i}] choice 选项不是 4 个")
                if q.get("type") == "tfng" and len(q.get("options") or []) != 3:
                    issues.append(f"questions[{i}] tfng 选项不是 3 个")
                if v == "listening" and q.get("type") == "gap":
                    answers = [str(a) for a in (q.get("answers") or [])]
                    # 允许多种记法（35 / thirty-five）；至少一个必须在原文出现，否则题目无法作答。
                    if answers and not any(a.lower() in body.lower() for a in answers):
                        issues.append(f"questions[{i}] gap 答案 {answers!r} 均未在 transcript 原文出现")
    elif v == "explain":
        check_keys(d, ["cn", "en", "keys"], issues)
        if not has_cjk(str(d.get("cn", ""))):
            issues.append("cn 不是中文")
        if not str(d.get("en", "")).strip():
            issues.append("en 为空")
        if not isinstance(d.get("keys"), list) or len(d.get("keys")) < 2:
            issues.append("keys 少于 2 个")
    return issues


# ---------------------------------------------------------------- 主流程

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--only", default="")
    ap.add_argument("--report", default="")
    ap.add_argument("--fixtures", default="", help="把原始输出写入该目录（供 AiProbeFixtureTest 使用）")
    args = ap.parse_args()

    all_cases = cases()
    selected = [c for c in all_cases if args.only.lower() in c["name"].lower()]
    if not selected:
        raise SystemExit(f"没有匹配 --only {args.only!r} 的用例")

    results = []
    for c in selected:
        t0 = time.time()
        print(f"[{c['name']}] 请求中…", flush=True)
        try:
            raw = call_model(args.base, args.model, c["system"], c["user"])
            issues = validate(c, raw)
        except urllib.error.HTTPError as e:
            raw = ""
            issues = [f"HTTP {e.code}: {e.read()[:200]!r}"]
        except Exception as e:
            raw = ""
            issues = [f"调用失败：{e}"]
        elapsed = time.time() - t0
        results.append(dict(name=c["name"], ok=not issues, issues=issues, raw=raw, elapsed=elapsed))
        mark = "PASS" if not issues else "FAIL"
        print(f"[{c['name']}] {mark} ({elapsed:.1f}s) {('; '.join(issues))[:200]}", flush=True)

    total = len(results)
    failed = sum(1 for r in results if not r["ok"])
    by_name = {r["name"]: r for r in results}
    calibration = ""
    if "scoring_writing_task2" in by_name and "scoring_writing_weak" in by_name:
        try:
            s = json_body(by_name["scoring_writing_task2"]["raw"]).get("overall")
            w = json_body(by_name["scoring_writing_weak"]["raw"]).get("overall")
            calibration = f"强作文 overall={s}，弱作文 overall={w}"
            if isinstance(s, (int, float)) and isinstance(w, (int, float)) and w >= s:
                calibration += "（警告：弱作文不低于强作文，区分度不足）"
            print(f"[校准] {calibration}")
        except Exception:
            calibration = "校准比较失败（原始输出无法解析）"
    print(f"\n== {total - failed}/{total} 通过，{failed} 失败 ==")

    if args.report:
        p = Path(args.report)
        if not p.is_absolute():
            p = ROOT / p
        p.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# 雅思 App AI 功能探针报告",
            "",
            f"- 模型：`{args.model}`",
            f"- 接口：`{args.base}`",
            f"- 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
            f"- 结果：**{total - failed}/{total} 通过**",
            "",
            "| 用例 | 结果 | 耗时 | 问题 |",
            "|---|---|---|---|",
        ]
        for r in results:
            lines.append(f"| {r['name']} | {'✅' if r['ok'] else '❌'} | {r['elapsed']:.1f}s | {'; '.join(r['issues']).replace('|','/') or '—'} |")
        if calibration:
            lines += ["", f"**评分区分度**：{calibration}"]
        lines += ["", "## 原始输出", ""]
        for r in results:
            lines += [f"### {r['name']}", "", "```json", r["raw"][:20000], "```", ""]
        p.write_text("\n".join(lines), encoding="utf-8")
        print(f"报告已写入 {p}")

    if args.fixtures:
        fd = Path(args.fixtures)
        if not fd.is_absolute():
            fd = ROOT / fd
        fd.mkdir(parents=True, exist_ok=True)
        for r in results:
            (fd / f"{r['name']}.txt").write_text(r["raw"], encoding="utf-8")
        print(f"夹具已写入 {fd}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
