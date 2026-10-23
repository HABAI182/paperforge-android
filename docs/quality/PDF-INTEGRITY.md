# PDF preservation and renderer checks

## Normal compression

`compress` now rewrites eligible embedded image resources; it does not redraw pages. Graphics-stream placement probing measures every use through nested forms and uses the largest physical placement for reused images. Only opaque 8-bit DeviceRGB/DeviceGray resources without special semantics enter recompression. Stencils, masks, profiles/CMYK/indexed colors, annotation appearances, unmeasured/pattern resources, and oversized non-JPEG decodes are reported/skipped. Signed files are copied byte-for-byte. Recursion/pixel budgets and coroutine cancellation keep output atomic. A rewrite that is not smaller yields the original bytes. Per-file `.report.json` records image decisions and dimensions.

`optimize` now performs this same real compression/deduplication at quality90. Identical eligible image streams share a resource. Fonts, native streams, page geometry and non-image resources are preserved. The old full-page raster conversion remains separately named **Flatten / 扁平化为图像 PDF**, with its loss explained before execution.

## Measured generated fixtures

- API35 AOSP `PdfSemanticTest`: two pages, nested form → form → shared 2000×2600 image, native text/vector rule, URI links, AcroForm value, title. **6,329,327 → 44,607 bytes**. This deliberately high-entropy synthetic JPEG is not a typical-document compression ratio.
- Largest image placement200×260pt; output444×578 at target160DPI. Both uses remain present. Decoded native page content streams have identical SHA256; native text extraction, both URI links, field `owner=Owner value`, title and page count remain identical in Android PdfBox and independent pypdf checks.
- Poppler native top/bottom regions (excluding the deliberately recompressed image) have **0.0 mean absolute pixel difference**. `semantic-objects.json` records values/hashes; `pdf_integrity.py` reproduces them.
- A native text-only PDF remains selectable and **842 → 842 bytes**, with original-copy fallback.
- The first implementation found a pinned PdfBox `getImage(null,subsampling)` bitmap-crop exception. Ordinary DCT images now use bounds-checked BitmapFactory decoding; other streams use only budgeted sample1. This was reproduced and the same test passes after the correction.

## Scan encoding across renderers

`PdfEncodingTest` produces actual RGB JPEG, DeviceGray JPEG and 1-bit Flate PDFs, checks dictionary metadata/no SMask, and renders each through Android PdfRenderer. `inspect_pdf.py` independently checks them with pypdf and renders through Poppler. All are300DPI. Android–Poppler pixel MAE is **0.4252 or lower on a 0–255 scale** (edge antialiasing); black/white patch polarity agrees. See encoding-objects.json. This is a generated-patch compatibility check, not all-PDF equivalence.

Existing CJK embedded font/ToUnicode stays intact. The full 82-case current pipeline benchmark still measures3.92% mean OCR CER and13/13 exact regression prefixes after image encoding. A mixed scanned-page plus small native header is no longer skipped solely because it has any text: OCR checks native coverage and excludes overlapping text regions. Real-world PDF profiles, malformed fonts, uncommon inline images and signatures require further selected-file acceptance; no universal compatibility claim.
