package cn.paperforge.app.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cn.paperforge.app.UserSettings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/** All state changes run on the main thread; engine callbacks are marshalled before publication. */
class OfflineSpeech(context: Context) : AutoCloseable {
    data class State(
        val ready: Boolean = false,
        val playing: Boolean = false,
        val offset: Int = 0,
        val end: Int = 0,
        val message: String = "正在连接系统朗读引擎",
        val voices: List<String> = emptyList(),
        val voiceLabels: Map<String, String> = emptyMap(),
        val selectedVoice: String = "",
        val engineId: String = "",
        val preciseRange: Boolean = false,
    )

    val state = MutableStateFlow(State())
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var text = ""
    private var generation = 0
    private var currentId = ""
    private var chunkStart = 0
    private var chunkEnd = 0
    private var closed = false

    private fun callback(id: String?, action: () -> Unit) {
        main.post { if (!closed && id == currentId) action() }
    }

    init {
        engine =
            TextToSpeech(context.applicationContext) { status ->
                main.post {
                    if (!closed) {
                        val tts = engine
                        val voices =
                            tts?.voices
                                .orEmpty()
                                .filter {
                                    !it.isNetworkConnectionRequired &&
                                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in
                                            it.features.orEmpty()
                                }
                                .sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                        if (status != TextToSpeech.SUCCESS || voices.isEmpty())
                            state.value = State(message = "未找到已安装的离线声音，请到系统朗读设置安装。")
                        else {
                            val saved = UserSettings.string("speechVoice", "")
                            val chinese = UserSettings.string("ocrLanguage", "CHINESE") != "LATIN"
                            val voice =
                                voices.firstOrNull { it.name == saved }
                                    ?: voices.firstOrNull {
                                        if (chinese) it.locale.language in setOf("zh", "cmn")
                                        else it.locale.language == "en"
                                    }
                                    ?: voices.first()
                            tts?.voice = voice
                            tts?.setSpeechRate(
                                UserSettings.number("speechSpeed", 1f).coerceIn(.5f, 2f)
                            )
                            state.value =
                                State(
                                    ready = true,
                                    message = "使用系统离线声音",
                                    voices = voices.map { it.name },
                                    voiceLabels =
                                        voices.associate {
                                            it.name to
                                                "${it.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)} · ${it.name}"
                                        },
                                    selectedVoice = voice.name,
                                    engineId = tts?.defaultEngine.orEmpty(),
                                )
                        }
                    }
                }
            }
        engine?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) =
                    callback(id) {
                        state.value =
                            state.value.copy(
                                playing = true,
                                offset = chunkStart,
                                end = chunkEnd,
                                preciseRange = false,
                                message = "朗读中 · 按句段跟读",
                            )
                    }

                override fun onDone(id: String?) =
                    callback(id) {
                        if (state.value.playing) {
                            if (chunkEnd < text.length) speakChunk(chunkEnd)
                            else
                                state.value =
                                    state.value.copy(
                                        playing = false,
                                        offset = text.length,
                                        end = text.length,
                                        message = "朗读完成",
                                    )
                        }
                    }

                @Deprecated("Legacy callback")
                override fun onError(id: String?) =
                    callback(id) {
                        state.value = state.value.copy(playing = false, message = "朗读失败，请检查离线声音")
                    }

                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) =
                    callback(id) {
                        state.value =
                            state.value.copy(
                                offset = chunkStart + start,
                                end = chunkStart + end,
                                preciseRange = true,
                                message = "朗读中 · 逐词跟读",
                            )
                    }

                override fun onStop(id: String?, interrupted: Boolean) =
                    callback(id) {
                        state.value = state.value.copy(playing = false, message = "朗读已停止")
                    }
            }
        )
    }

    fun play(value: String, start: Int = 0) {
        if (closed || !state.value.ready || value.isBlank()) return
        text = value
        generation++
        val offset = start.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        state.value =
            state.value.copy(
                playing = true,
                offset = offset,
                end = offset,
                preciseRange = false,
                message = "正在开始朗读",
            )
        speakChunk(offset)
    }

    private fun speakChunk(start: Int) {
        chunkStart = start
        // Punctuation/newline boundaries are independent of the phone's UI locale.
        // A decimal point within 200.50 is not a boundary.
        val terminal =
            (start until minOf(text.length, start + 220)).firstOrNull { i ->
                text[i] in "。！？!?\n" ||
                    (text[i] == '.' && (i + 1 == text.length || text[i + 1].isWhitespace()))
            }
        var boundary = terminal?.plus(1) ?: text.length
        while (
            boundary < text.length &&
                (text[boundary].isWhitespace() || text[boundary] in "\"'”’）)】")
        ) boundary++
        chunkEnd = minOf(boundary, start + 220, text.length)
        if (chunkEnd < boundary) {
            val space = text.lastIndexOf(' ', chunkEnd - 1).takeIf { it > start + 40 }
            if (space != null) chunkEnd = space + 1
            if (chunkEnd < text.length && text[chunkEnd].isLowSurrogate()) chunkEnd--
        }
        currentId = "$generation:$start"
        val result =
            engine?.speak(
                text.substring(start, chunkEnd),
                TextToSpeech.QUEUE_FLUSH,
                null,
                currentId,
            )
        if (result == TextToSpeech.ERROR)
            state.value = state.value.copy(playing = false, message = "系统未能开始朗读")
    }

    fun pause() {
        generation++
        currentId = ""
        engine?.stop()
        state.value =
            state.value.copy(
                playing = false,
                message = if (state.value.preciseRange) "已暂停，继续将从当前词开始" else "已暂停，继续将从当前句段开始",
            )
    }

    fun speed(rate: Float) {
        val value = rate.coerceIn(.5f, 2f)
        engine?.setSpeechRate(value)
        UserSettings.set("speechSpeed", value)
    }

    fun voice(name: String) {
        engine
            ?.voices
            ?.firstOrNull {
                it.name == name &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            ?.let {
                engine?.voice = it
                UserSettings.set("speechVoice", name)
                state.value = state.value.copy(selectedVoice = name)
                if (state.value.playing) play(text, state.value.offset)
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        pause()
        engine?.shutdown()
        engine = null
    }
}
