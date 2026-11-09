package cn.paperforge.app.imaging

import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Source evidence, not semantic ground truth. Labels may overlap; uncertainty protects ink. */
internal object WorkbookContent {
    const val VERSION = "workbook-content-5"
    const val PRINT = 1
    const val FAINT_OR_HANDWRITING = 2
    const val HORIZONTAL = 4
    const val VERTICAL = 8
    const val FIGURE = 16
    const val BACKGROUND = 32
    const val SHOW_THROUGH = 64
    const val UNCERTAIN = 128

    enum class Policy {
        CONSERVATIVE,
        STANDARD,
        STRONG,
    }

    data class Decision(val requested: Policy, val effective: Policy, val ambiguousPixels: Int) {
        fun json() =
            JSONObject()
                .put("requestedPolicy", requested.name)
                .put("effectivePolicy", effective.name)
                .put("ambiguousPixels", ambiguousPixels)
                .put("downgraded", requested != effective)
                .put(
                    "reason",
                    if (requested != effective)
                        "unclassified faint source components; keep conservative contrast"
                    else "source evidence guard",
                )
    }

    private val decision = ThreadLocal<Decision>()

    fun lastDecision() = decision.get()?.json()

    data class Evidence(
        val gray: ByteArray,
        val labels: ByteArray,
        val bleed: ByteArray,
        val decision: Decision,
    ) {
        fun summary(): JSONObject =
            JSONObject()
                .put("version", VERSION)
                .put("pixels", gray.size)
                .put("labelsOverlap", true)
                .put("semanticModel", false)
                .put("decision", decision.json())
                .put(
                    "suppressionMapMeaning",
                    "attenuation weight, not calibrated semantic probability",
                )
                .also { json ->
                    for ((name, bit) in
                        listOf(
                            "frontPrintedTextCandidate" to PRINT,
                            "handwritingOrFaintInk" to FAINT_OR_HANDWRITING,
                            "horizontalRules" to HORIZONTAL,
                            "verticalRulesOrTables" to VERTICAL,
                            "figures" to FIGURE,
                            "background" to BACKGROUND,
                            "suspectedShowThrough" to SHOW_THROUGH,
                            "uncertain" to UNCERTAIN,
                        )) json.put(name, labels.count { (it.toInt() and bit) != 0 })
                }
    }

    fun enabled() = ScanConfig.int("workbookIntelligence", 1) != 0

    fun protect(
        rgb: Mat,
        raw: ByteArray,
        paper: ByteArray,
        ink: ByteArray,
        soft: ByteArray,
        rules: ByteArray,
        candidate: ByteArray,
        amount: Float,
        strong: Boolean = false,
        preserveAll: Boolean = false,
        learned: ByteArray? = null,
    ): Evidence {
        val policy = ScanConfig.int("workbookPolicy", if (strong) 1 else 0).coerceIn(0, 2)
        val support = InkComponents.protection(ink, soft, rules, rgb.cols(), rgb.rows())
        var ambiguous = 0
        val sharpFraction = ScanConfig.float("workbookSharpFraction", .22f)
        for (i in ink.indices) {
            val v = ink[i].toInt() and 255
            if (
                support[i] == 0.toByte() &&
                    (rules[i].toInt() and 255) < 4 &&
                    v in 3..64 &&
                    v - (soft[i].toInt() and 255) < maxOf(4f, v * sharpFraction)
            )
                ambiguous++
        }
        // No calibrated semantic evidence identifies these components as reverse-side text.
        // Prefer the verified conservative contrast floor to an unsupported aggressive cleanup.
        val effective =
            if (
                policy > 0 &&
                    ambiguous > 4 &&
                    ScanConfig.int("workbookAllowAmbiguousStrong", 0) == 0
            )
                0
            else policy
        val selected = Decision(Policy.entries[policy], Policy.entries[effective], ambiguous)
        decision.set(selected)
        val colors = ByteArray(raw.size * 3).also { rgb.get(0, 0, it) }
        val result =
            PaperPixelEngine.protectedWorkbook(
                colors,
                ink,
                soft,
                rules,
                candidate,
                support,
                WorkbookBackground.mask(
                    ink,
                    soft,
                    rules,
                    support,
                    learned,
                    rgb.cols(),
                    rgb.rows(),
                    strong,
                    amount,
                ),
                rgb.cols(),
                rgb.rows(),
                floatArrayOf(
                    effective.toFloat(),
                    ScanConfig.float("workbookUncertainRetention", .95f).coerceIn(.8f, 1f),
                    ScanConfig.float("workbookMaximumFaintGain", 1.12f).coerceIn(1f, 1.3f),
                    ScanConfig.float("workbookColorThreshold", 36f).coerceIn(24f, 80f),
                    ScanConfig.float("workbookSuspectCeiling", 64f).coerceIn(20f, 80f),
                    ScanConfig.float("workbookSharpFraction", .22f).coerceIn(.1f, .4f),
                    ScanConfig.float("workbookSmoothRetentionConservative", .60f).coerceIn(.5f, 1f),
                    ScanConfig.float("workbookSmoothRetentionStandard", .4f).coerceIn(.3f, 1f),
                    ScanConfig.float("workbookSmoothRetentionStrong", .25f).coerceIn(.2f, 1f),
                    ScanConfig.float("workbookOutsideBandRetention", .65f).coerceIn(.5f, 1f),
                    if (preserveAll) 1f else 0f,
                    ScanConfig.float("workbookSupportedInkGain", 1.15f).coerceIn(1f, 2.6f),
                    ScanConfig.int("workbookPreserveInkEdges", 0).toFloat(),
                ),
            )
        check(result.size == 3 && result.all { it.size == raw.size })
        WorkbookStrokePolish.apply(result[0], result[1], rgb.cols(), rgb.rows())
        // Strength controls observed ink contrast, not how much yellow/gray paper leaks back in.
        // Fade illumination normalization in near zero; zero-strength still returns the raw crop.
        val paperAmount =
            if (ScanConfig.int("workbookPaperToneBlend", 1) != 0) (amount / .35f).coerceIn(0f, 1f)
            else 0f
        for (i in raw.indices) {
            val sourceTone =
                (raw[i].toInt() and 255) * (1 - paperAmount) +
                    (255 - (ink[i].toInt() and 255)) * paperAmount
            result[0][i] =
                (if (!preserveAll && (result[1][i].toInt() and BACKGROUND) != 0) 255f
                    else sourceTone * (1 - amount) + (result[0][i].toInt() and 255) * amount)
                    .toInt()
                    .coerceIn(0, 255)
                    .toByte()
        }
        return Evidence(result[0], result[1], result[2], selected).also {
            if (ScanConfig.int("workbookDiagnostics", 0) != 0)
                android.util.Log.d("WorkbookContent", it.summary().toString())
        }
    }

