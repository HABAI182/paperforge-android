package cn.paperforge.app

import android.content.SharedPreferences

/** Typed names and defaults shared by code, JSON normalization and profiles. */
sealed class SettingKey<T : Any>(val name: String, val default: T) {
    abstract fun read(prefs: SharedPreferences?): T

    abstract fun write(editor: SharedPreferences.Editor, value: T): SharedPreferences.Editor

    class Bool(name: String, value: Boolean) : SettingKey<Boolean>(name, value) {
        override fun read(prefs: SharedPreferences?) = prefs?.getBoolean(name, default) ?: default

        override fun write(editor: SharedPreferences.Editor, value: Boolean) =
            editor.putBoolean(name, value)
    }

    class Number(name: String, value: Float, val range: ClosedFloatingPointRange<Float>) :
        SettingKey<Float>(name, value) {
        override fun read(prefs: SharedPreferences?) = prefs?.getFloat(name, default) ?: default

        override fun write(editor: SharedPreferences.Editor, value: Float) =
            editor.putFloat(name, value)
    }

    class Text(name: String, value: String, val choices: Set<String> = emptySet()) :
        SettingKey<String>(name, value) {
        override fun read(prefs: SharedPreferences?) = prefs?.getString(name, default) ?: default

        override fun write(editor: SharedPreferences.Editor, value: String) =
            editor.putString(name, value)
    }
}

