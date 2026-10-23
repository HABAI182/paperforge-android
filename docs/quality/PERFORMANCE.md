# Resource and cancellation probe

API35 AOSP x86_64 emulator,3072MB configured RAM, host Windows/JDK17. `PerformanceTest` exports100 BW pages from the fixed3024×4032 synthetic source, Draft150DPI, then cancels a second100-page job after3% and probes High rendering.

-100-page PDF: **1,259,678 bytes**,100 pages independently opened through PdfBox.
-Total including cancellation and High probe: **23,606ms**. This is not a phone-throughput figure or a pure export-only timing.
-PSS sampled every50ms: **337,412KiB maximum observed**. Sampling can miss instantaneous peaks; this is not a low-memory-device guarantee.
-High render: **2631×3508**. Cancellation leaves neither final file nor `.part`.
-No crash/OOM in this run. Processing is sequential and decoder region allocation is bounded by18MP. OCR100-page throughput, thermal/battery behavior, low-RAM phones and actual50MP photos are not measured. Geometry unit cases exercise50MP sampling arithmetic only; they do not count as a50MP runtime test.

The earlier full82-case detector/OCR/PDF run sampled312,201KiB after stages; that lower-frequency snapshot is not directly comparable with this50ms sampler. Raw performance-results.json is retained for reproduction. No repeated stress loop was run after passing.
