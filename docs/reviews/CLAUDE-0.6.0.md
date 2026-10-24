# PaperForge (纸匠) — Repo Review + Upgrade Brief for Codex

Repo reviewed: github.com/HABAI182/paperforge-android, HEAD `0e4702e` (v0.6.0), Oct 2026.
Basis: README, docs/ (quality, roadmap, architecture, licenses), `implementation-status.json`, theme + screen code, committed acceptance screenshots. Build and device runs belong to the implementation phase and feed the measurement tasks in §7.

> **Working assumption.** One user, one owner, one phone. This is a private personal lab, so the brief optimises for capability rather than audience. §0 states the latitude; the private repo is the only archive.

---

## 0. Working premise — a private one-user laboratory

- **One user is the whole audience.** The only consumer is the project owner. Optimise for the owner's documents, the owner's phone and the owner's taste; store ceremony and audience-facing polish stay off the critical path.
- **Ambition is the default.** When two paths are plausible, take the one that could win outright, keep a revert point, and let the benchmark decide. An idea gets rejected on measurement, never on unfamiliarity.
- **Prototype faster than you polish.** A rough spike that produces a contact sheet today beats a tidy design that produces one next week.
- **Copy what already works.** Permissive OSS (MIT / BSD / Apache-2.0) is copy-and-ship with an `Adapted from …` note; copyleft code (GPL / AGPL / MPL) goes into the private build; closed-source apps are research material — outputs, timings, UI flow, crop behaviour, filter results, and decompilation for understanding — with the implementation rewritten as PaperForge's own code. Extracted assets, models and fonts stay in the local private build.
- **Benchmark the closed-source field.** CamScanner / 扫描全能王, Microsoft Lens, Adobe Scan, 白描, 夸克扫描王 and Google Drive's scanner set the bar: run them on the same photos, compare crop snap, edge fit, filter look and OCR export, then beat them on the owner's documents.
- **The private repo is the whole archive.** Code, the CamScanner-derived models, evidence logs, screenshots, sample PDFs and APKs all live in git together; Git LFS handles any binary that gets heavy. Single working line, no branch ceremony, no publication step.

---

## 1. One-paragraph verdict

PaperForge is an unusually disciplined solo/AI-built project: offline-only, privacy-clean, non-destructive, with real benchmark scaffolding and clear docs. Its weak points are not missing features. They are (a) **all quality evidence is synthetic**, so the "algorithms feel far from expectations" problem cannot be seen or fixed with the current measurements; (b) **a note on the CamScanner-derived models**, right for the owner's private personal repo and committed there with the code (the repo was public only for this review and is private now); (c) **a UI that is clean but generic** (stock Material 3, no type scale, zero motion, zero haptics, placeholder icons instead of document thumbnails); and (d) **69 of 98 roadmap cards still "planned"**, including nearly all core-scan cards. Recommended order: keep the repo private → build a real-photo benchmark → upgrade scan UX/quality → visual polish → new features.

---

## 2. Pros

| Area | What is good |
|---|---|
| Privacy | No `INTERNET` permission, no login/ads/watermark; optional app lock + FLAG_SECURE. Strong, marketable position. |
| Data safety | Originals immutable; edits are derived; undo/redo survives process death; Room migrations 1→7 with exported schemas; exports go `.part` → fsync → parse-verify → rename; SAF exports read back byte-by-byte. |
| PDF engineering | Compression preserves native content streams, links, forms; true 1-bit Flate B/W and real gray JPEG; sample 6.3 MB → 44 KB with identical text/links/forms; 100-page export is memory-bounded and cancellable. |
| China-first | Chinese + Latin offline OCR, CJK FTS4 full-text search, embedded CJK font for searchable PDF, Chinese UI copy. |
| Scan stack | CameraX shared ViewPort/UseCaseGroup, ORT DocQuadNet detector + MNN edge/dewarp models in native C++, classical detector as fallback, outline smoothing, auto-capture thresholds. |
| Breadth | Scan, OCR, PDF merge/split/redact/sign/encrypt, Office↔PDF via on-phone LibreOfficeKit, tables→editable XLSX with merged cells and manual cell review. Rare offline on mobile. |
| Process | Fixed-seed benchmarks, baseline-vs-new comparisons, per-version acceptance folders, license/provenance tracking, 40+ docs that state limits openly. |

