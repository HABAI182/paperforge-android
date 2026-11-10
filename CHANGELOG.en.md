# Changelog

**English** | [简体中文](CHANGELOG.md)

Notes for versions before 2.0.0 are in Chinese in [CHANGELOG.md](CHANGELOG.md).

## 2.0.1 · 2026-11-10

A revision of 2.0.0 (internal build 20042). It focuses on original-photo detail, manual cropping and automatic edge finding.

### Original-photo detail

- **Unedited full-frame Original page.** PDF export now embeds the camera's own JPEG, with no downscaling or second compression. Image export copies the file unchanged.
- **Cropped Original page.** It keeps the photo's full resolution, where PDF export at Standard used to shrink it to 2572 px on the long side. It is compressed at quality 95 or higher.
- **Photo JPEG quality is back to 97.** An earlier default-settings migration had set every install to 94. This release clears that value; any other value you chose is kept.
- **Resolution.** The camera now asks for the highest 4:3 resolution it supports.
- **Phone's own capture enhancement.** Where the phone offers it, PaperForge uses it (CameraX Extensions in auto mode, such as HDR or night). Otherwise it falls back to normal capture. You can turn it off under Settings → Capture.

### Manual cropping

- **A new magnifier.** It shows the photo at 3×. It moves out of the way of your finger and hand, and never covers the point you are placing. It always stays fully on screen and glides when it changes place.
- **Precise markers.** An open crosshair marks the exact point, and the crop line is drawn thin, so you can line it up with the paper edge. The corner being adjusted shows as a ring instead of a solid dot.
- **Corners follow your finger.** A dragged corner moves relative to your finger instead of jumping under the fingertip.
- **Fine adjustment.** After you let go, arrow keys nudge the corner by about 0.1% of the photo's long side per tap. Holding a key repeats.
- **All Android versions.** The magnifier now appears on every version; Android 8 had none.

### Automatic edge finding

- **PageNet v3.** The page-detection model adds training scenes with printed covers, pens, open notebooks, stacked sheets of the same paper, engineering drawings and light desks.
- **Stacked sheets.** The top sheet's edge is found from where its ruled lines end.
- **Multi-colour book covers.** The whole cover is outlined, not just a central panel.
- **Colour blocks.** Covers made mostly of colour blocks keep their colour instead of being whitened like paper.
- **Edge snapping.** The outline snaps to a nearby continuous paper edge.

### Known limitations

- This release was verified on emulators and the project's test photos. Whether the phone's capture enhancement is available, and how the magnifier feels, still need checking on a real phone.
- When ruled lines on the back show through a printed page, faint traces can remain.

## 2.0.0 · 2026-11-09

This is the first official release since 1.1.0 (internal build 20036). It includes every improvement kept since 1.1.0. The 1.2.0 and 1.3.0 development lines were never published separately, so their contents are part of this release.

### Interface and navigation

- **New three-section layout.** Documents, Workspace and Settings, with a bottom-bar indicator that follows your gesture. Swipe between the three sections and the neighbouring page follows your finger.
- **Workspace cards.** Tools are grouped by purpose into a second layer: scan & OCR, format conversion, PDF tools, organising, tasks & workflows, and connect & intelligence. Frequent tools can be pinned.
- **Redesigned editor.**
  - A persistent thumbnail strip.
  - Main tools: add page, crop, filter, OCR, delete, more.
  - "More" is grouped into six purpose cards.
- **Export result card.** Share or save to the phone are the main actions, and opening in another app is secondary. The save folder can be collapsed.
- **Simpler PDF reader bar.** Just View, Pages, Annotate and Tools. Page mode, full screen, fit page/width and paper colour are all under View.
- **Polish.** Landscape layouts keep more room for the document. Labels and card sizes are now consistent.

### Motion and interaction

- **Cards.** They expand from where you tap and collapse back, and returning keeps your place.
- **Page transitions.** Pages now exit and then enter in one continuous move. Back gestures animate in their direction, category cards arrive in sequence, and buttons respond to a press.
- **Camera outline.** Once the phone is still, the outline shows the final crop and glides to new positions instead of jumping. It was checked only in an emulator; real-phone feel is not yet verified.
- **Reduced motion.** The app follows the system and in-app settings for reduced motion.

### Capture

