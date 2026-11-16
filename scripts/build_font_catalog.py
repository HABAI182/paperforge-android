"""Build PaperForge's downloadable-font catalog from google/fonts pinned at one commit (metadata only: names, sizes,
git blob SHA-1 from the GitHub API; no font file is downloaded here). Every file is then served byte-identically by
GitHub raw and by jsDelivr's CDNs at that commit; the app verifies the git blob SHA-1 and size of what it receives."""
import json
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
COMMIT = "cc39c4c2c304bc368c9b7c311ea2bcc6013299fe"

# id, folder candidates, names (zh, en), category, coverage (zh, en), replaces (proprietary/common fonts it stands in
# for, matched against a PDF's font names), files wanted (substrings; empty = all ttf).
FAMILIES = [
    ("arimo", ["ofl/arimo", "apache/arimo"], ("Arimo", "Arimo"), "SANS", ("西文，与 Arial 等宽", "Latin, metric-compatible with Arial"), ["arial", "helvetica", "liberationsans"], []),
    ("tinos", ["ofl/tinos", "apache/tinos"], ("Tinos", "Tinos"), "SERIF", ("西文，与 Times New Roman 等宽", "Latin, metric-compatible with Times New Roman"), ["timesnewroman", "times", "liberationserif"], []),
    ("cousine", ["ofl/cousine", "apache/cousine"], ("Cousine", "Cousine"), "MONO", ("等宽，与 Courier New 等宽", "Monospace, metric-compatible with Courier New"), ["couriernew", "courier", "liberationmono"], []),
    ("carlito", ["ofl/carlito"], ("Carlito", "Carlito"), "SANS", ("西文，与 Calibri 等宽", "Latin, metric-compatible with Calibri"), ["calibri"], []),
    ("caladea", ["ofl/caladea"], ("Caladea", "Caladea"), "SERIF", ("西文，与 Cambria 等宽", "Latin, metric-compatible with Cambria"), ["cambria"], []),
    ("noto-sans-sc", ["ofl/notosanssc"], ("思源黑体 简体（完整）", "Noto Sans SC (full)"), "SANS", ("简体中文全字库，可替代微软雅黑、黑体", "Full Simplified Chinese; stands in for Microsoft YaHei, SimHei"), ["microsoftyahei", "simhei", "heiti", "dengxian", "pingfang", "notosanssc", "sourcehansans"], []),
    ("noto-serif-sc", ["ofl/notoserifsc"], ("思源宋体 简体（完整）", "Noto Serif SC (full)"), "SERIF", ("简体中文全字库，可替代宋体", "Full Simplified Chinese; stands in for SimSun, Songti"), ["simsun", "nsimsun", "songti", "stsong", "notoserifsc", "sourcehanserif"], []),
    ("noto-sans-tc", ["ofl/notosanstc"], ("思源黑体 繁體（完整）", "Noto Sans TC (full)"), "SANS", ("繁體中文全字庫", "Full Traditional Chinese"), ["microsoftjhenghei", "pmingliu", "mingliu"], []),
    ("noto-serif-tc", ["ofl/notoseriftc"], ("思源宋体 繁體（完整）", "Noto Serif TC (full)"), "SERIF", ("繁體中文全字庫", "Full Traditional Chinese"), ["mingliu", "pmingliu"], []),
    ("lxgw-wenkai-tc", ["ofl/lxgwwenkaitc"], ("霞鹜文楷 TC", "LXGW WenKai TC"), "SCRIPT", ("楷体风格，繁体为主", "Kai style, Traditional Chinese first"), ["kaiti", "stkaiti", "dfkai"], ["Regular", "Bold"]),
    ("noto-sans-jp", ["ofl/notosansjp"], ("Noto Sans JP", "Noto Sans JP"), "SANS", ("日文", "Japanese"), ["msgothic", "yugothic", "meiryo"], []),
    ("noto-sans-kr", ["ofl/notosanskr"], ("Noto Sans KR", "Noto Sans KR"), "SANS", ("韩文", "Korean"), ["malgungothic", "gulim", "batang"], []),
    ("noto-sans", ["ofl/notosans"], ("Noto Sans", "Noto Sans"), "SANS", ("西文、希腊文、西里尔文", "Latin, Greek, Cyrillic"), ["segoeui", "verdana", "tahoma"], []),
    ("noto-serif", ["ofl/notoserif"], ("Noto Serif", "Noto Serif"), "SERIF", ("西文、希腊文、西里尔文", "Latin, Greek, Cyrillic"), ["georgia", "garamond", "palatino", "bookantiqua"], []),
    ("roboto", ["ofl/roboto", "apache/roboto"], ("Roboto", "Roboto"), "SANS", ("西文", "Latin"), ["roboto"], []),
    ("open-sans", ["ofl/opensans"], ("Open Sans", "Open Sans"), "SANS", ("西文", "Latin"), ["opensans"], []),
    ("lato", ["ofl/lato"], ("Lato", "Lato"), "SANS", ("西文", "Latin"), ["lato"], ["Regular", "Bold", "Italic"]),
    ("source-sans-3", ["ofl/sourcesans3"], ("Source Sans 3", "Source Sans 3"), "SANS", ("西文", "Latin"), ["sourcesans", "myriad"], []),
    ("merriweather", ["ofl/merriweather"], ("Merriweather", "Merriweather"), "SERIF", ("西文", "Latin"), ["merriweather"], []),
    ("roboto-mono", ["ofl/robotomono", "apache/robotomono"], ("Roboto Mono", "Roboto Mono"), "MONO", ("等宽西文", "Monospace Latin"), ["consolas", "menlo", "robotomono"], []),
    ("source-code-pro", ["ofl/sourcecodepro"], ("Source Code Pro", "Source Code Pro"), "MONO", ("等宽西文", "Monospace Latin"), ["sourcecodepro", "lucidaconsole"], []),
    ("zcool-xiaowei", ["ofl/zcoolxiaowei"], ("站酷小薇", "ZCOOL XiaoWei"), "SERIF", ("简体中文标题字", "Simplified Chinese display"), [], []),
    ("ma-shan-zheng", ["ofl/mashanzheng"], ("马善政楷书", "Ma Shan Zheng"), "SCRIPT", ("简体中文毛笔楷书", "Simplified Chinese brush Kai"), [], []),
]


