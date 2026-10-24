# Optional desktop adapter

The default PaperForge workflow runs entirely on Android. This directory is an optional offline adapter for desktop batch processing; it is not required for phone Office, tables, DOCX or picture PPTX conversion.

Use Python 3.11+ with the pinned `requirements.txt`, plus an installed LibreOffice (or configure `soffice` in `config.json`). `python paperforge_desktop.py` opens the local UI; `python paperforge_desktop.py task.zip --output results` processes a phone task package. `--capabilities` prints actual engine availability. Direct files can use `--mode office-pdf`, `office-docx`, `office-odt`, `office-rtf`, `office-xlsx`, `office-ods`, `office-csv`, `office-pptx`, `office-odp`, `pdf-docx`, `tables-xlsx`, `tables-csv` or `pdf-pptx`.

Office conversion uses the real LibreOffice renderer. Structured PDF→DOCX uses pdf2docx, whose upstream is no longer actively maintained. Tables use PyMuPDF/OpenCV and optional bundled RapidOCR models. These optional Python dependencies retain their upstream licenses and are not included as Windows runtime binaries in this release.
