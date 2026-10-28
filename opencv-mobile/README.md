# PaperForge OpenCV mobile dependency

OpenCV 4.12.0, Apache-2.0, built from the fixed upstream source and archive SHA256 in
`UPSTREAM.json`. The small, unmodified Java API JAR and Android resource come from the
official Maven AAR; their provenance is recorded separately from the native build.
The application already carries OpenCV and retained codec license notices under
`app/src/main/assets/licenses/`.

Only `core`, `imgproc`, `imgcodecs`, `photo`, and Java JNI are compiled. Scanning and
Paddle use these APIs; illumination repair requires `photo.inpaint`. JPEG, PNG,
and WebP remain enabled. OpenCV DNN, video, calibration, feature matching, stitching,
ML, OpenCL and unused codecs are omitted. The app's separate MNN detector and ORT
Paddle backend continue to use their existing runtimes.
KleidiCV and ADE downloads are explicitly disabled to keep the result independent
of whether optional third-party downloads happen to succeed. Baseline ARM NEON
intrinsics remain enabled; physical ARM64 performance is a separate acceptance item.

The generated Java API still exposes upstream classes outside these modules; these
classes are not supported by this native library and must not be introduced into
application code without updating the module list. Upstream Java names remain
stable through R8 for JNI. The native target uses 16 KiB ELF load alignment.

Ordinary builds fetch and verify the source archive. For an offline build, pass
`-PpaperforgeOpenCvSource=/absolute/path/to/extracted/opencv-source` to Gradle after
verifying the archive against `UPSTREAM.json`. This overrides the fetch, so the
caller is responsible for the local checkout. Only `opencv_java` is built; the
upstream Android SDK skeleton and its recursive Gradle build are not build targets.