## 3. Cons / risks

**P0 — private personal repo**
1. **CamScanner-derived models.** `LICENSE_NOTICES.md` says `border.mnn` and `dewarp.mnn` were recovered from the user-provided CamScanner 7.27.5 APK and are "not open-source-licensed". The repo is private and personal, so the models stay committed as part of the app; record the provenance line precisely in the notice file. An openly licensed swap is an optional experiment (see A3), not a gate.
2. **No project license.** Intentionally absent for a private personal repo; `LICENSE_NOTICES.md` exists to record third-party provenance, not to grant rights.

**P0 — quality can't be seen**
3. **Everything measured is synthetic.** The headline numbers (IoU 0.997, CER 3.92%, 0/65 edge failures) come from 82 self-generated images that the docs themselves call "development evidence, not independent validation". No real phone, no real paper/glare/pencil/book curvature, no competitor comparison. Detector timing (~147 ms p50) was not measured on real ARM64 hardware. This is exactly why quality "feels far from expectations" while the reports say it is great.
4. **One OCR engine (bundled ML Kit, closed model).** No layout analysis, weak on tables/vertical text/handwriting; second engine (PaddleOCR) explicitly deferred. Both ONNX Runtime and MNN are already shipped, so adding open OCR models mostly costs model weights, not new runtimes.

**P1 — product**
5. **69 planned / 18 partial / 10 done / 1 ready.** All `CORE01–05` and `SCN01–08` (the scan core: crop magnifier, spine/book, dewarp traceability, long receipts, finger removal, glare) are still planned.
6. **Conversion limits:** Office layout needs a *separate* APK install; PPTX output is one flat image per slide; PDF→DOCX is best-effort reconstruction.
7. **No localization:** 0 `stringResource` uses. All Chinese strings are hard-coded in Kotlin, so no English/other locale and harder copy edits.

**P1 — code & repo hygiene**
8. **Large single files:** `ReaderScreen` 900 lines, `MainViewModel` 898, `HomeScreen` 807, `EditorScreen` 733, `MainActivity` 673; single Gradle `app` module; `UserSettings` is string-keyed (`"theme"`, `"accent"`), not typed.
9. **Repo is evidence-heavy:** ~40 md files, acceptance logs, lint XML, zips, 28 PNGs and binary PDFs committed; that is fine for a private archive and useful as history. The README itself reads like a changelog, has no hero screenshots, and its first screen does not say what the app does in one line.

**P2 — visual design** (details in §5)
10. Stock M3 look: no `Typography`, no custom `Shapes`, **no animations (0 usages found), no haptics, no dynamic color**, 36 hard-coded `Color(0x…)` in `ui/`. Document list uses generic icon tiles instead of page thumbnails. Home is dominated by a large green hero card. Conversion center rows all use the same arrow icon.

---

## 4. Priority task list for Codex

Work in this order; each task states its "done when". Take the ambitious version of each task — the benchmark settles disagreements.

### A. Repo basics (personal, private project)
- **A1. Keep the repo private** — code, models, evidence, screenshots, sample PDFs and APKs commit together. Add a one-line provenance note at the top of `LICENSE_NOTICES.md`: "Third-party models are included for private personal use; provenance below."
- **A2. README rewrite.** First 10 lines: what it is, 3 screenshots (home, camera, reader), privacy promise. Move version history to `CHANGELOG.md`.
- **A3. (Optional experiment)** compare openly licensed replacements — DocQuadNet is already Apache-2.0; evaluate UVDoc / DocTr-style models for dewarp — and swap only on a quality or size win.

