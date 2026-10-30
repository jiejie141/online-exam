#!/usr/bin/env python3
"""把外部题库转换成本项目导入器认识的 JSONL。

为什么要单独一个脚本，而不是让 Java 直接读 C-Eval / MMLU 的原始格式：

    数据来源是最常变的东西，导入逻辑却要稳定。如果把"认别人格式"写进 Java，
    每接一个新来源就得改后端、跑测试、重新发布；而来源方随时可能改列名、
    改目录结构、甚至停更。所以边界划在这里：**脚本负责认格式，Java 只认一种
    中间格式**。脚本坏了改脚本，后端不用动。

本脚本只用标准库（和项目里 scripts/e2e_check.py 一样的约束）：
    数据来源常在离线机器上，装不了包的时候也得能跑。

用法
----
C-Eval（下载后解压，目录里是每个学科一个 csv）：
    python scripts/convert_question_bank.py ceval \
        --src ~/C-Eval/data/val --out /tmp/ceval.jsonl --limit 500

MMLU（同上，data/val/*.csv）：
    python scripts/convert_question_bank.py mmlu \
        --src ~/mmlu/data/val --out /tmp/mmlu.jsonl --limit 500

任意 CSV（Excel 另存为 CSV，列名用参数指定）：
    python scripts/convert_question_bank.py csv \
        --src 题库.csv --out /tmp/bank.jsonl \
        --subject-col 科目 --stem-col 题干 --answer-col 答案

输出格式（每行一个对象，可直接粘进「题库管理 → 批量导入」）：
    {"subject":"...","type":"single","stem":"...",
     "options":[{"key":"A","text":"..."}, ...],"answer":"A",
     "difficulty":1,"external_id":"...","source":"ceval",
     "source_ref":"...","license":"..."}

⚠️ 版权：本脚本只做格式转换，不改变数据本身的许可。
   C-Eval / CMMLU 是 CC BY-NC-SA 4.0（非商用 + 相同方式共享 + 署名），
   MMLU 是 MIT。**课程/作业/演示属于非商用，可以；拿去卖不行。**
   转出来的文件不要提交进公开仓库 —— 因此 .gitignore 里挡了 *.jsonl 之外的
   做法是错的，正确做法是把它们放在 var/ 或 /tmp 下，别放仓库里。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys

LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

# 各来源的许可证，直接写进每一条记录，方便以后一条 SQL 盘查
LICENSES = {
    "ceval": "CC BY-NC-SA 4.0",
    "cmmlu": "CC BY-NC-SA 4.0",
    "mmlu": "MIT",
    "csv": None,
    "excel": None,
}


def die(msg: str) -> None:
    print(f"错误: {msg}", file=sys.stderr)
    raise SystemExit(2)


def read_csv_rows(path: str) -> list[dict]:
    """读 CSV。utf-8-sig 是为了吃掉 Excel 的 BOM —— 带 BOM 时第一列的列名
    会变成 '\\ufeffquestion'，然后"找不到 question 列"这种报错能查很久。"""
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def pick(row: dict, *names: str) -> str:
    for n in names:
        if n in row and row[n] is not None and str(row[n]).strip():
            return str(row[n]).strip()
    return ""


def answer_to_letters(raw: str, n_options: int, *, zero_based: bool) -> str:
    """把各种答案写法归一到字母串。

    这里刻意不"智能猜测"：0 基还是 1 基只能由调用方按来源约定指定。
    猜错的后果是整场考试所有单选题都指向邻项，而且没有任何报错。
    """
    s = raw.strip().upper().replace(" ", "").replace(",", "").replace("，", "")
    if not s:
        return ""
    if all(c in LETTERS for c in s):
        return s
    if all(c.isdigit() for c in s):
        out = []
        for c in s:
            idx = int(c) if not zero_based else int(c) + 1
            if not 1 <= idx <= n_options:
                die(f"答案下标 {c} 超出选项个数 {n_options}（当前约定 {'0' if zero_based else '1'} 基）")
            out.append(LETTERS[idx - 1])
        return "".join(sorted(out))
    die(f"无法识别的答案写法: {raw!r}")


def build_record(*, subject: str, stem: str, options: list[str], answer: str,
                 external_id: str, source: str, source_ref: str, difficulty: int = 1) -> dict:
    if len(options) < 2:
        return {}
    return {
        "subject": subject,
        "type": "single",
        "stem": stem,
        "options": [
            {"key": LETTERS[i], "text": text} for i, text in enumerate(options)
        ],
        "answer": answer,
        "difficulty": difficulty,
        "external_id": external_id,
        "source": source,
        "source_ref": source_ref,
        "license": LICENSES.get(source),
    }


def convert_ceval_or_mmlu(src_dir: str, source: str, limit: int, zero_based: bool) -> list[dict]:
    """C-Eval 与 MMLU 的 CSV 形状几乎一致：id/question/A/B/C/D/answer。

    区别只有一个：MMLU 的 answer 在多数转写版本里是 0~3 的下标，
    C-Eval 是字母。这个差别用 zero_based 显式表达，不靠猜。
    """
    if not os.path.isdir(src_dir):
        die(f"不是一个目录: {src_dir}")
    files = sorted(f for f in os.listdir(src_dir) if f.endswith(".csv"))
    if not files:
        die(f"目录里没有 csv: {src_dir}")

    out: list[dict] = []
    for name in files:
        if limit and len(out) >= limit:
            break
        subject = name.rsplit("_", 1)[0].rsplit(".", 1)[0]  # abstract_algebra_val.csv -> abstract_algebra
        rows = read_csv_rows(os.path.join(src_dir, name))
        for i, row in enumerate(rows):
            if limit and len(out) >= limit:
                break
            stem = pick(row, "question", "Question", "stem")
            options = [pick(row, k) for k in ("A", "B", "C", "D")]
            raw_answer = pick(row, "answer", "Answer")
            if not stem or not raw_answer or any(not o for o in options):
                continue
            answer = answer_to_letters(raw_answer, len(options), zero_based=zero_based)
            rec = build_record(
                subject=subject, stem=stem, options=options, answer=answer,
                external_id=pick(row, "id") or f"{subject}-{i}",
                source=source, source_ref=f"{name} 第 {i + 2} 行",
            )
            if rec:
                out.append(rec)
    return out


def convert_csv(src: str, subject_col: str, stem_col: str, answer_col: str,
                options_col: str, limit: int) -> list[dict]:
    rows = read_csv_rows(src)
    out: list[dict] = []
    for i, row in enumerate(rows):
        if limit and len(out) >= limit:
            break
        stem = pick(row, stem_col)
        raw_answer = pick(row, answer_col)
        if not stem or not raw_answer:
            continue
        if options_col:
            # 选项写在一列里，用 | 或 ; 分隔：甲|乙|丙|丁
            raw = pick(row, options_col).replace("；", "|").replace(";", "|")
            options = [p.strip() for p in raw.split("|") if p.strip()]
        else:
            options = [pick(row, k) for k in ("A", "B", "C", "D")]
        answer = answer_to_letters(raw_answer, len(options), zero_based=False)
        rec = build_record(
            subject=pick(row, subject_col) or "未分类", stem=stem, options=options,
            answer=answer, external_id=str(i + 2), source="excel",
            source_ref=f"{os.path.basename(src)} 第 {i + 2} 行",
        )
        if rec:
            out.append(rec)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="外部题库 -> 本项目 JSONL")
    sub = ap.add_subparsers(dest="fmt", required=True)

    for name in ("ceval", "mmlu"):
        p = sub.add_parser(name, help=f"{name} 的 data/val 目录")
        p.add_argument("--src", required=True, help="包含 *.csv 的目录")
        p.add_argument("--out", required=True)
        p.add_argument("--limit", type=int, default=0, help="最多导出多少条（0 = 全部）")

    p = sub.add_parser("csv", help="任意 CSV（Excel 另存为 CSV）")
    p.add_argument("--src", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--subject-col", required=True)
    p.add_argument("--stem-col", required=True)
    p.add_argument("--answer-col", required=True)
    p.add_argument("--options-col", default="", help="选项写在一列的列名；不传则用 A/B/C/D 四列")
    p.add_argument("--limit", type=int, default=0)

    args = ap.parse_args()

    if args.fmt == "ceval":
        records = convert_ceval_or_mmlu(args.src, "ceval", args.limit, zero_based=False)
    elif args.fmt == "mmlu":
        records = convert_ceval_or_mmlu(args.src, "mmlu", args.limit, zero_based=True)
    else:
        records = convert_csv(args.src, args.subject_col, args.stem_col, args.answer_col,
                              args.options_col, args.limit)

    if not records:
        die("一条记录都没转出来 —— 检查列名是否与来源一致（先 head 一下源文件）")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for rec in records:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    subjects = sorted({r["subject"] for r in records})
    print(f"转换完成: {len(records)} 条 -> {args.out}")
    print(f"学科: {len(subjects)} 个 ({', '.join(subjects[:8])}{' …' if len(subjects) > 8 else ''})")
    print(f"许可证: {LICENSES.get(args.fmt) or '未知（csv/excel 来源需要你自己确认）'}")
    print()
    print("下一步（先预览，别直接导）:")
    print(f"  1. 打开 题库管理 → 批量导入，粘贴 {args.out} 的内容，点「预览」")
    print("  2. 看报告里的「未通过校验」和「抽样核对」，确认答案对得上")
    print("  3. 再点「确认导入」")


if __name__ == "__main__":
    main()
