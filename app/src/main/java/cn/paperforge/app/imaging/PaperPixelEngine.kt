package cn.paperforge.app.imaging

/** Native byte-plane loops; OpenCV owns geometry/evidence, existing ScanParams own the look. */
internal object PaperPixelEngine {
    init {
        System.loadLibrary("paperforge_scan")
    }

    external fun normalize(raw: ByteArray, paper: ByteArray, floor: Int): ByteArray

    external fun protectedWorkbook(
        rgb: ByteArray,
        ink: ByteArray,
        soft: ByteArray,
        rules: ByteArray,
        candidate: ByteArray,
        support: ByteArray,
        background: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray,
    ): Array<ByteArray>

    external fun componentProtection(
        ink: ByteArray,
        soft: ByteArray,
        labels: IntArray,
        stats: IntArray,
        width: Int,
        height: Int,
        params: FloatArray,
    ): ByteArray

    external fun workbookPolish(
        gray: ByteArray,
        labels: ByteArray,
        width: Int,
        height: Int,
        cap: Int,
    )

    external fun workbookColor(
        rgb: ByteArray,
        paper: ByteArray,
        gray: ByteArray,
        labels: ByteArray,
        amount: Float,
        colorThreshold: Float,
    ): ByteArray

    external fun componentSeeds(ink: ByteArray, labels: IntArray, stats: IntArray): ByteArray

    external fun componentClean(
        output: ByteArray,
        ink: ByteArray,
        labels: IntArray,
        stats: IntArray,
        nearby: ByteArray,
        amount: Float,
        maximumArea: Int,
    )

    external fun faintClean(
        output: ByteArray,
        rules: ByteArray,
        foreground: ByteArray?,
        width: Int,
        height: Int,
        params: FloatArray,
    )

    external fun workbook(
        raw: ByteArray,
        ink: ByteArray,
        soft: ByteArray,
        near: ByteArray,
        rules: ByteArray,
        foreground: ByteArray?,
        params: FloatArray,
    ): ByteArray
}