### B. Make quality measurable (highest leverage for "algorithms feel weak")
- **B1. Real-photo benchmark.** Add a **private corpus** committed in the private repo — 50–100 phone photos covering A4 on desk, curved book, receipt, ID card, whiteboard, glare, low light, shadow, pencil (the owner's own documents may sit in `bench-private/` if preferred). Ground-truth corners via a quick annotation tool (`scripts/bench/annotate.py` exists) and text for OCR. Report IoU / corner error / CER on real photos separately from synthetic.
- **B2. On-device timing.** Add an in-app (debug) "Diagnostics" screen: detector ms, OCR ms per page, peak memory, thermal state. Export JSON. Use it on a real ARM64 phone.
- **B3. Scan-failure capture.** Debug-only toggle "save failed capture + overlay" so bad cases from daily use feed back into the corpus.

### C. OCR upgrade
- **C1. Second engine behind `OcrEngine`:** PaddleOCR PP-OCR (det + rec, ONNX Runtime or MNN, already in the APK) as an optional downloadable-or-sideloaded model pack with its own license file. Compare against ML Kit on the real corpus (B1) and only make it default if CER/latency wins.
- **C2. Speed tiers** 快速 / 均衡 / 精细 (OCR01), **raw vs corrected text separated** (OCR02), **region re-recognize** by drawing a box (OCR03).
- **C3. Text selection in the reader** using native PDF text first, OCR layer second (R07/OCR10). This is the single most "app feels pro" interaction missing.

### D. Scan UX
- **D1. Post-capture review tray:** after each shot show a small badge per page — sharp/blurry, glare, crop confidence (reuse `CaptureQualityPolicy`) — with one-tap *retake* and *adjust corners*. The shutter stays available at all times.
- **D2. Auto-capture feedback:** corners lock with a short snap animation, a ring around the shutter fills while steady, haptic tick on lock, subtle click on capture, thumbnail "flies" into the page counter.
- **D3. Mode strip** 文档 / 书籍 / 票据 / 证件 / 白板 with per-mode defaults (enhancement, aspect, split spine for book, double-sided merge for ID, long stitch for receipt). Roadmap SCN02/05/06 already describe these.
- **D4. Crop screen:** magnifier loupe while dragging a corner; edge-drag; "自动 / 全图 / 重置" bar; maximize canvas (UI04).
- **D5. Seal/stamp removal** (红章去除): red-channel mask + inpaint, very common need for Chinese documents; offer as an Enhance option with before/after.
- **D6. Handwriting/ink-lighten and finger removal** as later experiments (SCN07), not before B1.

### E. Reader & library
- **E1. Real thumbnails** for every document card (cache first page; invalidate on edit). Badges: 草稿 / 已识字 / 已加密 / 页数.
- **E2. Search results with snippet + page hit** (LIB03), highlight in reader.
- **E3. Reader:** PDF outline/TOC (R08), link + "back to where I was" (R09), page scrubber with thumbnail preview, RTL/two-page cover mode (R12), TTS (R14).
- **E4. Library:** date-grouped headers (今天 / 昨天 / 本周 / 更早), swipe actions with Undo snackbar, 收件箱 for unfiled scans (LIB04), duplicate detection by perceptual hash (LIB09), storage usage + clear caches (LIB05).
- **E5. Smart naming:** after OCR propose a title/date from the first lines (e.g. "发票 2026-10-23 – 公司名"); user taps to accept. Rules-based first; no cloud.

### F. High-value practical tools
- **F1. Compress to target size** (≤200 KB / 500 KB / 1 MB / custom) — heavily used for government/school upload forms. Binary-search JPEG quality + DPI; show the predicted result before saving (PDF05).
- **F2. Auto-suggest redaction** from OCR text: 18-digit ID numbers, mobile numbers, bank-card patterns, emails → highlight as candidates, user confirms (PDF04). Ties into the existing manual redaction.
- **F3. Form fill + sign flow:** saved signature + stamp + date text in one sheet; flatten on export.
- **F4. System entry points:** launcher shortcuts (扫描 / 打开最近), Quick Settings tile, share-target already exists, add "Scan" App Shortcut and a "Continue reading" widget (SET05).
- **F5. Task center** (JOB01) for long conversions/exports with notification progress and retry per item.
- **F6. Named profiles** (日常 / 书籍 / 票据 / 高清归档) that bundle scan+enhance+export presets (SET01).

### G. Engineering hygiene
- **G1. Externalize strings** to `res/values/strings.xml` (+ `values-en`). Add lint check for hard-coded text.
- **G2. Split large files:** extract per-feature ViewModels from `MainViewModel`; split `ReaderScreen` into toolbar / canvas / panels; keep behavior identical and keep existing androidTests green.
- **G3. Typed settings** (data class + DataStore) instead of string keys.
- **G4. Keep evidence in git:** commit summaries, logs, PNG/zip evidence and sample PDFs beside the code (Git LFS for the heavy binaries); no separate evidence channel.
- **G5. CI:** GitHub Actions running `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` on every push; upload APK artifact.

---

## 5. UI / aesthetics upgrade spec

Current state (from the committed v0.6.0 screenshots): warm off-white background, deep green accent, rounded tonal cards, 4-item bottom bar (首页 / 文档 / 转换 / 工具), large bold title "纸匠". It is tidy and calm, but looks like default Material 3. The design doc (`docs/roadmap/05-UI设计与页面规范.md`) already says the right things; the code just hasn't caught up (UI01/03/07/08/09 partial, UI04/05 planned).

### 5.1 Design tokens (do first, everything else inherits)
- **Typography:** define a full `Typography` in `PaperForgeTheme`. Suggested scale (sp): display 30/36 semibold for screen titles, title 20/26 medium, body 15/23 regular (CJK line-height ≈ 1.5), label 12/16 medium, numeric styles with `tabular-nums` (`fontFeatureSettings = "tnum"`) for page counts/sizes/progress. Keep system CJK sans for body; optionally bundle a **subset-only** serif face for the "纸匠" wordmark (a few glyphs, a few KB) to give brand character without APK bloat.
- **Shapes:** `Shapes(small = 10.dp, medium = 14.dp, large = 20.dp, extraLarge = 28.dp)`; cards 14–16 dp, sheets 24 dp, buttons fully rounded only for primary actions.
- **Color:** keep ink-green but add semantic roles that are currently missing: `tertiary` (warm amber for warnings/"draft"), `error`, `scrim`, `surfaceTint = Transparent`, success state. Add **optional Material You dynamic color** (whole scheme swapped, not just primary). Add a true-black **AMOLED** dark option. Remove the 36 hard-coded `Color(0x…)` in `ui/` in favor of theme roles. Make sure dialogs/bottom sheets use the themed container colors (an older screenshot shows a lavender default dialog tint).
- **Elevation:** rely on tonal surfaces + hairline `outlineVariant` (0.5–1 dp); shadow only for FAB/floating bars.
- **Motion tokens:** 120 ms (state), 200 ms (panel), 280 ms (screen); easing `FastOutSlowIn`; springs for crop handles. Respect a "reduce motion" setting (the doc already promises one). Currently there are no animation calls at all.
- **Haptics:** `HapticFeedback` on corner lock, capture, selection toggle, destructive confirm. Make it a setting.
- **Icons:** keep Material icons for actions, but create small **format badges** (PDF / DOCX / XLSX / PPTX / JPG / TXT) as vector pills with distinct tints for document types and conversion cards.

### 5.2 Screen by screen

**Home (documents first)**
- Replace the large green hero card with: top app bar (纸匠 + small subtitle only on first run), **search bar pinned at the very top** (today it sits *below* the "最近文档" heading), filter chips (最近 / 收藏 / 未整理 / 已识字), and an **extended FAB "扫描"** bottom-end. The three equal tiles (相册导入 / 打开PDF / 图片识字) become one "＋ 导入" menu.
- Document cards: **real first-page thumbnail** (rounded 10 dp, subtle border), title (2 lines max), meta line "PDF · 12 页 · 今天 09:43", small status chips. Optional grid view with large covers.
- Optional "继续阅读" card with progress bar when a recent reading exists.
- Empty state: small original vector illustration + two buttons (扫描 / 导入).

**Camera**
- Full-bleed preview; top row only back / flash / auto-manual / more; outside the safe frame add a dim scrim so the detected quad pops.
- Quad: 2.5 dp stroke in primary, 12 % fill, animated morph between frames (already tracked), **snap pulse** when locked.
- Shutter: 72 dp ring with inner circle; steady-progress ring; haptic on lock.
- Bottom: mode strip, gallery shortcut, captured-page stack thumbnail with count that animates when a page is added, "完成" pill.
- Quality hints as a single-line toast chip ("光线较暗 · 保持稳定"), never modal.

**Edit**
- Doc name in the top bar (tap to rename) instead of a large persistent text field that today steals the top third of the screen.
- Canvas maximal. Bottom dock: 裁切 · 增强 · 识字 · 更多, plus a primary 导出 button.
- Enhance: horizontal row of **live preview thumbnails of the current page** (自然 / 清晰 / 去阴影 / 黑白 / 彩色 / 白板), long-press = compare with original, plus an explicit compare button.
- Page strip collapsible; drag to reorder; page grid for management.

**Reader**
- Edge-to-edge page, chrome auto-hides with a 180 ms fade; fix the large empty gap above page 1 seen in the screenshot (reduce top padding when chrome hidden).
- Title truncation: allow 2 lines or marquee instead of "PaperForge-PaperForg…".
- Page indicator pill + scrubber with thumbnail preview while dragging; bottom bar of 4–5 actions max (页面 / 批注 / 转换 / 分享), put 全屏 in the top bar.
- Paper tint (warm/night) as a smooth cross-fade, with a proper AMOLED night mode.

**Conversion center**
- Drop the large marketing-style headline "让文件自由流转" (the design doc itself says the screen should not introduce itself). Use a compact title + search + chips.
- Each row: **source badge → target badge** (e.g. `DOCX → PDF`), one-line description, small tag (手机本机 / 需 Office 引擎), instead of the same arrow icon on every row.
- Top section "最近转换"; show engine status (Office 引擎: 已安装 / 去安装) in a single inline banner.

**Settings**
- Group by purpose with section headers, search, per-group "恢复默认"; live preview for theme/accent/font size at the top of Appearance.

**Adaptive**
- List-detail for tablets/foldables (UI08), `WindowSizeClass`, max content width 640 dp for forms.

### 5.3 Accessibility checklist
Content descriptions for all icon buttons; 48 dp touch targets; contrast check for accent on both schemes; cap the custom font-scale multiplier applied on top of the system scale; test at 200 % font size and TalkBack.

---

## 6. Suggested kickoff prompt for Codex (copy/paste)

> You are working on `HABAI182/paperforge-android` (Kotlin, Compose, CameraX, OpenCV, ONNX Runtime, MNN, PdfBox-Android, Room). Read `docs/ARCHITECTURE.md`, `docs/roadmap/05-UI设计与页面规范.md`, `docs/roadmap/implementation-status.json`, and `PaperForge-review-for-Codex.md`. Keep existing features working (multi-select, direct delete, backup, export, settings JSON). This is a private personal app; the repo and Releases stay private, and models, evidence and samples are committed with the code. Move fast: one owner consumes the result, so take the ambitious option, port the best open-source algorithm directly (permissive code ships with an `Adapted from …` note, copyleft code stays in the private build), and study CamScanner / Microsoft Lens / Adobe Scan behaviour — screenshots, outputs, decompilation for understanding — then beat them with PaperForge's own implementation. Work in small commits, one task at a time, in this order: **A2 → G1 + design tokens (§5.1) → E1 (document thumbnails) → Home redesign (§5.2) → D1/D2 camera feedback → B2 diagnostics screen → F1 compress-to-size → C3 text selection in reader**. For each task: state the files you will touch, implement, run `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`, add or update an androidTest only where behavior changed, and update `implementation-status.json` accurately (partial vs done). Report real-device quality only from physical-phone runs. Keep the manifest network-free.

---

## 7. Measurements still to run

- App behavior, speed, and scan/OCR accuracy on real ARM64 hardware — run the B2 diagnostics screen on the owner's phone.
- `border.mnn` / `dewarp.mnn` provenance — keep the `LICENSE_NOTICES.md` line precise (CamScanner 7.27.5 APK) and the models committed.
- Licenses of candidate replacement models (UVDoc, PP-OCR variants) — confirm when each is added.