    fun write(rgb: Mat, paper: ByteArray, evidence: Evidence, destination: Mat, amount: Float) {
        if (EdgeInkDiagnostics.enabled()) {
            Mat(rgb.rows(), rgb.cols(), CvType.CV_8UC1)
                .also { it.put(0, 0, evidence.labels) }
                .let { labels ->
                    EdgeInkDiagnostics.dump("06a-labels", labels)
                    labels.release()
                }
        }
        val colors = ByteArray(evidence.gray.size * 3).also { rgb.get(0, 0, it) }
        val output =
            PaperPixelEngine.workbookColor(
                colors,
                paper,
                evidence.gray,
                evidence.labels,
                amount,
                ScanConfig.float("workbookColorThreshold", 36f),
            )
        destination.create(rgb.rows(), rgb.cols(), CvType.CV_8UC3)
        destination.put(0, 0, output)
    }

    /** Also guards the existing learned retouch output against missing/hallucinated source ink. */
    fun guard(rgb: Mat, candidate: Mat, destination: Mat, amount: Float = 1f): Evidence {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val gray = mat()
        val field = mat()
        val ink = mat()
        val soft = mat()
        val rules = mat()
        val tone = mat()
        val exterior = mat()
        try {
            val prepared =
                WorkbookPaperBoundary.prepare(rgb, paperTone = true, exteriorMask = exterior)
                    ?.also(owned::add)
            val input = prepared ?: rgb
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGB2GRAY)
            val window =
                (minOf(rgb.cols(), rgb.rows()) * ScanConfig.float("workbookPaperWindow", .035f))
                    .toInt()
                    .coerceIn(15, 151) or 1
            if (ScanConfig.int("paperFieldSmooth", 0) != 0) PaperField.estimateBytes(gray, field)
            else EnhancePipeline.paperField(gray, field, window)
            val raw = ByteArray((gray.total()).toInt()).also { gray.get(0, 0, it) }
            val paper = ByteArray(raw.size).also { field.get(0, 0, it) }
            val residual =
                PaperPixelEngine.normalize(raw, paper, ScanConfig.int("workbookPaperFloor", 20))
            ink.create(gray.rows(), gray.cols(), CvType.CV_8UC1)
            ink.put(0, 0, residual)
            Imgproc.GaussianBlur(
                ink,
                soft,
                Size(0.0, 0.0),
                (minOf(rgb.cols(), rgb.rows()) / 1000.0).coerceIn(.8, 2.4),
            )
            EnhancePipeline.rulingEvidence(ink, rules)
            if (candidate.channels() == 1) candidate.copyTo(tone)
            else
                Imgproc.cvtColor(
                    candidate,
                    tone,
                    if (candidate.channels() == 4) Imgproc.COLOR_RGBA2GRAY
                    else Imgproc.COLOR_RGB2GRAY,
                )
            val evidence =
                protect(
                    input,
                    raw,
                    paper,
                    residual,
                    ByteArray(raw.size).also { soft.get(0, 0, it) },
                    ByteArray(raw.size).also { rules.get(0, 0, it) },
                    ByteArray(raw.size).also { tone.get(0, 0, it) },
                    amount,
                )
            write(input, paper, evidence, destination, amount)
            if (!exterior.empty()) {
                if (PaperTone.enabled()) PaperTone.retone(destination, exterior)
                else destination.setTo(Scalar.all(255.0), exterior)
            }
            return evidence
        } finally {
            owned.forEach(Mat::release)
        }
    }
}

/** Bounded edge-aware filtering; no erosion, redraw, or crossings of 32-level ink thresholds. */
internal object WorkbookStrokePolish {
    const val VERSION = "stroke-polish-1"

    fun apply(gray: ByteArray, labels: ByteArray, width: Int, height: Int) {
        val cap = ScanConfig.int("workbookStrokePolish", 3).coerceIn(0, 5)
        if (cap > 0) PaperPixelEngine.workbookPolish(gray, labels, width, height, cap)
    }
}
