# Changelog

**English** | [简体中文](CHANGELOG.md)

Notes for versions before 2.0.0 are in Chinese in [CHANGELOG.md](CHANGELOG.md).

## 2.0.0 · 2026-11-09

This is the first official release since 1.1.0 (internal build 20036). It includes the 1.2.0 and 1.3.0 development lines and every improvement kept after them. Neither 1.2.0 nor 1.3.0 was published separately on GitHub.

### New features

- **Own page-detection model, PageNet.** Page outlines now come from a model trained by this project. All third-party extracted models were removed; see [provenance](docs/PROVENANCE.md).
- **Camera outline matches the crop.** Once the phone is still, the outline on screen is the crop the capture will make. The outline glides to new positions instead of jumping. The animation was checked only in an emulator; how it feels on a real camera is not yet verified.
- **Engineering drawings.** Construction lines, dashed hidden lines, centre lines and 3D sketches are kept more completely, and the drawing's vertical lines are made upright.
- **Colour pages keep their colour.** Book covers and coloured paper come out in colour. Large colour prints on ordinary pages, such as a cover picture, keep their colour instead of being whitened to an outline.
- **Annotated copies are documents.** Saving an annotated copy of a PDF adds it to the library, named with "Annotated" at the end. The reader shows the pen marks.
- **Capture review and retakes.** After a shot you can view the latest page, adjust it, undo it or retake it. Capture hints flag blur, glare or a cut-off page edge, but never block saving.
- **Book spreads.** The app finds the spine and splits a two-page photo into left and right pages.
- **Receipts and tables.** The app extracts receipt fields and lets you review them, then exports them as CSV or XLSX, with each field traced back to its place on the page.
- **OCR proofreading.** Recognition confidence is shown, and lines can be reviewed from least to most confident. Offline OCR also suggests document names and categories.
- **Folders and batch naming.** A folder row sits above the document list. You can preview and apply batch renames offline.
- **Process shared PDFs.** PDFs shared from other apps can be cleaned before sharing on, or saved locally.
- **Watermark tools.** Add visible watermarks, or manually remove a watermark text layer from documents you are entitled to modify.
- **LAN library.** After pairing, you can browse the phone's documents from a computer's web browser.
- **AI enhance and repair, on device.** Optional detail restoration and super-resolution create a separate copy and keep the original.

### Improvements

- **Scan quality.**
  - Paper comes out whiter and cleaner, with less shading, show-through and paper texture.
  - Printed text, including punctuation and thin serif strokes, and handwriting come out more complete. Faint pencil and thin strokes survive better.
  - Output resolution is higher.
- **Ruled notebooks.** Rules are redrawn only on genuinely ruled paper. Strokes that cross or touch a rule no longer break on either side of it.
- **Geometry.** After perspective correction, long straight lines and text rows are straightened again. Levelling and flattening of curved pages are improved.
- **Automatic cropping.** The page outline is checked against the photo's own paper edges, so a blue cover is no longer cropped to one light panel. A colour picture at the bottom of a cover no longer ends the page early.
- **Manual crop.** You adjust the outline on the original photo, so it can now grow past the old page edge. "Full image" and "Add margin" work on the whole photo. Saving reprocesses the page from the new outline, and the change can be undone.
- **Thumbnails.** Shrunken pages no longer turn rules into dots and dashes. While a page is being processed, the thumbnail shows the cropped photo with a spinner instead of a half-finished page.
- **Reading and export.** Exported files appear immediately, and previews are cached. New scans are named "PaperForge MM-DD-YYYY HH.MM".
- **More reliable updates.**
  - When the GitHub API can't be reached, the release manifest is read from github.com or a mirror in mainland China.
  - Downloads try GitHub and then each mirror, resuming the same partial file.
  - Every file must match its SHA-256 and carry the same signature as the installed PaperForge, so a tampered file cannot be installed.
  - Switching between sources mid-download has not yet been tested end to end on a real network.
- **Editor.** The editor is laid out around a persistent thumbnail strip. Preview adjustments apply live, and transitions are smoother.

### Bug fixes

- Exports no longer take overly long, and an interrupted export can resume.
- Empty folders now refresh after a backup restore, and stale undo history is cleared.
- A frame-hugging outline is no longer reported as a found page.
- In the crop editor, dragging the outline no longer swaps the photo for the processed page or stops it growing outward.
- Camera thumbnails now match the final page.

### Internal

- **Licence.** From this release, PaperForge's own code is released under the GNU AGPL-3.0-or-later (see [LICENSE](LICENSE)), matching the bundled MuPDF.
- Image processing is parallelised and uses less memory on large photos. Each step is timed, so slow ones are easy to find.
- The build refuses to package any `.mnn` model file, so removed models cannot slip back in.
- New automated tests cover the crop source, consistency between the camera outline and the crop, annotation display and update fallbacks.

### Known limitations

- **Stacked sheets.** When a sheet lies on a book or other sheets, automatic cropping may still include the paper underneath. Two automatic fixes damaged other pages and were not shipped. Adjust the outline by hand in the camera or the editor.
- **Real-device validation.**
  - Tested only in an emulator: the smooth outline animation, and how fast the camera settles on the final crop.
  - Tested only against a local server: switching to mirrors mid-download.
- **Quality gaps.**
  - Strokes can look slightly grainy when zoomed in.
  - Very faint strokes can still break away from rules.
  - Pale printed stripes on a cover can be taken for rules and darkened.
- **Older releases.** The public 1.1.0 and earlier APKs, and the Git history, still contain the removed third-party extracted models. See [provenance](docs/PROVENANCE.md).
