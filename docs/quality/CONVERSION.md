# Conversion refinements

TXT/MD/HTML/DOCX now use PDFBox paragraph boundaries, joining wrapped lines with CJK/Latin spacing rules and preserving page boundaries. DOCX keeps editable paragraphs and escaped XML. A separate **原页参考图 + 文字 DOCX** mode places a bounded page reference PNG above editable text, explicitly describing the result as a comparison document rather than reconstructed layout. One page image is rendered at a time. Outputs use temporary files and cancellation checks.

Embedded-image extraction now walks nested form resources, avoids duplicate streams and cycles, and enforces the18MP decode budget. It exports PNGs, so the UI now says so. Inline-image extraction and >18MP original-image extraction remain unsupported, with explicit errors; normal compression can first reduce eligible large JPEGs.

API35 AOSP `ConversionTest` creates a two-page PDF with a reused nested image, wrapped paragraphs and XML-special text. Text/HTML/DOCX/image-DOCX are generated, document XML is parsed, both image relationships are present, and nested extraction returns one unique PNG. No native Word/Office visual acceptance was available. Complex columns/tables, faithful original Office layout, ruled-table XLSX and curved-page reconstruction remain separate P2 experiments; none are represented by a nonfunctional button.
