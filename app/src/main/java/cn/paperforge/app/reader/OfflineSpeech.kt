package cn.paperforge.app.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow

class OfflineSpeech(context: Context) : AutoCloseable {
    data class State(
        val ready: Boolean = false,
        val playing: Boolean = false,
        val offset: Int = 0,
        val end: Int = 0,
        val message: String = "正在连接系统朗读引擎",
        val voices: List<String> = emptyList(),
    )

    val state = MutableStateFlow(State())
    private var engine: TextToSpeech? = null
    private var text = ""
    private var generation = 0
    private var currentId = ""
    private var chunkStart = 0
    private var chunkEnd = 0
    private var closed = false

    init {
        engine =
            TextToSpeech(context.applicationContext) { status ->
                Handler(Looper.getMainLooper()).post {
                    if (!closed) {
                        val tts = engine
                        val voices =
                            tts?.voices.orEmpty().filter {
                                !it.isNetworkConnectionRequired &&
                                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in
                                        it.features.orEmpty()
                            }
                        if (status != TextToSpeech.SUCCESS || voices.isEmpty())
                            state.value = State(message = "未找到已安装的离线声音，请到系统朗读设置安装。")
                        else {
                            tts?.voice =
                                voices.firstOrNull { it.locale.language == "zh" } ?: voices.first()
                            state.value =
                                State(
                                    ready = true,
                                    message = "使用系统离线声音",
                                    voices = voices.map { it.name },
                                )
                        }
                    }
                }
            }
        engine?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == currentId) state.value = state.value.copy(playing = true)
                }

                override fun onDone(id: String?) {
                    if (id == currentId && state.value.playing) {
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
                override fun onError(id: String?) {
                    if (id == currentId)
                        state.value = state.value.copy(playing = false, message = "朗读失败，请检查离线声音")
                }

                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                    if (id == currentId)
                        state.value =
                            state.value.copy(offset = chunkStart + start, end = chunkStart + end)
                }
            }
        )
    }

    fun play(value: String, start: Int = 0) {
        if (!state.value.ready || value.isBlank()) return
        text = value
        generation++
        state.value = state.value.copy(playing = true, message = "朗读中")
        speakChunk(start.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
    }

    private fun speakChunk(start: Int) {
        chunkStart = start
        chunkEnd = minOf(text.length, start + minOf(3500, TextToSpeech.getMaxSpeechInputLength()))
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
        state.value = state.value.copy(playing = false, message = "已暂停")
    }

    fun speed(rate: Float) {
        engine?.setSpeechRate(rate.coerceIn(.5f, 2f))
    }

    fun voice(name: String) {
        engine
            ?.voices
            ?.firstOrNull { it.name == name && !it.isNetworkConnectionRequired }
            ?.let { engine?.voice = it }
    }

    override fun close() {
        closed = true
        pause()
        engine?.shutdown()
        engine = null
    }
}
