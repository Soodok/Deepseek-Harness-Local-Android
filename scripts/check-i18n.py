#!/usr/bin/env python3
"""i18n consistency checks for the DSH Mobile Android app.

Run locally:  python3 scripts/check-i18n.py
In CI:        .github/workflows/i18n-check.yml (pull_request + push)

The app ships English as the DEFAULT locale (res/values/strings.xml) and keeps
the original Chinese under res/values-zh-rCN/. These checks guard that split:
no CJK may leak into the default locale, into layout literals, or into Kotlin
string literals, and every locale must stay in sync with the base one.

Exit code 0 = all checks passed.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")

BASE_VALUES = os.path.join(APP, "res", "values")
LOCALE_DIRS = ["values-zh-rCN"]

# CJK ideographs, Kangxi/compat, CJK symbols & punctuation, fullwidth forms.
CJK = re.compile(r"[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]")

# Android format args: %s %d %1$s %2$d %1$.2f  (%% is an escaped percent, skip it)
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdf]")

# Kotlin string literals: triple-quoted raw strings first, then normal ones.
KT_RAW = re.compile(r'"""(.*?)"""', re.S)
KT_STR = re.compile(r'"((?:[^"\\\n]|\\.)*)"')

# CJK literals that are intentional (zh-locale data kept in the English build).
ALLOWED_CJK_LITERALS = {
    # ExtensionStoreActivity maps catalogue categories to accent colours; the
    # zh catalog still carries Chinese category names, so both are matched.
    ("ExtensionStoreActivity.kt", "语言运行时"),
    ("ExtensionStoreActivity.kt", "编译构建"),
    # AgentContextSeed teaches the agent to recognise UI labels in BOTH languages
    # (the app ships zh + en). These Chinese strings are intentional examples of
    # on-screen text the agent must match, not user-facing copy that needs i18n.
    ("AgentContextSeed.kt", "重启"),
    ("AgentContextSeed.kt", "允许"),
    ("AgentContextSeed.kt", "确定"),
    ("AgentContextSeed.kt", "扩展目录发布失败"),
    ("AgentContextSeed.kt", "缺失"),
    ("AgentContextSeed.kt", "构建完成"),
    ("AgentContextSeed.kt", "设置"),
}

# Whole files exempt from the Kotlin-literal CJK scan: these embed *bilingual
# agent instructions* (the seed tells the agent which on-screen labels to match
# in either language), so Chinese examples inside them are by design.
ALLOWED_CJK_FILES = {
    "AgentContextSeed.kt",
}

errors = []
notes = []


def fail(msg):
    errors.append(msg)


def rel(p):
    return os.path.relpath(p, ROOT)


def kotlin_files():
    base = os.path.join(APP, "java")
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith(".kt"):
                yield os.path.join(dirpath, n)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def line_of(text, idx):
    return text.count("\n", 0, idx) + 1


# ---------------------------------------------------------------- A. XML wellformed
def check_xml():
    targets = [os.path.join(APP, "AndroidManifest.xml")]
    for dirpath, _, names in os.walk(os.path.join(APP, "res")):
        for n in names:
            if n.endswith(".xml"):
                targets.append(os.path.join(dirpath, n))
    for p in targets:
        try:
            ET.parse(p)
        except ET.ParseError as e:
            fail("XML not well-formed: %s: %s" % (rel(p), e))
    notes.append("A. XML well-formedness: %d file(s) parsed" % len(targets))


# ------------------------------------------------- B. default locale must be English
def load_strings(values_dir):
    p = os.path.join(values_dir, "strings.xml")
    if not os.path.isfile(p):
        return None
    root = ET.parse(p).getroot()
    out = {}
    for child in root:
        if child.tag == "string":
            out[child.get("name")] = child.text or ""
        elif child.tag == "string-array":
            out[child.get("name")] = "\n".join(
                (it.text or "") for it in child
            )
    return out


def check_base_locale_english():
    base = load_strings(BASE_VALUES)
    if base is None:
        fail("missing %s" % rel(os.path.join(BASE_VALUES, "strings.xml")))
        return
    for name, val in sorted(base.items()):
        if CJK.search(val):
            fail("CJK in default-locale string '%s': %r" % (name, val))
    notes.append("B. Default locale free of CJK: %d string(s) checked" % len(base))


# ------------------------------------------ C. layout literals must not hardcode CJK
LAYOUT_ATTRS = ("text", "hint", "contentDescription")
ATTR_RE = re.compile(
    r'android:(?:text|hint|contentDescription)\s*=\s*"([^"]*)"'
)


def check_layouts():
    n = 0
    layout_dir = os.path.join(APP, "res", "layout")
    for dirpath, _, names in os.walk(layout_dir):
        for name in sorted(names):
            if not name.endswith(".xml"):
                continue
            p = os.path.join(dirpath, name)
            for m in ATTR_RE.finditer(read(p)):
                val = m.group(1)
                if not val or val.startswith("@"):
                    continue
                n += 1
                if CJK.search(val):
                    fail("CJK hardcoded in layout %s: android:text=\"%s\""
                         % (rel(p), val))
    notes.append("C. Layout literals: %d hardcoded android:text/hint checked" % n)


def strip_kotlin_comments(src):
    """Blank out comment bodies (newlines preserved) so literal scanning skips them.

    A naive regex would flag CJK that merely appears inside a comment between
    quotes, e.g. KDoc like `点"重启"按钮`. Only real literals matter here.
    """
    out = list(src)
    i, n = 0, len(src)
    state = None  # None | 'line' | 'block' | 'str' | 'raw' | 'char'
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                state = "line"
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if src.startswith('"""', i):
                state = "raw"
                i += 3
                continue
            if c == '"':
                state = "str"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            i += 1
        elif state == "line":
            if c == "\n":
                state = None
            else:
                out[i] = " "
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = None
                i += 2
                continue
            if c != "\n":
                out[i] = " "
            i += 1
        elif state == "raw":
            if src.startswith('"""', i):
                state = None
                i += 3
                continue
            i += 1
        elif state == "str":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = None
            i += 1
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = None
            i += 1
    return "".join(out)