- **Viewfinder.** The viewfinder is larger, with the controls laid over it. Multi-page capture is the default, and a swipeable mode strip switches between document, original colour, show-through removal and ID modes.
- **After capture.** You can page through the shots and adjust, undo, retake or AI-enhance each one. A page that is still processing shows the cropped photo with a spinner.
- **Hints.** Blur, glare or a cut-off page edge is flagged, but saving is never blocked.
- **Page detection.** It uses PageNet, a model trained by this project, and checks the outline against the photo's real paper edges. The outline is refined again on the full-resolution photo.

### Scan quality

- **Cleaner, whiter paper.** There is less shadow, show-through, paper texture and speckle. Book-gutter shadows and dark corners outside the page are cleaned up.
- **More complete text.** Printed text, including punctuation and thin serif strokes, handwriting and faint pencil are kept better, with more natural stroke tone. On ruled paper, strokes that cross a rule no longer break.
- **Better geometry.**
  - After perspective correction, long lines and text rows are straightened.
  - Ruled pages are flattened, with headers aligned to the rules.
  - Book spreads can be split into two pages at the detected spine.
- **Engineering drawings.** Construction, hidden and centre lines and 3D sketches are kept more completely, and vertical lines are made upright.
- **Colour pages.** Book covers and coloured paper keep their colour, and so do large colour pictures on ordinary pages.
- **Output.** Resolution is higher, and workbook PDFs use lossless page images by default.

### Editing and repair

- **Manual crop.** It works on the original photo and can extend past the old page edge. Saving reprocesses the page, and the change can be undone.
- **Crop, rotation and filters.** Crop and small-angle rotation share one draft. Filters are regenerated from the original, so pages are never enhanced twice.
- **Adjustments.** They preview live, with side-by-side comparison in landscape. Filters are simplified to Original, Workbook, Strong background removal and Grey.
- **Flatten and repair, on device.** Flatten, show-through removal, thin-text enhancement and super-resolution create a separate copy and keep the original.
- **Annotation.** Mark pages with pen, text or mosaic. Annotated PDF copies appear in the library as "<name> Annotated", and the reader shows the marks.
- **Watermark tools.** Add visible watermarks, or manually clean watermark objects from PDFs and Office files you are entitled to modify.

### OCR, reading and documents

- **OCR proofreading.**
  - OCR shows confidence, and lines can be reviewed from least confident up.
  - Missed lines are recognised again.
  - When no matching model is installed, OCR falls back to an offline engine.
- **Text selection.** Selecting text on the page and the full-text view are improved, and the reader shows the whole text.
- **Receipts.** The app extracts date, merchant, amount and other fields, lets you review them, and exports CSV or XLSX traced back to the page.
- **Naming.** Names and categories are suggested from the recognised text, and batch renames can be previewed offline.
- **Shared PDFs.** PDFs shared into the app open as documents first. You can choose "process and share", or save locally.
- **Editor and library.** The editor exports PDF directly. The library is now the single source for conversion, PDF tools and batch work.

### Organising

- **Folders.** They sit above the list and can be created, renamed, moved and removed, including subfolders and empty folders, with undo. Backups include empty folders.
- **Exports and naming.** Exported files appear in the library at once. New scans are named "PaperForge MM-DD-YYYY HH.MM".
- **Thumbnails.** They are sharper and no longer turn rules into dots, and previews are cached.
- **LAN.** After pairing, you can browse the library from a computer's browser, view covers, search by category and download several files at once.

### Performance and reliability

- **Speed and memory.** Image processing is parallelised and uses less memory on large photos. Routine scans finish in the background, and each result can be undone.
- **Fixes.**
  - Exports no longer take overly long, and an interrupted export can resume.
  - Empty folders now refresh after a restore.
- **Updates.**
  - Optional automatic checks and Wi-Fi downloads.
  - When GitHub is unreachable, updates are fetched from a mirror, and downloads resume.
  - Every file must match its SHA-256 and the installed PaperForge's signature.
  - Mid-download mirror switching has not yet been tested end to end on a real network.

### Open-source licence

- From this release, PaperForge's own code is released under the GNU AGPL-3.0-or-later (see [LICENSE](LICENSE)).
- Third-party components keep their own licences. Google ML Kit text recognition is a proprietary component that runs on the device (see [third-party notices](LICENSE_NOTICES.md)).

### Known limitations

- **Stacked sheets.** When a sheet lies on a book or other sheets, automatic cropping may include the paper beneath. Adjust the outline in the camera or the editor.
- **Not yet validated on real devices.** The smooth outline animation and mid-download mirror switching have not been tested on a real phone or network.
- **Quality.**
  - Strokes can look slightly grainy when zoomed in.
  - Very faint strokes away from rules can still break.
  - Pale printed stripes on a cover may be darkened as if they were rules.
