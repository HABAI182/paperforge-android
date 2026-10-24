# Synthetic conversion fixtures

The DOCX/XLSX/PPTX sources in `fixtures/` were generated for PaperForge with python-docx/openpyxl/python-pptx. Their table has five rows, three columns, Chinese labels, identifiers `00123` / `00456`, amounts `120.50` / `80.00` / `200.50`, and merged headers/totals. No personal document was used.

`phone-office-result.pdf` was produced by the real Android LibreOfficeKit engine. `phone-table-*.xlsx`, `phone-structure.docx`, and `phone-slides.pptx` were produced by the Android application and read with independent Office parsers. The Android test assets also include a rasterized table, borderless aligned text and a two-page matching-header table. The desktop roundtrip fixture is a separate generated long Chinese/English text PDF converted to DOCX and imported by the app.

Timing and package details are in the JSON records. These synthetic examples verify file structure and known contents; they are not a general accuracy claim for arbitrary scans.
