package cn.paperforge.app.pdf

import android.content.Context
import java.io.InputStream
import java.security.MessageDigest

/** A font the editor offers without importing. */
data class LibraryFont(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val category: Category,
    val asset: String,
    /** What it covers, shown in the picker. */
    val coverage: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Fonts of one family share this key; bold/italic switch between them. */
    val family: String = id,
) {
    enum class Category(val label: String) {
        SANS("黑体 · 无衬线"),
        SERIF("宋体 · 衬线"),
        SCRIPT("楷体 · 手写"),
        MONO("等宽"),
        SYMBOL("数学与符号"),
    }

    val cjk
        get() = coverage.startsWith("简") || coverage.startsWith("繁")
}

/**
 * Built-in fonts for native text editing. Chinese fonts ship in `fonts/editor`; Latin office fonts come from the
 * full package's office runtime (`unpack/user/fonts`) and are listed only when present.
 */
object NativeFontLibrary {
    private const val OFFICE = "unpack/user/fonts"

    val all: List<LibraryFont> =
        listOf(
            LibraryFont("CJK", "思源黑体", "Noto Sans SC", LibraryFont.Category.SANS, "fonts/PaperForgeCJK-Regular.ttf", "简体全字库 + 英文", family = "sans-sc"),
            LibraryFont("builtin:sans-sc-bold", "思源黑体 粗", "Noto Sans SC Bold", LibraryFont.Category.SANS, "fonts/editor/NotoSansSC-Bold.ttf", "简体常用字（GB2312）+ 英文", bold = true, family = "sans-sc"),
            LibraryFont("builtin:serif-sc", "思源宋体", "Noto Serif SC", LibraryFont.Category.SERIF, "fonts/editor/NotoSerifSC-Regular.ttf", "简体常用字（GB2312）+ 英文", family = "serif-sc"),
            LibraryFont("builtin:serif-sc-bold", "思源宋体 粗", "Noto Serif SC Bold", LibraryFont.Category.SERIF, "fonts/editor/NotoSerifSC-Bold.ttf", "简体常用字（GB2312）+ 英文", bold = true, family = "serif-sc"),
            LibraryFont("builtin:fangsong", "朱雀仿宋", "Zhuque Fangsong", LibraryFont.Category.SERIF, "fonts/editor/ZhuqueFangsong-Regular.ttf", "简体常用字（GB2312），公文常用", family = "fangsong"),
            LibraryFont("builtin:kai", "PaperForge 楷体", "PaperForge Kai (LXGW WenKai)", LibraryFont.Category.SCRIPT, "fonts/editor/PaperForgeKai-Regular.ttf", "简体常用字（GB2312）+ 英文", family = "kai"),
            LibraryFont("builtin:sans-tc", "思源黑体 繁體", "Noto Sans TC", LibraryFont.Category.SANS, "fonts/editor/NotoSansTC-Regular.ttf", "繁體常用字（Big5 一級）+ 英文", family = "sans-tc"),
            LibraryFont("builtin:liberation-sans", "Liberation Sans", "Arial 同宽", LibraryFont.Category.SANS, "$OFFICE/LiberationSans-Regular.ttf", "英文与西文", family = "liberation-sans"),
            LibraryFont("builtin:liberation-sans-bold", "Liberation Sans 粗", "Arial Bold 同宽", LibraryFont.Category.SANS, "$OFFICE/LiberationSans-Bold.ttf", "英文与西文", bold = true, family = "liberation-sans"),
            LibraryFont("builtin:liberation-sans-italic", "Liberation Sans 斜", "Arial Italic 同宽", LibraryFont.Category.SANS, "$OFFICE/LiberationSans-Italic.ttf", "英文与西文", italic = true, family = "liberation-sans"),
            LibraryFont("builtin:liberation-sans-bolditalic", "Liberation Sans 粗斜", "Arial Bold Italic 同宽", LibraryFont.Category.SANS, "$OFFICE/LiberationSans-BoldItalic.ttf", "英文与西文", bold = true, italic = true, family = "liberation-sans"),
            LibraryFont("builtin:carlito", "Carlito", "Calibri 同宽", LibraryFont.Category.SANS, "$OFFICE/Carlito-Regular.ttf", "英文与西文", family = "carlito"),
            LibraryFont("builtin:carlito-bold", "Carlito 粗", "Calibri Bold 同宽", LibraryFont.Category.SANS, "$OFFICE/Carlito-Bold.ttf", "英文与西文", bold = true, family = "carlito"),
            LibraryFont("builtin:carlito-italic", "Carlito 斜", "Calibri Italic 同宽", LibraryFont.Category.SANS, "$OFFICE/Carlito-Italic.ttf", "英文与西文", italic = true, family = "carlito"),
            LibraryFont("builtin:liberation-serif", "Liberation Serif", "Times New Roman 同宽", LibraryFont.Category.SERIF, "$OFFICE/LiberationSerif-Regular.ttf", "英文与西文", family = "liberation-serif"),
            LibraryFont("builtin:liberation-serif-bold", "Liberation Serif 粗", "Times New Roman Bold 同宽", LibraryFont.Category.SERIF, "$OFFICE/LiberationSerif-Bold.ttf", "英文与西文", bold = true, family = "liberation-serif"),
            LibraryFont("builtin:liberation-serif-italic", "Liberation Serif 斜", "Times New Roman Italic 同宽", LibraryFont.Category.SERIF, "$OFFICE/LiberationSerif-Italic.ttf", "英文与西文", italic = true, family = "liberation-serif"),
            LibraryFont("builtin:liberation-serif-bolditalic", "Liberation Serif 粗斜", "Times New Roman Bold Italic 同宽", LibraryFont.Category.SERIF, "$OFFICE/LiberationSerif-BoldItalic.ttf", "英文与西文", bold = true, italic = true, family = "liberation-serif"),
            LibraryFont("builtin:caladea", "Caladea", "Cambria 同宽", LibraryFont.Category.SERIF, "$OFFICE/Caladea-Regular.ttf", "英文与西文", family = "caladea"),
            LibraryFont("builtin:caladea-bold", "Caladea 粗", "Cambria Bold 同宽", LibraryFont.Category.SERIF, "$OFFICE/Caladea-Bold.ttf", "英文与西文", bold = true, family = "caladea"),
            LibraryFont("builtin:gentium", "Gentium Book Basic", "Gentium Book Basic", LibraryFont.Category.SERIF, "$OFFICE/GenBkBasR.ttf", "英文与西文", family = "gentium"),
            LibraryFont("builtin:liberation-mono", "Liberation Mono", "Courier New 同宽", LibraryFont.Category.MONO, "$OFFICE/LiberationMono-Regular.ttf", "英文与代码", family = "liberation-mono"),
            LibraryFont("builtin:liberation-mono-bold", "Liberation Mono 粗", "Courier New Bold 同宽", LibraryFont.Category.MONO, "$OFFICE/LiberationMono-Bold.ttf", "英文与代码", bold = true, family = "liberation-mono"),
            LibraryFont("builtin:math", "Noto Sans Math", "数学符号", LibraryFont.Category.SYMBOL, "fonts/editor/NotoSansMath-Regular.ttf", "数学符号与希腊字母", family = "math"),
            LibraryFont("builtin:opensymbol", "OpenSymbol", "符号", LibraryFont.Category.SYMBOL, "$OFFICE/opens___.ttf", "符号与箭头", family = "opensymbol"),
        )