def listing(folder):
    out = subprocess.run([GH, "api", f"repos/google/fonts/contents/{folder}?ref={COMMIT}"], capture_output=True, text=True)
    if out.returncode != 0:
        return None
    data = json.loads(out.stdout)
    return data if isinstance(data, list) else None


def main():
    families = []
    for fid, folders, names, category, coverage, replaces, wanted in FAMILIES:
        for folder in folders:
            items = listing(folder)
            if items:
                break
        else:
            print("missing", fid, file=sys.stderr)
            continue
        license_id = "Apache-2.0" if folder.startswith("apache/") else "OFL-1.1" if folder.startswith("ofl/") else "UFL-1.0"
        license_file = next((i["name"] for i in items if i["name"] in ("OFL.txt", "LICENSE.txt", "UFL.txt")), None)
        files = []
        for item in items:
            name = item["name"]
            if not name.endswith(".ttf"):
                continue
            if wanted and not any(w in name for w in wanted):
                continue
            files.append({"name": name, "path": f"{folder}/{name}", "size": item["size"], "gitSha1": item["sha"]})
        if not files:
            print("no ttf", fid, file=sys.stderr)
            continue
        families.append({
            "id": fid, "nameZh": names[0], "nameEn": names[1], "category": category,
            "coverageZh": coverage[0], "coverageEn": coverage[1], "license": license_id,
            "licensePath": f"{folder}/{license_file}" if license_file else None,
            "replaces": replaces, "files": files,
        })
        print(fid, folder, len(files), sum(f["size"] for f in files) // 1024, "KB", file=sys.stderr)
    catalog = {
        "version": 1,
        "repository": "google/fonts",
        "commit": COMMIT,
        "note": "Pinned commit; every source serves these exact bytes. Verify size and git blob SHA-1 (sha1 of 'blob <size>\\0' + data).",
        "sources": [
            "https://cdn.jsdelivr.net/gh/google/fonts@{commit}/{path}",
            "https://fastly.jsdelivr.net/gh/google/fonts@{commit}/{path}",
            "https://gcore.jsdelivr.net/gh/google/fonts@{commit}/{path}",
            "https://raw.githubusercontent.com/google/fonts/{commit}/{path}",
            "https://github.com/google/fonts/raw/{commit}/{path}",
        ],
        "families": families,
    }
    json.dump(catalog, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
