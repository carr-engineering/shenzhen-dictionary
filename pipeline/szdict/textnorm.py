"""Digit strings written the way they are read (PIPE-086), applied to corpus text before it is
segmented and counted: `9:30` -> 九点半, `3个` -> 三个, `2个` -> 两个, `2015年` -> 二零一五年, `105` -> 一百零五.
A general text normalisation — the engine has no rule about numerals."""
from __future__ import annotations

import re

DIGITS = "零一二三四五六七八九"
FULLWIDTH = str.maketrans("０１２３４５６７８９：．％", "0123456789:.%")
# Measure words (and time units) before which a lone 2 is read 两.
LIANG_BEFORE = set("个次天年位只条张本件块杯种名人点岁秒分周倍句把双对台辆部间家口头颗片瓶碗下遍回顿趟门枚根支篇封首座栋包盒箱桶袋串群")
LIANG_BEFORE_WORDS = ("小时", "星期", "礼拜", "分钟", "公里", "公斤", "千", "万", "亿")


def spell(s: str) -> str:
    """Digit by digit: years, telephone numbers, codes."""
    return "".join(DIGITS[int(c)] for c in s)


def _section(n: int) -> str:
    """0 < n < 10000 with 千 百 十; inner zeros read once as 零."""
    out = []
    zero = False
    for unit, name in ((1000, "千"), (100, "百"), (10, "十"), (1, "")):
        d, n = divmod(n, unit)
        if d == 0:
            zero = bool(out)
            continue
        if zero:
            out.append("零")
            zero = False
        out.append(DIGITS[d] + name)
    return "".join(out)


def cardinal(n: int) -> str:
    """0 … 10^12 - 1 as a cardinal: 十二, 一百零五, 两千, 三万五千, 一亿二千万."""
    if n == 0:
        return "零"
    parts = []
    need_zero = False
    for unit, name in ((10 ** 8, "亿"), (10 ** 4, "万"), (1, "")):
        sec, n = divmod(n, unit)
        if sec == 0:
            need_zero = bool(parts)
            continue
        s = _section(sec)
        if parts and (need_zero or sec < 1000):
            s = "零" + s
        need_zero = False
        parts.append(s + name)
    out = "".join(parts)
    if out.startswith("一十"):
        out = out[1:]
    # 2000 and 20000 are read 两千 / 两万
    if out.startswith("二千") or out.startswith("二万") or out.startswith("二亿"):
        out = "两" + out[1:]
    return out


_NUM = re.compile(r"(\d{1,2}):(\d{2})(?::\d{2})?(?![\d:])|(\d+)\.(\d+)|(\d+)%|(\d+)")


def read_digits(text: str) -> str:
    """Replaces every digit string of `text` by its reading in Chinese numerals."""
    if not any(c.isdigit() for c in text):
        return text
    text = text.translate(FULLWIDTH)

    def repl(m: re.Match) -> str:
        if m.group(1) is not None:                       # clock time
            h, mi = int(m.group(1)), int(m.group(2))
            if h > 24 or mi > 59:
                return spell(m.group(1)) + spell(m.group(2))
            hour = ("两" if h == 2 else cardinal(h)) + "点"
            if mi == 0:
                return hour
            if mi == 30:
                return hour + "半"
            return hour + ("零" + DIGITS[mi] if mi < 10 else cardinal(mi)) + "分"
        if m.group(3) is not None:                       # decimal
            return cardinal(int(m.group(3))) + "点" + spell(m.group(4))
        if m.group(5) is not None:                       # per cent
            return "百分之" + cardinal(int(m.group(5)))
        s = m.group(6)
        after = text[m.end():m.end() + 2]
        before = text[m.start() - 1:m.start()]
        if (len(s) == 4 and after[:1] == "年") or (len(s) > 1 and s[0] == "0") or len(s) > 12 or (len(s) >= 5 and not (after[:1] and after[:1] in "个人元块年天次岁万亿名")):
            return spell(s)                              # years, codes, telephone numbers
        n = int(s)
        if n == 2 and before != "第" and after[:1] and (after[:1] in LIANG_BEFORE or after.startswith(LIANG_BEFORE_WORDS)) and after[:2] != "年级":
            return "两"
        return cardinal(n)

    return _NUM.sub(repl, text)