    /** The same family with [bold] / [italic] set, when the library has it. */
    fun variant(id: String, bold: Boolean, italic: Boolean): LibraryFont? {
        val f = find(id) ?: return null
        return all.firstOrNull { it.family == f.family && it.bold == bold && it.italic == italic }
    }

    fun isBuiltIn(id: String) = id.startsWith("builtin:")

    fun find(id: String) = all.firstOrNull { it.id == id }

    private val present = mutableMapOf<String, Boolean>()

    fun available(context: Context): List<LibraryFont> =
        all.filter { font ->
            synchronized(present) {
                present.getOrPut(font.asset) {
                    runCatching { context.assets.open(font.asset).close() }.isSuccess
                }
            }
        }

    /** The font file of a built-in id, or null for any other id. */
    fun open(context: Context, id: String): InputStream? =
        if (!isBuiltIn(id)) null
        else context.assets.open(requireNotNull(find(id)) { "字体不可用：$id" }.asset)

    /** Content version of the built-in fonts [ids] use, for staged jobs. */
    fun version(context: Context, ids: Collection<String>): String {
        val used = ids.mapNotNull { id -> find(id) }.sortedBy { it.id }
        if (used.isEmpty()) return "original-or-imported"
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        for (font in used) {
            hash.update(font.id.toByteArray())
            context.assets.open(font.asset).use { stream ->
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    /** A reasonable built-in stand-in when the original (subset) font lacks characters. */
    fun substitute(context: Context, item: NativePdfEditor.TextObject, text: String): LibraryFont? {
        val ready = available(context)
        return fallbackIds(item.font, item.serif, item.monospace, text).firstNotNullOfOrNull { id -> ready.firstOrNull { it.id == id } }
    }

    /**
     * Built-in fonts, best match first, that can stand in for a font named [fontName] (its style: kai, fangsong,
     * serif, bold, monospace) for [text]. Always ends with "CJK", the PaperForge font with Chinese and Latin.
     */
    fun fallbackIds(fontName: String, serif: Boolean, monospace: Boolean, text: String): List<String> {
        val han = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val name = fontName.lowercase()
        val bold = "bold" in name || "black" in name || "heavy" in name || "hei" in name && "bold" in name
        val kai = "kai" in name || "楷" in name
        val fang = "fang" in name || "仿宋" in name
        return when {
            kai && han -> listOf("builtin:kai")
            fang && han -> listOf("builtin:fangsong", "builtin:serif-sc")
            han && serif -> if (bold) listOf("builtin:serif-sc-bold") else listOf("builtin:serif-sc")
            han -> if (bold) listOf("builtin:sans-sc-bold", "CJK") else listOf("CJK")
            monospace -> listOf("builtin:liberation-mono")
            serif ->
                if (bold) listOf("builtin:liberation-serif-bold", "builtin:serif-sc-bold")
                else listOf("builtin:liberation-serif", "builtin:serif-sc")
            "calibri" in name -> listOf("builtin:carlito", "CJK")
            "cambria" in name -> listOf("builtin:caladea", "builtin:serif-sc")
            else ->
                if (bold) listOf("builtin:liberation-sans-bold", "builtin:sans-sc-bold")
                else listOf("builtin:liberation-sans", "CJK")
        }.plus("CJK").distinct()
    }
}