object SettingKeys {
    val Detector = SettingKey.Text("detector", "NEURAL", setOf("NEURAL", "HYBRID", "STANDARD"))
    val OutlineWeight = SettingKey.Number("outlineWeight", 3f, 1f..8f)
    val OutlineSwitchFrames = SettingKey.Number("outlineSwitchFrames", 2f, 1f..6f)
    val DewarpStrength = SettingKey.Number("dewarpStrength", 1f, 0f..1f)
    val AutoShutter = SettingKey.Bool("autoShutter", true)
    val TurnRearm = SettingKey.Bool("turnRearm", true)
    val TurnThreshold = SettingKey.Number("turnThreshold", .06f, .025f..0.2f)
    val CaptureTimer = SettingKey.Number("captureTimer", 0f, 0f..10f)
    val ScanAspect = SettingKey.Number("scanAspect", 0f, 0f..2.5f)
    val ReviewAfterCapture = SettingKey.Bool("reviewAfterCapture", false)
    val LockCrop = SettingKey.Bool("lockCrop", true)
    val ShowOutline = SettingKey.Bool("showOutline", true)
    val StrictQuality = SettingKey.Bool("strictQuality", false)
    val CaptureArea = SettingKey.Number("captureArea", .08f, 0.05f..0.4f)
    val CaptureConfidence = SettingKey.Number("captureConfidence", .60f, 0.45f..0.95f)
    val StableMs = SettingKey.Number("stableMs", 600f, 300f..2000f)
    val CooldownMs = SettingKey.Number("cooldownMs", 1500f, 800f..5000f)
    val AnalysisMs = SettingKey.Number("analysisMs", 160f, 100f..500f)
    val CameraJpeg = SettingKey.Number("cameraJpeg", 97f, 75f..100f)
    val Filter =
        SettingKey.Text(
            "filter",
            "AUTO",
            setOf("ORIGINAL", "AUTO", "TEXT", "CLEAN", "GRAY", "BW", "COLOR", "WHITEBOARD"),
        )
    val EnhanceStrength = SettingKey.Number("enhanceStrength", 1f, 0f..1f)
    val Brightness = SettingKey.Number("brightness", 0f, -0.5f..0.5f)
    val Contrast = SettingKey.Number("contrast", 1f, 0.5f..1.8f)
    val OcrLanguage = SettingKey.Text("ocrLanguage", "CHINESE", setOf("CHINESE", "LATIN"))
    val OcrBackend = SettingKey.Text("ocrBackend", "MLKIT", setOf("MLKIT", "PADDLE"))
    val OcrPackId = SettingKey.Text("ocrPackId", "")
    val OcrMode = SettingKey.Text("ocrMode", "BALANCED", setOf("FAST", "BALANCED", "DETAILED"))
    val OcrVoting = SettingKey.Bool("ocrVoting", true)
    val OcrOrder =
        SettingKey.Text(
            "ocrOrder",
            "AUTO",
            setOf("AUTO", "ROWS", "COLUMNS_LTR", "COLUMNS_RTL", "VERTICAL_RTL", "VERTICAL_LTR"),
        )
    val Searchable = SettingKey.Bool("searchable", false)
    val Paper = SettingKey.Text("paper", "Auto", setOf("Auto", "A4", "A5", "Letter", "ID"))
    val Resolution =
        SettingKey.Text("resolution", "STANDARD", setOf("DRAFT", "STANDARD", "HIGH", "CUSTOM"))
    val CustomDpi = SettingKey.Number("customDpi", 300f, 72f..600f)
    val Quality = SettingKey.Number("quality", 90f, 40f..100f)
    val Theme = SettingKey.Text("theme", "SYSTEM", setOf("SYSTEM", "LIGHT", "DARK", "AMOLED"))
    val Accent = SettingKey.Text("accent", "GREEN", setOf("GREEN", "BLUE", "PURPLE", "ORANGE"))
    val DynamicColor = SettingKey.Bool("dynamicColor", false)
    val ReduceMotion = SettingKey.Bool("reduceMotion", false)
    val Haptics = SettingKey.Bool("haptics", true)
    val DocumentSidebar = SettingKey.Bool("documentSidebar", true)
    val FontScale = SettingKey.Number("fontScale", 1f, 0.85f..1.3f)
    val CompactRows = SettingKey.Bool("compactRows", false)
    val DocumentLayout = SettingKey.Text("documentLayout", "LIST", setOf("LIST", "COVERS"))
    val GridColumns = SettingKey.Text("gridColumns", "AUTO", setOf("AUTO", "2", "3", "4"))
    val HomeCollection =
        SettingKey.Text(
            "homeCollection",
            "RECENT",
            setOf("RECENT", "FAVORITES", "INBOX", "NO_OCR", "NEEDS_EXPORT"),
        )
    val EdgeTurn = SettingKey.Bool("edgeTurn", true)
    val ReaderSelectionOrder =
        SettingKey.Text("readerSelectionOrder", "0", setOf("0", "1", "2", "3", "4", "5"))
    val SpeechVoice = SettingKey.Text("speechVoice", "")
    val SpeechSpeed = SettingKey.Number("speechSpeed", 1f, .5f..2f)
    val RecentCount = SettingKey.Number("recentCount", 6f, 4f..20f)
    val HomeTools = SettingKey.Text("homeTools", "images,pdf,ocr,merge,compress,tools")
    val PinnedTools = SettingKey.Text("pinnedTools", "")
    val ExportFormat = SettingKey.Text("exportFormat", "PDF", setOf("PDF", "JPG", "PNG"))
    val ExportNaming = SettingKey.Text("exportNaming", "{title}")
    val ExportDestination = SettingKey.Text("exportDestination", "")
    val WorkflowRecipes = SettingKey.Text("workflowRecipes", "[]")
    val AutoExportRules = SettingKey.Text("autoExportRules", "[]")
    val TaskMemoryMB = SettingKey.Number("taskMemoryMB", 192f, 32f..384f)
    val all: List<SettingKey<*>> =
        listOf(
            Detector,
            OutlineWeight,
            OutlineSwitchFrames,
            DewarpStrength,
            AutoShutter,
            TurnRearm,
            TurnThreshold,
            CaptureTimer,
            ScanAspect,
            ReviewAfterCapture,
            LockCrop,
            ShowOutline,
            StrictQuality,
            CaptureArea,
            CaptureConfidence,
            StableMs,
            CooldownMs,
            AnalysisMs,
            CameraJpeg,
            Filter,
            EnhanceStrength,
            Brightness,
            Contrast,
            OcrLanguage,
            OcrBackend,
            OcrPackId,
            OcrMode,
            OcrVoting,
            OcrOrder,
            Searchable,
            Paper,
            Resolution,
            CustomDpi,
            Quality,
            Theme,
            Accent,
            DynamicColor,
            ReduceMotion,
            Haptics,
            DocumentSidebar,
            FontScale,
            CompactRows,
            DocumentLayout,
            GridColumns,
            HomeCollection,
            EdgeTurn,
            ReaderSelectionOrder,
            SpeechVoice,
            SpeechSpeed,
            RecentCount,
            HomeTools,
            PinnedTools,
            ExportFormat,
            ExportNaming,
            ExportDestination,
            WorkflowRecipes,
            AutoExportRules,
            TaskMemoryMB,
        )
    val defaults: Map<String, Any> = all.associate { it.name to it.default }
}
