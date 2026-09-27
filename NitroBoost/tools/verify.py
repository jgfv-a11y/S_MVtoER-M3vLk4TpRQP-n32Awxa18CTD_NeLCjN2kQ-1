#!/usr/bin/env python3
"""
NitroBoost static verifier.
Runs without Android SDK / network:
  1. all XML is well-formed
  2. every @string/@color/@drawable/@layout/@style/@menu/@xml/@mipmap reference resolves
  3. every component in AndroidManifest exists as a Kotlin class
  4. every R.xxx reference in Kotlin resolves to a resource or generated id
  5. Kotlin braces/parens/brackets balance (outside strings & comments)
  6. vector pathData is syntactically valid
Exit code 0 = clean.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(MAIN, "res")
SRC = os.path.join(MAIN, "java")
TEST_SRC = os.path.join(ROOT, "app", "src", "test", "java")

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


# ---------- collect resources ----------
def res_type_dirs():
    """map resource type -> set of names, and ids -> set of names"""
    types = {}
    ids = set()
    for d in os.listdir(RES):
        full = os.path.join(RES, d)
        if not os.path.isdir(full):
            continue
        base = "values" if d.startswith("values") else re.split(r"[-]", d)[0]
        for f in os.listdir(full):
            if not f.endswith(".xml"):
                continue
            name = f[:-4]
            if base in ("values", "values-ar"):
                # collect <string name>, <color name>, <style name>, <dimen name> ...
                try:
                    text = open(os.path.join(full, f), encoding="utf-8").read()
                    for m in re.finditer(r'<(string|color|dimen|style|integer|bool|item)[^>]*name="([^"]+)"', text):
                        types.setdefault(m.group(1), set()).add(m.group(2))
                    for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', text):
                        ids.add(m.group(1))
                except Exception as e:
                    err(f"cannot read {full}/{f}: {e}")
                continue
            types.setdefault(base, set()).add(name)
            # collect @+id definitions
            path = os.path.join(full, f)
            try:
                text = open(path, encoding="utf-8").read()
                for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', text):
                    ids.add(m.group(1))
            except Exception as e:
                err(f"cannot read {path}: {e}")
    return types, ids


def main():
    types, ids = res_type_dirs()

    defined_styles = set()
    for f in os.listdir(os.path.join(RES, "values")):
        if f.endswith(".xml"):
            text = open(os.path.join(RES, "values", f), encoding="utf-8").read()
            for m in re.finditer(r'<style\s+[^>]*name="([^"]+)"', text):
                defined_styles.add(m.group(1))

    # android framework ids we do not define
    android_ids = {"android:id/empty"}

    # ---------- 1. XML well-formed ----------
    xml_files = []
    for base, dirs, files in os.walk(RES):
        for f in files:
            if f.endswith(".xml"):
                xml_files.append(os.path.join(base, f))
    xml_files.append(os.path.join(MAIN, "AndroidManifest.xml"))

    import xml.parsers.expat
    for xf in xml_files:
        try:
            ET.parse(xf)
        except ET.ParseError as e:
            err(f"XML parse error {os.path.relpath(xf, ROOT)}: {e}")
        # namespace-strict pass: catches unbound prefixes that aapt2 rejects
        try:
            parser = xml.parsers.expat.ParserCreate(namespace_separator=":")
            parser.ParseFile(open(xf, "rb"))
        except xml.parsers.expat.ExpatError as e:
            err(f"namespace error {os.path.relpath(xf, ROOT)}: {e}")

    # ---------- 2. reference resolution ----------
    ref_re = re.compile(r'@(string|color|drawable|layout|style|menu|xml|mipmap|dimen|id)/([A-Za-z0-9_.]+)')
    style_parent_re = re.compile(r'parent="(?:\?android:attr/)?([A-Za-z0-9_.]+)"')

    framework_styles = {
        "Widget.Material3.Button",
        "Widget.Material3.Button.OutlinedButton",
        "Widget.Material3.Button.TextButton",
        "Widget.Material3.CardView.Filled",
        "Theme.Material3.Dark.NoActionBar",
        "Theme.Material3.DayNight.NoActionBar",
    }

    for xf in xml_files:
        text = open(xf, encoding="utf-8").read()
        for m in ref_re.finditer(text):
            t, n = m.group(1), m.group(2)
            if t == "id":
                if not n.startswith("android:") and n not in ids:
                    err(f"{os.path.relpath(xf, ROOT)}: @id/{n} not defined anywhere (need @+id)")
            elif t == "style":
                if n not in defined_styles and n not in framework_styles:
                    err(f"{os.path.relpath(xf, ROOT)}: @style/{n} not found")
            elif t in types:
                if n not in types[t]:
                    err(f"{os.path.relpath(xf, ROOT)}: @{t}/{n} not found")
            else:
                err(f"{os.path.relpath(xf, ROOT)}: unknown resource type @{t}/{n}")
    for xf in xml_files:
        text = open(xf, encoding="utf-8").read()
        for m in re.finditer(r'@(?:style|parent=.*?style)/([A-Za-z0-9_.]+)', text):
            pass
        for m in re.finditer(r'style="?@style/([A-Za-z0-9_.]+)', text):
            n = m.group(1)
            if n not in defined_styles and n not in framework_styles:
                err(f"{os.path.relpath(xf, ROOT)}: style @{n} not defined")
        for m in re.finditer(r'parent="(@style/[A-Za-z0-9_.]+|\?android:attr/[A-Za-z0-9_.]+)"', text):
            p = m.group(1)
            if p.startswith("@style/") and p[len("@style/"):] not in framework_styles:
                warn(f"{os.path.relpath(xf, ROOT)}: style parent {p} (framework styles OK if in material lib)")

    # ---------- 3. manifest components exist ----------
    manifest = open(os.path.join(MAIN, "AndroidManifest.xml"), encoding="utf-8").read()
    pkg = re.search(r'package="([^"]+)"', manifest)
    # namespace comes from gradle: com.nitroboost.app
    ns = "com.nitroboost.app"
    comp_re = re.compile(r'android:name="(\.[A-Za-z0-9_.]+)"')
    for m in comp_re.finditer(manifest):
        cls = ns + m.group(1)  # leading dot included
        rel = cls.replace(".", "/") + ".kt"
        if not os.path.exists(os.path.join(SRC, rel)):
            err(f"manifest component class missing: {cls}")
    # external shizuku provider is fine

    # ---------- 4. Kotlin R references ----------
    kotlin_files = []
    for base in (SRC, TEST_SRC):
        for dp, _, files in os.walk(base):
            for f in files:
                if f.endswith(".kt"):
                    kotlin_files.append(os.path.join(dp, f))

    r_re = re.compile(r'R\.(string|color|drawable|layout|style|menu|xml|mipmap|id|raw)/([A-Za-z0-9_]+)')
    for kf in kotlin_files:
        text = open(kf, encoding="utf-8").read()
        for m in r_re.finditer(text):
            t, n = m.group(1), m.group(2)
            if t == "id":
                if n not in ids:
                    err(f"{os.path.relpath(kf, ROOT)}: R.id.{n} not defined in any layout")
            elif t in types:
                if n not in types[t]:
                    err(f"{os.path.relpath(kf, ROOT)}: R.{t}.{n} not found")
            else:
                err(f"{os.path.relpath(kf, ROOT)}: unknown R.{t}.{n}")

    # ---------- 5. Kotlin balance ----------
    def strip_kotlin(code):
        out = []
        i = 0
        n = len(code)
        while i < n:
            c = code[i]
            # line comment
            if c == '/' and i + 1 < n and code[i + 1] == '/':
                while i < n and code[i] != '\n':
                    i += 1
                continue
            # block comment (nesting aware)
            if c == '/' and i + 1 < n and code[i + 1] == '*':
                depth = 1
                i += 2
                while i < n and depth > 0:
                    if code[i] == '/' and i + 1 < n and code[i + 1] == '*':
                        depth += 1
                        i += 2
                    elif code[i] == '*' and i + 1 < n and code[i + 1] == '/':
                        depth -= 1
                        i += 2
                    else:
                        i += 1
                continue
            # triple-quoted string
            if c == '"' and code[i:i + 3] == '"""':
                i += 3
                while i < n and code[i:i + 3] != '"""':
                    i += 1
                i += 3
                continue
            # string
            if c == '"':
                i += 1
                while i < n and code[i] != '"':
                    if code[i] == '\\':
                        i += 1
                    i += 1
                i += 1
                continue
            # char literal
            if c == "'":
                i += 1
                while i < n and code[i] != "'":
                    if code[i] == '\\':
                        i += 1
                    i += 1
                i += 1
                continue
            out.append(c)
            i += 1
        return ''.join(out)

    for kf in kotlin_files:
        code = strip_kotlin(open(kf, encoding="utf-8").read())
        for open_c, close_c in (("{", "}"), ("(", ")"), ("[", "]")):
            if code.count(open_c) != code.count(close_c):
                err(f"{os.path.relpath(kf, ROOT)}: unbalanced {open_c}{close_c} "
                    f"({code.count(open_c)} vs {code.count(close_c)})")

    # ---------- 6. vector paths ----------
    for xf in xml_files:
        if "drawable" not in xf:
            continue
        text = open(xf, encoding="utf-8").read()
        for m in re.finditer(r'android:pathData="([^"]+)"', text):
            data = m.group(1)
            if not is_valid_path(data):
                err(f"{os.path.relpath(xf, ROOT)}: invalid pathData: {data[:60]}")

    # ---------- report ----------
    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    if errors:
        print(f"\n{len(errors)} error(s), {len(warnings)} warning(s)")
        sys.exit(1)
    print(f"OK — {len(xml_files)} xml files, {len(kotlin_files)} kotlin files, "
          f"{len(ids)} ids, 0 errors")


def is_valid_path(d):
    """Sanity-check SVG path data: letters with well-formed number runs."""
    i = 0
    n = len(d)
    cmds = set("MmLlHhVvCcSsQqTtAaZz")
    seen_cmd = False
    while i < n:
        c = d[i]
        if c in " \t\r\n,":
            i += 1
            continue
        if c in cmds:
            seen_cmd = True
            i += 1
            continue
        # number
        m = re.match(r'[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?', d[i:])
        if not m:
            return False
        i += m.end()
    return seen_cmd


if __name__ == "__main__":
    main()
