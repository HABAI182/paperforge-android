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
    # Round 15: broader Chinese styles, document serifs and sans, handwriting, monospace and other scripts.
    ("noto-sans-hk", ["ofl/notosanshk"], ("思源黑体 香港", "Noto Sans HK"), "SANS", ("繁體中文（香港）", "Traditional Chinese (Hong Kong)"), ["mhei", "microsoftjhenghei"], []),
    ("noto-serif-hk", ["ofl/notoserifhk"], ("思源宋体 香港", "Noto Serif HK"), "SERIF", ("繁體中文（香港）宋體", "Traditional Chinese (Hong Kong) serif"), ["mingliu_hkscs"], []),
    ("noto-serif-jp", ["ofl/notoserifjp"], ("Noto Serif JP", "Noto Serif JP"), "SERIF", ("日文明朝体", "Japanese Mincho"), ["msmincho", "yumincho"], []),
    ("noto-serif-kr", ["ofl/notoserifkr"], ("Noto Serif KR", "Noto Serif KR"), "SERIF", ("韩文明朝体", "Korean serif"), ["batang"], []),
    ("lxgw-wenkai-mono-tc", ["ofl/lxgwwenkaimonotc"], ("霞鹜文楷等宽 TC", "LXGW WenKai Mono TC"), "SCRIPT", ("等宽楷体", "Monospaced Kai"), [], ["Regular", "Bold"]),
    ("long-cang", ["ofl/longcang"], ("龙藏体", "Long Cang"), "HAND", ("简体中文行书手写", "Chinese handwriting"), [], []),
    ("zhi-mang-xing", ["ofl/zhimangxing"], ("志莽行书", "Zhi Mang Xing"), "HAND", ("简体中文行书", "Chinese running script"), [], []),
    ("liu-jian-mao-cao", ["ofl/liujianmaocao"], ("流江毛草", "Liu Jian Mao Cao"), "HAND", ("简体中文草书", "Chinese cursive"), [], []),
    ("zcool-qingke-huangyou", ["ofl/zcoolqingkehuangyou"], ("站酷庆科黄油体", "ZCOOL QingKe HuangYou"), "DISPLAY", ("简体中文标题字", "Chinese display"), [], []),
    ("zcool-kuaile", ["ofl/zcoolkuaile"], ("站酷快乐体", "ZCOOL KuaiLe"), "DISPLAY", ("简体中文活泼标题字", "Chinese playful display"), [], []),
    ("eb-garamond", ["ofl/ebgaramond"], ("EB Garamond", "EB Garamond"), "SERIF", ("西文书籍衬线", "Latin book serif"), ["garamond", "adobegaramond"], []),
    ("crimson-pro", ["ofl/crimsonpro"], ("Crimson Pro", "Crimson Pro"), "SERIF", ("西文正文衬线", "Latin text serif"), ["minion"], []),
    ("libre-baskerville", ["ofl/librebaskerville"], ("Libre Baskerville", "Libre Baskerville"), "SERIF", ("西文衬线", "Latin serif"), ["baskerville"], []),
    ("lora", ["ofl/lora"], ("Lora", "Lora"), "SERIF", ("西文衬线", "Latin serif"), [], []),
    ("pt-serif", ["ofl/ptserif"], ("PT Serif", "PT Serif"), "SERIF", ("西文、西里尔文衬线", "Latin and Cyrillic serif"), [], []),
    ("source-serif-4", ["ofl/sourceserif4"], ("Source Serif 4", "Source Serif 4"), "SERIF", ("西文衬线", "Latin serif"), ["sourceserif"], []),
    ("ibm-plex-serif", ["ofl/ibmplexserif"], ("IBM Plex Serif", "IBM Plex Serif"), "SERIF", ("西文衬线", "Latin serif"), ["plexserif"], ["-Regular.", "-Bold.", "-Italic."]),
    ("gelasio", ["ofl/gelasio"], ("Gelasio", "Gelasio"), "SERIF", ("西文，与 Georgia 等宽", "Latin, metric-compatible with Georgia"), ["georgia"], []),
    ("playfair-display", ["ofl/playfairdisplay"], ("Playfair Display", "Playfair Display"), "DISPLAY", ("西文标题衬线", "Latin display serif"), [], []),
    ("inter", ["ofl/inter"], ("Inter", "Inter"), "SANS", ("西文界面无衬线", "Latin UI sans"), ["segoeui", "helveticaneue"], []),
    ("ibm-plex-sans", ["ofl/ibmplexsans"], ("IBM Plex Sans", "IBM Plex Sans"), "SANS", ("西文无衬线", "Latin sans"), ["plexsans"], []),
    ("work-sans", ["ofl/worksans"], ("Work Sans", "Work Sans"), "SANS", ("西文无衬线", "Latin sans"), [], []),
    ("fira-sans", ["ofl/firasans"], ("Fira Sans", "Fira Sans"), "SANS", ("西文、希腊文、西里尔文", "Latin, Greek, Cyrillic"), ["firasans"], ["-Regular.", "-Bold.", "-Italic."]),
    ("pt-sans", ["ofl/ptsans"], ("PT Sans", "PT Sans"), "SANS", ("西文、西里尔文", "Latin and Cyrillic"), [], []),
    ("nunito-sans", ["ofl/nunitosans"], ("Nunito Sans", "Nunito Sans"), "SANS", ("西文圆润无衬线", "Latin rounded sans"), [], []),
    ("montserrat", ["ofl/montserrat"], ("Montserrat", "Montserrat"), "SANS", ("西文几何无衬线", "Latin geometric sans"), ["gothamrounded", "gotham"], []),
    ("caveat", ["ofl/caveat"], ("Caveat", "Caveat"), "HAND", ("西文手写", "Latin handwriting"), [], []),
    ("dancing-script", ["ofl/dancingscript"], ("Dancing Script", "Dancing Script"), "HAND", ("西文花体", "Latin script"), [], []),
    ("kalam", ["ofl/kalam"], ("Kalam", "Kalam"), "HAND", ("西文、天城文手写", "Latin and Devanagari handwriting"), [], ["-Regular.", "-Bold."]),
    ("patrick-hand", ["ofl/patrickhand"], ("Patrick Hand", "Patrick Hand"), "HAND", ("西文手写", "Latin handwriting"), [], []),
    ("indie-flower", ["ofl/indieflower"], ("Indie Flower", "Indie Flower"), "HAND", ("西文手写", "Latin handwriting"), [], []),
    ("jetbrains-mono", ["ofl/jetbrainsmono"], ("JetBrains Mono", "JetBrains Mono"), "MONO", ("等宽西文（代码）", "Monospace (code)"), ["jetbrainsmono"], []),
    ("ibm-plex-mono", ["ofl/ibmplexmono"], ("IBM Plex Mono", "IBM Plex Mono"), "MONO", ("等宽西文", "Monospace Latin"), ["plexmono"], ["-Regular.", "-Bold.", "-Italic."]),
    ("fira-mono", ["ofl/firamono"], ("Fira Mono", "Fira Mono"), "MONO", ("等宽西文", "Monospace Latin"), ["firamono"], []),
    ("noto-sans-mono", ["ofl/notosansmono"], ("Noto Sans Mono", "Noto Sans Mono"), "MONO", ("等宽，多语言", "Monospace, multilingual"), [], []),
    ("noto-sans-arabic", ["ofl/notosansarabic"], ("Noto Sans Arabic", "Noto Sans Arabic"), "SANS", ("阿拉伯文", "Arabic"), ["arial", "tahoma"], []),
    ("noto-naskh-arabic", ["ofl/notonaskharabic"], ("Noto Naskh Arabic", "Noto Naskh Arabic"), "SERIF", ("阿拉伯文纳斯赫体", "Arabic Naskh"), ["traditionalarabic", "simplifiedarabic"], []),
    ("noto-sans-hebrew", ["ofl/notosanshebrew"], ("Noto Sans Hebrew", "Noto Sans Hebrew"), "SANS", ("希伯来文", "Hebrew"), ["david", "narkisim"], []),
    ("noto-sans-devanagari", ["ofl/notosansdevanagari"], ("Noto Sans Devanagari", "Noto Sans Devanagari"), "SANS", ("天城文（印地文等）", "Devanagari (Hindi and more)"), ["mangal"], []),
    ("noto-sans-thai", ["ofl/notosansthai"], ("Noto Sans Thai", "Noto Sans Thai"), "SANS", ("泰文", "Thai"), ["angsana", "cordia", "leelawadee"], []),
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
            "https://testingcf.jsdelivr.net/gh/google/fonts@{commit}/{path}",
            "https://quantil.jsdelivr.net/gh/google/fonts@{commit}/{path}",
            "https://cdn.jsdmirror.com/gh/google/fonts@{commit}/{path}",
            "https://raw.githubusercontent.com/google/fonts/{commit}/{path}",
            "https://raw.gitmirror.com/google/fonts/{commit}/{path}",
            "https://github.com/google/fonts/raw/{commit}/{path}",
        ],
        "families": families,
    }
    json.dump(catalog, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
