#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 catalog_raw.json（教材目录）与 questions.json（题库）合成 assets/catalog.json。

核心工作：把题库里以「知识点」组织的分组，映射到教材的「单元 / 课」节点上。

映射精度分三级，写进数据里以便前端区分：
  lesson  —— 精确到课（如古诗题对应到《静夜思》这一课）
  unit    —— 精确到单元（数学计算题只能到这个粒度，同一单元内的题原本就不分课）
  book    —— 只到册（知识点跨单元，如「修辞手法」「一般现在时」）

未匹配到任何节点的知识点进「综合练习」虚拟节点，不会凭空消失。

用法：
    python3 tools/build_catalog.py
"""

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RAW = os.path.join(HERE, "catalog_raw.json")
BANK = os.path.join(ROOT, "app/src/main/assets/questions.json")
OUT = os.path.join(ROOT, "app/src/main/assets/catalog.json")

SUBJECTS = ["语文", "数学", "英语"]

# ---------------------------------------------------------------- 映射表
#
# 键：题库里的知识点名（questions.json 的 group.unit）
# 值：目录节点列表，元素形如
#     ("单元", "三年级", "上册", "多位数乘一位数")   按单元名匹配
#     ("课",   "三年级", "上册", "多位数乘一位数", "口算乘法")
#     ("册",   "三年级", "上册")                      整册
# 单元名用「子串匹配」，教材里的空格/标点差异不影响命中。

M_1 = [
    ("单元", "一年级", "上册", "5以内数的认识和加"),
    ("单元", "一年级", "上册", "6～10的认识和加"),
    ("单元", "一年级", "上册", "20以内的进位加法"),
    ("单元", "一年级", "下册", "20以内的退位减法"),
]

MAPPING = {
    # ---------------- 数学 ----------------
    "20以内加减法": M_1,
    "认识100以内的数": [("单元", "一年级", "下册", "100以内数的认识")],
    "认识图形": [("单元", "一年级", "上册", "认识立体图形"),
                 ("单元", "一年级", "下册", "认识平面图形")],
    "认识人民币": [],
    "认识钟表": [],
    "比一比与解决问题": [("单元", "一年级", "下册", "数量间的加减关系")],

    "100以内加减法": [("单元", "一年级", "下册", "100以内的口算加"),
                      ("单元", "一年级", "下册", "100以内的笔算加")],
    "表内乘法": [("单元", "二年级", "上册", "1~6的表内乘法"),
                 ("单元", "二年级", "上册", "7～9的表内乘")],
    "表内除法": [("单元", "二年级", "上册", "1～6的表内除法"),
                 ("单元", "二年级", "上册", "7～9的表内乘")],
    "长度单位": [("单元", "二年级", "上册", "厘米和米"),
                 ("单元", "三年级", "上册", "毫米、分米和千米")],
    "角的初步认识": [("单元", "三年级", "上册", "线和角")],
    "混合运算与解决问题": [("单元", "三年级", "上册", "混合运算"),
                           ("单元", "四年级", "下册", "四则运算")],

    "多位数乘一位数": [("单元", "三年级", "上册", "多位数乘一位数")],
    "除数是一位数的除法": [("单元", "三年级", "下册", "除数是一位数的除法")],
    "分数的初步认识": [("单元", "三年级", "上册", "分数的初步认识")],
    "长方形和正方形的周长": [("单元", "三年级", "下册", "长方形和正方形")],
    "面积": [("单元", "三年级", "下册", "图形的面积")],
    "时分秒": [],

    "四则运算": [("单元", "四年级", "下册", "四则运算")],
    "大数的认识": [("单元", "四年级", "上册", "万以上数的认识")],
    "角的度量": [("单元", "四年级", "上册", "角的度量")],
    "小数的意义和性质": [("单元", "四年级", "下册", "小数的意义和性质")],
    "平行与垂直": [("单元", "四年级", "上册", "平行四边形和梯形")],
    "运算定律": [("单元", "四年级", "下册", "运算律")],

    "小数乘法": [("单元", "五年级", "上册", "小数乘法")],
    "小数除法": [("单元", "五年级", "上册", "小数除法")],
    "分数加减法": [("单元", "五年级", "下册", "分数的加法和减法")],
    "简易方程": [("单元", "五年级", "上册", "用字母表示数和数量关系"),
                 ("单元", "五年级", "上册", "简易方程")],
    "多边形的面积": [("单元", "五年级", "上册", "多边形的面积")],
    "因数与倍数": [("单元", "五年级", "下册", "因数与倍数")],

    "分数乘除法": [("单元", "六年级", "上册", "分数乘法"),
                   ("单元", "六年级", "上册", "分数除法")],
    "比和比例": [("单元", "六年级", "下册", "比例")],
    "圆": [],
    "百分数": [("单元", "六年级", "上册", "百分数"),
               ("单元", "六年级", "下册", "百分数（二）")],
    "圆柱与圆锥": [("单元", "六年级", "下册", "圆柱与圆锥")],
    "负数": [("单元", "六年级", "下册", "负数")],

    # ---------------- 语文 ----------------
    # 语文题多为跨单元的语文知识，按册挂载；古诗类能对到课文时对到课。
    "拼音": [("单元", "一年级", "上册", "汉语拼音")],
    "识字与笔画": [("单元", "一年级", "上册", "识字"),
                   ("单元", "一年级", "下册", "识字")],
    "词语搭配": [("册", "一年级", "上册"), ("册", "一年级", "下册")],
    "反义词": [("册", "一年级", "上册"), ("册", "一年级", "下册"),
               ("册", "二年级", "上册"), ("册", "二年级", "下册")],
    "古诗启蒙": [("册", "一年级", "上册"), ("册", "一年级", "下册")],
    "古诗（苏教版）": [("册", "一年级", "上册")],
    "多音字": [("册", "二年级", "上册"), ("册", "二年级", "下册")],
    "量词": [("册", "二年级", "上册")],
    "近义词": [("册", "二年级", "上册"), ("册", "二年级", "下册")],
    "古诗": [("册", "二年级", "上册"), ("册", "二年级", "下册"),
             ("册", "三年级", "上册"), ("册", "三年级", "下册"),
             ("册", "四年级", "上册"), ("册", "四年级", "下册"),
             ("册", "五年级", "上册"), ("册", "五年级", "下册"),
             ("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "成语": [("册", "三年级", "上册"), ("册", "三年级", "下册")],
    "修改病句": [("册", "三年级", "上册"), ("册", "三年级", "下册")],
    "的地得": [("册", "三年级", "上册"), ("册", "三年级", "下册")],
    "标点符号": [("册", "三年级", "上册"), ("册", "三年级", "下册")],
    "修辞手法": [("册", "四年级", "上册"), ("册", "四年级", "下册")],
    "成语典故": [("册", "四年级", "上册"), ("册", "四年级", "下册")],
    "关联词": [("册", "四年级", "上册"), ("册", "四年级", "下册")],
    "歇后语": [("册", "五年级", "上册"), ("册", "五年级", "下册")],
    "说明方法": [("册", "五年级", "上册"), ("册", "五年级", "下册")],
    "病句辨析": [("册", "五年级", "上册"), ("册", "五年级", "下册")],
    "近反义词": [("册", "五年级", "上册"), ("册", "五年级", "下册")],
    "文言文常识": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "修辞与句式": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "成语运用": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "标点与句子": [("册", "六年级", "上册"), ("册", "六年级", "下册")],

    # ---------------- 英语 ----------------
    "字母与发音": [("册", "三年级", "上册")],
    "颜色（低年级）": [("单元", "三年级", "上册", "The colourful world"),
                       ("册", "一年级", "上册")],
    "动物（低年级）": [("单元", "三年级", "上册", "Amazing animals")],
    "数字 1-5": [("单元", "三年级", "上册", "Useful numbers")],
    "简单问候": [("单元", "三年级", "上册", "Making friends")],
    "字母表": [("册", "三年级", "上册"), ("册", "三年级", "下册")],
    "水果": [("单元", "四年级", "下册", "On the farm")],
    "数字 6-10": [("单元", "三年级", "上册", "Useful numbers"),
                  ("单元", "三年级", "下册", "Numbers in life")],
    "身体部位": [("册", "三年级", "上册")],
    "课堂用语": [("单元", "四年级", "下册", "Class rules"),
                 ("册", "三年级", "上册")],
    "字母与语音": [("册", "三年级", "上册")],
    "颜色": [("单元", "三年级", "上册", "The colourful world")],
    "数字": [("单元", "三年级", "上册", "Useful numbers"),
             ("单元", "三年级", "下册", "Numbers in life")],
    "日常问候": [("单元", "三年级", "上册", "Making friends"),
                 ("单元", "三年级", "下册", "Meeting new people")],
    "常见单词": [("册", "三年级", "上册"), ("册", "三年级", "下册")],
    "词汇（人教版 PEP）": [("册", "三年级", "上册")],
    "词汇（外研版）": [("册", "三年级", "上册")],
    "家庭成员": [("单元", "三年级", "上册", "Different families")],
    "学校科目": [("单元", "四年级", "下册", "Time for school")],
    "天气": [("单元", "四年级", "上册", "The weather and us"),
             ("单元", "四年级", "上册", "Changing for the seasons")],
    "can 句型": [("册", "四年级", "上册"), ("册", "四年级", "下册")],
    "时间表达": [("单元", "四年级", "下册", "Time for school")],
    "星期与月份": [("单元", "四年级", "下册", "Time for school")],
    "一般现在时": [("册", "五年级", "上册"), ("册", "五年级", "下册")],
    "there be 句型": [("册", "五年级", "上册")],
    "频度副词": [("册", "五年级", "上册")],
    "常用介词": [("册", "五年级", "上册"), ("册", "五年级", "下册")],
    "一般过去时": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "形容词比较级": [("册", "六年级", "上册")],
    "一般将来时": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "常用动词短语": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
    "情景交际": [("册", "六年级", "上册"), ("册", "六年级", "下册")],
}


def node_id(grade, subject, term, unit_no=None, lesson_no=None):
    base = f"{grade}|{subject}|{term}"
    if unit_no is None:
        return base
    if lesson_no is None:
        return f"{base}|{unit_no}"
    return f"{base}|{unit_no}|{lesson_no}"


def build_index(raw):
    """建立 (grade, subject, term) -> book，以及单元名查找表。"""
    books = {}
    for b in raw["books"]:
        books[(b["grade"], b["subject"], b["term"])] = b
    return books


def resolve(spec, books, subject):
    """把一条映射规则解析成节点 id 列表。命中不到返回空列表。

    subject 必须传入：一条「三年级上册」的册级规则要只落在本学科的册上，
    否则会给语文知识点挂上数学、英语的册。
    """
    kind = spec[0]
    if kind == "单元":
        _, grade, term, keyword = spec
        hits = []
        for (g, s, t), b in books.items():
            if g != grade or t != term or s != subject:
                continue
            kw = keyword.replace(" ", "")
            for u in b["units"]:
                if kw in u["name"].replace(" ", ""):
                    hits.append(node_id(g, s, t, u["no"]))
        return hits
    if kind == "课":
        _, grade, term, unit_kw, lesson_kw = spec
        hits = []
        for (g, s, t), b in books.items():
            if g != grade or t != term or s != subject:
                continue
            ukw = unit_kw.replace(" ", "")
            for u in b["units"]:
                if ukw not in u["name"].replace(" ", ""):
                    continue
                for l in u["lessons"]:
                    if lesson_kw in l["name"]:
                        hits.append(node_id(g, s, t, u["no"], l["no"]))
        return hits
    if kind == "册":
        _, grade, term = spec
        return [node_id(g, s, t) for (g, s, t) in books
                if g == grade and t == term and s == subject]
    return []


def main():
    raw = json.load(open(RAW, encoding="utf-8"))
    bank = json.load(open(BANK, encoding="utf-8"))
    books = build_index(raw)

    # 输出目录：册 -> 单元 -> 课
    out_books = []
    for b in raw["books"]:
        out_books.append({
            "edition": b["edition"],
            "grade": b["grade"],
            "subject": b["subject"],
            "term": b["term"],
            "year": b["year"],
            "id": node_id(b["grade"], b["subject"], b["term"]),
            "units": [{
                "no": u["no"],
                "name": u["name"],
                "id": node_id(b["grade"], b["subject"], b["term"], u["no"]),
                "lessons": [{
                    "no": l["no"],
                    "name": l["name"],
                    "id": node_id(b["grade"], b["subject"], b["term"], u["no"], l["no"]),
                } for l in u["lessons"]],
            } for u in b["units"]],
        })

    # 知识点 -> 节点
    # 键必须是 (学科, 知识点)：同名知识点在不同学科下要落到各自的册
    kmap = {}
    unmatched = []
    subjects_of = {}
    for g in bank["groups"]:
        subjects_of.setdefault(g["unit"], set()).add(g["subject"])

    for unit_name, specs in MAPPING.items():
        for subj in sorted(subjects_of.get(unit_name, set())):
            nodes = []
            for sp in specs:
                nodes.extend(resolve(sp, books, subj))
            nodes = sorted(set(nodes))
            if nodes:
                kmap[(subj, unit_name)] = nodes
            else:
                unmatched.append(unit_name)

    # 题库里出现但映射表没写的知识点，也记下来
    for g in bank["groups"]:
        u = g["unit"]
        if u not in MAPPING and u not in unmatched:
            unmatched.append(u)

    # 把映射写回题库分组：group.scopes
    #
    # 册级规则（如「修辞手法」挂四年级上册整册）保持册 id，不展开到单元。
    # 早先展开过一次，结果每个单元都显示同样的题量——因为册级知识点把每单元
    # 都撑满了，单元选择等于失效。保持「册」id 后，语义才对：
    #   选整册 -> 加入册 id，册级题和单元级题都命中
    #   选单元 -> 只加单元 id，册级题不命中（它本来就不属于某一单元）
    def unique(nodes):
        return sorted(set(nodes))

    total_q = 0
    scoped_q = 0
    for g in bank["groups"]:
        nodes = unique(kmap.get((g["subject"], g["unit"]), []))
        # 没有对应教材单元的知识点（新教材已删除或尚未覆盖），归到「综合练习」，
        # 家长仍能单独勾选，不会被悄悄丢掉。
        if not nodes:
            nodes = [f"综合|{g['subject']}"]
        g["scopes"] = nodes
        n = len(g["questions"])
        total_q += n
        if nodes:
            scoped_q += n

    bank["catalogVersion"] = 1
    with open(BANK, "w", encoding="utf-8") as f:
        json.dump(bank, f, ensure_ascii=False, indent=1)

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({
            "version": 1,
            "edition": "人教版",
            "note": "小学 1-6 年级语文/数学/英语教材目录，数据源电子课本网，"
                    "按 2024 年秋起的新教材版次整理；未覆盖的版次不在本文件中。",
            "books": out_books,
        }, f, ensure_ascii=False, indent=1)

    n_units = sum(len(b["units"]) for b in out_books)
    n_lessons = sum(len(u["lessons"]) for b in out_books for u in b["units"])
    print(f"教材目录：{len(out_books)} 册 / {n_units} 单元 / {n_lessons} 课 -> {OUT}")
    print(f"知识点映射：{len(kmap)} 个命中，{len(unmatched)} 个未命中")
    print(f"题目覆盖：{scoped_q}/{total_q} 道已挂到目录 "
          f"({scoped_q*100//max(total_q,1)}%)")
    if unmatched:
        print("未映射（将进「综合练习」）：")
        for u in sorted(set(unmatched)):
            n = sum(len(g["questions"]) for g in bank["groups"] if g["unit"] == u)
            print(f"  - {u}（{n} 道）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
