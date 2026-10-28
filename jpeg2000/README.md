# JPEG-2000 support

JP2ForAndroid 1.1.0 Java bindings with unchanged logic and native source, fixed at `8ad43abb5d8e2caf2147162d3bfe5569581b5d1f`. Only vendored Java line endings are normalized to LF. Original and normalized SHA256 values are in `UPSTREAM.json`. This supplies PDFBox's optional JPX decoder/encoder using OpenJPEG 2.5.4.

The upstream published 1.1.0 AAR requires compileSdk 37. These bindings use the existing Bitmap/annotation APIs, so this source module compiles them with PaperForge's API36 / NDK28.2 toolchain; the application target/min SDK stay unchanged. Native sources are fetched at their fixed archive hash during building. The upstream 16 KiB linker options remain in effect. No runtime download is needed.

BSD license texts are bundled in the application's `assets/licenses/JP2ForAndroid-LICENSE.txt` and `OpenJPEG-LICENSE.txt`. Upstream Java copyright headers remain intact. JNI class/method names are preserved for R8.
