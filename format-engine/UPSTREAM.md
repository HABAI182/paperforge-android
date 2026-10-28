# Independent on-phone format engine

This optional APK provides a signature-protected bound service. PaperForge grants input/output file descriptors, retains the original, and receives a page ZIP with physical geometry, source text regions, warnings and a source SHA256. The engine has no network permission or account dependency. It uses Android WebView for the original OFD/DjVu adapters, MuPDF for additional layouts, Mime4j for email, JLaTeXMath for mathematics, and veraPDF for complete PDF/A profiles. It is distributed under AGPL-3.0-or-later; see COPYING. The main app talks to the independent service through its descriptor protocol.

Pinned sources:

* Additional native/parser sources, exact artifacts, hashes and full license texts are in `src/main/assets/native/UPSTREAM.json` and neighboring notices. MuPDF Fitz 1.28.5 is the unmodified official AAR, packaged for the selected 64-bit ABI. `-PpaperforgeFitzAar=/absolute/path/to/fitz.aar` is an optional local cache override with exact SHA256 enforcement; the normal build resolves the same pinned Maven version.
* New adapters preserve mapped text and basic vectors through the native PDF device, subset embedded fonts and canonicalize the generated ToUnicode maps for consistent phone extraction. LaTeX is a stated common-structure subset with rasterized formula images, not a full TeX runtime. CAJ support is limited to PDF-backed containers. See `docs/development/SPECIAL-FORMATS.md` for every route and limitation.

* [DLTech21/ofd.js](https://github.com/DLTech21/ofd.js), commit `c426b690dfca3d602f105476639f794479fed1b3`, package 0.2.6, Apache-2.0. Unmodified source is under `vendor/ofdjs/src`, with its license. Its JBIG2/PDF.js notices are retained.
* [RussCoder/djvujs](https://github.com/RussCoder/djvujs), commit `eca3e69c966f3b7fcf0453f39029bc1e409fea8a`, library 0.5.4. The **library** is GPL-2.0-or-later; the Unlicense applies to the separate viewer and extension, which are not used here. The library and all upstream license files are under `vendor/djvujs`.
* Build dependencies and integrity records are pinned in `package-lock.json`. Assets are checked in; rebuilding uses `npm ci --ignore-scripts --no-audit --no-fund` then `npm run build` in this directory.

`scripts/build.mjs` applies narrow adaptations while keeping the source snapshot unchanged:

* Load actual OFD `FontFile` resources through `FontFace` with unique aliases, including nested resource base locations; record missing/unsupported embedded fonts.
* Remove the preview's ten-pixel page margin, decimal scale rounding and 127-DPI scale ceiling, so requested page geometry is respected.
* Put literal text into SVG text nodes through `textContent`.
* Resolve the old ASN.1 module's two static dependencies explicitly for the browser bundler.

The wrapper supports all OFD document roots in order, renders one page at a time, and preserves per-page physical boxes. DjVu uses its actual DPI and rotated image data. Original text-region geometry is returned when available; no OCR text is invented by this engine. Main-app PDF export adds these regions as an invisible text layer. The visible copy is rasterized at the chosen DPI, not a reversible recreation of the source object model.

Public Android software `WebView.draw` is used in the engine process; no private printing callbacks or reflection are used. Each completed page is atomically cached by source SHA, DPI and adapter version; cancellation ends the isolated engine process. The main app retains its own completed page ZIP and versioned results for retries and backup.

OFD seal appearance and certificate/signature verification are distinct. This engine copies visible marks; it does not transfer or certify source signatures. Detailed fonts, unsupported shapes and real documents should be compared against the retained source.

The two-page DjVu acceptance fixture is generated from our own color pixels by `scripts/make-fixture.mjs`; the second page carries a 90-degree rotation flag. The OFD fixture is generated at runtime with the app's licensed font and synthetic text/vector objects. No upstream example photograph or invoice is redistributed as a test fixture.