# ------------------------------------------ D. Kotlin string literals must be English
def check_kotlin_literals():
    n = 0
    for p in sorted(kotlin_files()):
        src = read(p)
        code = strip_kotlin_comments(src)
        base = os.path.basename(p)
        if base in ALLOWED_CJK_FILES:
            # Bilingual agent instructions (see ALLOWED_CJK_FILES) - skip literal scan.
            continue
        spans = [(m.start(), m.end(), m.group(1)) for m in KT_RAW.finditer(code)]
        covered = [(s, e) for s, e, _ in spans]
        for m in KT_STR.finditer(code):
            if any(s <= m.start() < e for s, e in covered):
                continue
            spans.append((m.start(), m.end(), m.group(1)))
        for start, _end, body in spans:
            if not CJK.search(body):
                continue
            ln = line_of(src, start)
            if (base, body) in ALLOWED_CJK_LITERALS:
                continue
            n += 1
            fail("CJK in Kotlin string literal %s:%d: %r"
                 % (rel(p), ln, body[:60]))
    notes.append("D. Kotlin literals: %d CJK literal(s) remaining (excluding %d allowed)"
                 % (n, len(ALLOWED_CJK_LITERALS)))


# ------------------------------------- E/F. locale parity + placeholder parity
def check_locale_parity():
    base = load_strings(BASE_VALUES)
    if base is None:
        return
    for loc in LOCALE_DIRS:
        loc_dir = os.path.join(APP, "res", loc)
        if not os.path.isdir(loc_dir):
            continue
        locs = load_strings(loc_dir)
        if locs is None:
            continue
        for name in sorted(set(base) - set(locs)):
            fail("'%s' missing from %s/strings.xml" % (name, loc))
        for name in sorted(set(locs) - set(base)):
            fail("'%s' only in %s/strings.xml, not in the default locale"
                 % (name, loc))
        for name in sorted(set(base) & set(locs)):
            a = sorted(PLACEHOLDER.findall(base[name]))
            b = sorted(PLACEHOLDER.findall(locs[name]))
            if a != b:
                fail("placeholder mismatch for '%s' (%s): default=%s locale=%s"
                     % (name, loc, a, b))
        notes.append("E/F. Locale parity %s: %d string(s) in sync" % (loc, len(locs)))


# ------------------------------------------- G. every R.string.* reference resolves
R_STR = re.compile(r'(?<!android\.)\bR\.string\.([A-Za-z0-9_]+)')


def check_string_refs():
    base = load_strings(BASE_VALUES) or {}
    refs = {}
    for p in sorted(kotlin_files()):
        for m in R_STR.finditer(read(p)):
            refs.setdefault(m.group(1), set()).add(rel(p))
    for name in sorted(set(refs) - set(base)):
        fail("R.string.%s referenced but not defined (used in %s)"
             % (name, ", ".join(sorted(refs[name]))))
    unused = sorted(set(base) - set(refs))
    notes.append("G. R.string references: %d reference(s), %d defined, %d unused"
                 % (len(refs), len(base), len(unused)))
    if unused:
        notes.append("   (unused, may be referenced from XML only: %s)"
                     % ", ".join(unused))


# ----------------------------------------------- H. catalogue assets stay in sync
def check_catalogs():
    ext = os.path.join(APP, "assets", "extensions")
    base_p = os.path.join(ext, "catalog.json")
    if not os.path.isfile(base_p):
        return
    try:
        base = json.loads(read(base_p))
    except ValueError as e:
        fail("catalog.json is not valid JSON: %s" % e)
        return
    base_ids = [i["id"] for i in base["items"]]
    notes.append("H. Catalog: %d extension(s), categories: %s"
                 % (len(base_ids),
                    ", ".join(sorted({i.get("category", "?") for i in base["items"]}))))
    for name in sorted(os.listdir(ext)):
        if not name.endswith(".json") or name == "catalog.json":
            continue
        try:
            other = json.loads(read(os.path.join(ext, name)))
        except ValueError as e:
            fail("%s is not valid JSON: %s" % (name, e))
            continue
        other_ids = [i["id"] for i in other["items"]]
        if other_ids != base_ids:
            fail("%s item ids/order differ from catalog.json: %s vs %s"
                 % (name, other_ids, base_ids))
        if other.get("mirrors") != base.get("mirrors"):
            fail("%s mirrors differ from catalog.json" % name)
    # runtime manifest must stay parseable
    man = os.path.join(APP, "assets", "runtime", "MANIFEST.json")
    if os.path.isfile(man):
        try:
            m = json.loads(read(man))
            for k in ("version", "url", "sha256"):
                if k not in m:
                    fail("runtime MANIFEST.json missing key '%s'" % k)
        except ValueError as e:
            fail("runtime MANIFEST.json is not valid JSON: %s" % e)


def main():
    check_xml()
    check_base_locale_english()
    check_layouts()
    check_kotlin_literals()
    check_locale_parity()
    check_string_refs()
    check_catalogs()

    print("== dsh-mobile i18n check ==")
    for n in notes:
        print("  " + n)
    if errors:
        print("\nFAILED (%d):" % len(errors))
        for e in errors:
            print("  ::error::" + e)
        return 1
    print("\nAll i18n checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
