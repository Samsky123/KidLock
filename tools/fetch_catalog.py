#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
抓取人教版（统编语文 / 人教数学 / 人教 PEP 英语）小学 1-6 年级教材目录。

数据源：电子课本网 dzkbw.com（GBK 编码）。
产物：tools/catalog_raw.json —— 中间产物，供 build_catalog.py 做知识点映射后
      生成 app/src/main/assets/catalog.json。

用法：
    python3 tools/fetch_catalog.py          # 抓取（有缓存，已抓过的册跳过）
    python3 tools/fetch_catalog.py --force  # 强制重抓

设计要点：
1. 册次路径从学科索引页动态发现，取每个 (年级,学期) 的最新版次，
   不硬编码年份——教材换版时脚本自动跟着走。
2. 解析 mululist 时不能用 `</div>` 收尾（区块里嵌了广告 div），
   改为「连续读取指向 /books/ 的 <a>，遇到外链即停」。
3. 三个学科的层级不同，分别解析：
   语文  第一单元 → 1 大青树下的小学
   数学  一、观察物体 → 观察简单的立体积木
   英语  Unit 1 Making friends → Part A ...
"""

import json
import os
import re
import sys
import time
import urllib.request

BASE = "http://www.dzkbw.com"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW_PATH = os.path.join(HERE, "catalog_raw.json")

GRADE_LABEL = {"1": "一年级", "2": "二年级", "3": "三年级",
               "4": "四年级", "5": "五年级", "6": "六年级"}
GRADE_ORDER = list(GRADE_LABEL.values())

# 索引页 -> (学科, 路径正则)
INDEXES = [
    ("语文", "/books/rjb/yuwen/", re.compile(r"/books/rjb/yuwen/xs(\d)([sx])_([0-9a-z]+)/")),
    ("数学", "/books/rjb/shuxue/", re.compile(r"/books/rjb/shuxue/xs(\d)([sx])_([0-9a-z]+)/")),
    ("英语", "/books/rjb/yingyu/", re.compile(r"/books/rjb/yingyu/pep(\d)([sx])_([0-9a-z]+)/")),
]

CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6,
          "七": 7, "八": 8, "九": 9, "十": 10, "十一": 11, "十二": 12}

# 练习/附录，进目录但不作为可选课
NON_LESSON = re.compile(
    r"^(口语交际|习作|语文园地|快乐读书吧|识字表|写字表|词语表|封面|目录|前言|"
    r"附录|后记|总复习|整理和复习|期中|期末|阅读|习作例文|综合性学习|"
    r"Recycle|Words|Useful|Reading|Tips|Songs|Chants|Story)", re.I)


def fetch(url, retries=4):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
                "Referer": BASE,
                "Accept-Language": "zh-CN,zh;q=0.9",
            })
            with urllib.request.urlopen(req, timeout=25) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2.0 * (i + 1))
    raise last


def decode(raw):
    for enc in ("gbk", "gb18030", "utf-8"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("gbk", errors="ignore")


def parse_items(html_text):
    """取出 mululist 区块内的目录条目文本（按教材顺序）。

    区块里嵌了广告 <div>，所以不用 </div> 收尾；靠「href 是否指向 /books/」
    判断目录是否结束。
    """
    i = html_text.find('class="mululist"')
    if i < 0:
        return []
    seg = html_text[i:]
    items = []
    for a in re.finditer(r'<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>', seg, re.S | re.I):
        href, inner = a.group(1), a.group(2)
        if not href.startswith("/books/"):
            break
        txt = re.sub(r"<[^>]+>", "", inner)
        txt = txt.replace("&nbsp;", " ").replace("\u3000", " ").strip()
        txt = re.sub(r"\s+", " ", txt)
        if txt:
            items.append(txt)
    return items


def parse_chinese(items):
    """语文：第X单元 -> 课号 + 课名。"""
    units, cur = [], None
    for txt in items:
        if re.match(r"^(封面|目录|前言)", txt):
            continue
        mu = re.match(r"^第([一二三四五六七八九十]+)单元\s*[·•\s]*(.*)$", txt)
        if mu:
            no = CN_NUM.get(mu.group(1), len(units) + 1)
            suffix = mu.group(2).strip()
            name = f"第{no}单元" + (f" · {suffix}" if suffix else "")
            cur = {"no": no, "name": name, "lessons": []}
            units.append(cur)
            continue
        ml = re.match(r"^(\d+)\s*\*?\s*(.+)$", txt)
        if ml and cur is not None:
            name = ml.group(2).strip()
            if NON_LESSON.match(name) or len(name) > 30:
                continue
            cur["lessons"].append({"no": int(ml.group(1)), "name": name})
    return units


def parse_math(items):
    """数学：一、单元名 -> 课时名。

    新版（2024 起）单元用中文序号「一、二、三、」，课时用「1. 1~5的认识」；
    旧版单元直接是「1 时、分、秒」，没有课时层级。
    关键陷阱：一年级课时名以数字范围开头（1~5的认识），不能被当成单元序号。
    """
    units, cur = [], None
    # 新版单元标题形如「一 5以内数的认识和加、减法」或「一、观察物体」，
    # 中文序号后面可能是空格也可能是顿号，两种都要认。
    CN_UNIT = re.compile(r"^([一二三四五六七八九十]+)(?:[、\.．]\s*|\s+)(.{2,})$")
    has_cn_unit = any(CN_UNIT.match(t) for t in items)

    for txt in items:
        if re.match(r"^(封面|目录|前言|☆)", txt):
            continue

        mu = CN_UNIT.match(txt)
        if mu:
            name = mu.group(2).strip()
            if NON_LESSON.match(name):
                cur = None
                continue
            cur = {"no": len(units) + 1, "name": name, "lessons": []}
            units.append(cur)
            continue

        if not has_cn_unit:
            # 旧版：以「数字 + 空格」开头的单元名，排除「1~5」这类范围表达
            mo = re.match(r"^(\d+)\s+([\u4e00-\u9fa5（(].*)$", txt)
            if mo and not re.match(r"^\d+\s*[~～\-—]", txt):
                name = mo.group(2).strip()
                if not NON_LESSON.match(name):
                    cur = {"no": len(units) + 1, "name": name, "lessons": []}
                    units.append(cur)
                    continue

        # 复习类单元后面不再挂课时
        if re.match(r"^(复习与关联|总复习|整理与复习|整理和复习)", txt):
            cur = None
            continue

        # 其余都是当前单元下的课时
        if cur is not None and not NON_LESSON.match(txt) and len(txt) <= 30:
            cur["lessons"].append({"no": len(cur["lessons"]) + 1, "name": txt})
    return units


def parse_english(items):
    """英语：Unit N 名称 -> Part A/B/C 小标题。"""
    units, cur = [], None
    for txt in items:
        if re.match(r"^(封面|目录|前言)", txt):
            continue
        mu = re.match(r"^Unit\s*(\d+)\s*(.*)$", txt, re.I)
        if mu:
            cur = {"no": int(mu.group(1)), "name": mu.group(2).strip() or f"Unit {mu.group(1)}",
                   "lessons": []}
            units.append(cur)
            continue
        if cur is not None and re.match(r"^Part\s*[A-C]", txt, re.I):
            cur["lessons"].append({"no": len(cur["lessons"]) + 1, "name": txt})
    return units


PARSERS = {"语文": parse_chinese, "数学": parse_math, "英语": parse_english}


def discover_books():
    """从索引页发现各学科所有册次。

    每个 (年级,学期) 会有一到多个候选路径（新版 / 旧版 / _new），
    按「新版优先」排序后全部保留——新版页面可能尚未上线内容，
    抓取时依次回退，避免因为某一版是空页就丢掉整册。
    """
    found = []
    for subject, index_path, pattern in INDEXES:
        html_text = decode(fetch(BASE + index_path))
        slots = {}  # (grade_no, term_code) -> [(sort_key, path)]
        for m in pattern.finditer(html_text):
            gno, term_code, ver = m.group(1), m.group(2), m.group(3)
            path = m.group(0)
            key = (int(ver), 2) if ver.isdigit() else (0, 1 if ver == "new" else 0)
            slots.setdefault((gno, term_code), [])
            if all(p != path for _, p in slots[(gno, term_code)]):
                slots[(gno, term_code)].append((key, path))
        for (gno, term_code), cands in sorted(slots.items()):
            if int(gno) > 6:
                continue
            cands.sort(reverse=True)
            found.append({
                "subject": subject,
                "grade": GRADE_LABEL[gno],
                "term": "上册" if term_code == "s" else "下册",
                "candidates": [p for _, p in cands],
            })
        print(f"{subject}：发现 {len([b for b in found if b['subject']==subject])} 册")
        time.sleep(0.5)
    return found


def main():
    force = "--force" in sys.argv
    cache = {"books": []}
    if os.path.exists(RAW_PATH) and not force:
        with open(RAW_PATH, encoding="utf-8") as f:
            cache = json.load(f)
    done = {(b["subject"], b["grade"], b["term"]) for b in cache.get("books", [])}

    print("发现册次……")
    books_meta = discover_books()

    books = cache.get("books", [])
    failed = []
    for b in books_meta:
        key = (b["subject"], b["grade"], b["term"])
        if key in done:
            print(f"  跳过（已缓存）{b['subject']} {b['grade']}{b['term']}")
            continue

        got = None
        for path in b["candidates"]:
            url = BASE + path + "001.htm"
            print(f"  抓取 {b['subject']} {b['grade']}{b['term']} <- {path}")
            try:
                html_text = decode(fetch(url))
                units = PARSERS[b["subject"]](parse_items(html_text))
                if units:
                    got = (path, units)
                    break
                print("    解析为空，回退到旧版次")
            except Exception as e:  # noqa: BLE001
                print(f"    失败：{e}")
            time.sleep(0.7)

        if not got:
            failed.append((b, "所有候选版次均无目录"))
            continue

        path, units = got
        nl = sum(len(u["lessons"]) for u in units)
        books.append({
            "edition": "人教版",
            "grade": b["grade"],
            "subject": b["subject"],
            "term": b["term"],
            "year": path.rstrip("/").split("_")[-1],
            "units": units,
        })
        print(f"    ok  {len(units)} 单元 / {nl} 课")
        time.sleep(0.7)

    books.sort(key=lambda x: (["语文", "数学", "英语"].index(x["subject"]),
                              GRADE_ORDER.index(x["grade"]), x["term"]))
    with open(RAW_PATH, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "books": books}, f, ensure_ascii=False, indent=1)
    print(f"\n共 {len(books)} 册 -> {RAW_PATH}")
    if failed:
        print("失败清单：")
        for b, why in failed:
            print(f"  - {b['subject']} {b['grade']}{b['term']}：{why}")


if __name__ == "__main__":
    main()
