package cn.paperforge.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.reader.OfflineSpeech
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineSpeechFlowTest {
    @Test
    fun actualOfflineEngineSegmentProgressPauseResumeStopAndPcmOutput() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val proof = File(app.cacheDir, "offline-speech-proof").apply { mkdirs() }
        val originalVoice = UserSettings.string("speechVoice", "")
        val originalSpeed = UserSettings.number("speechSpeed", 1f)
        fun main(action: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        lateinit var speech: OfflineSpeech
        main { speech = OfflineSpeech(app) }
        var reference: TextToSpeech? = null
        try {
            val ready = withTimeout(15000) { speech.state.first { it.ready } }
            assertEquals("com.reecedunn.espeak", ready.engineId)
            assertTrue(ready.voices.isNotEmpty())
            val english = ready.voices.first { ready.voiceLabels[it].orEmpty().contains("英语") }
            val text =
                "First sentence includes code zero zero one two three. Second sentence includes total two hundred point five zero."
            val secondStart = text.indexOf("Second")
            main {
                speech.voice(english)
                speech.speed(1.6f)
                speech.play(text)
            }
            val first =
                withTimeout(15000) { speech.state.first { it.playing && it.end > it.offset } }
            assertEquals(0, first.offset)
            assertEquals(secondStart, first.end)
            assertFalse(first.preciseRange)
            val second =
                withTimeout(20000) { speech.state.first { it.playing && it.offset >= secondStart } }
            main { speech.pause() }
            val paused = speech.state.value
            assertFalse(paused.playing)
            assertEquals(second.offset, paused.offset)
            main { speech.play(text, paused.offset) }
            val resumed =
                withTimeout(15000) { speech.state.first { it.playing && it.end > it.offset } }
            assertEquals(paused.offset, resumed.offset)
            main { speech.close() }
            delay(250)
            assertFalse(speech.state.value.playing)
            val initialized = CompletableDeferred<Int>()
            main { reference = TextToSpeech(app) { initialized.complete(it) } }
            assertEquals(TextToSpeech.SUCCESS, withTimeout(15000) { initialized.await() })
            val tts = requireNotNull(reference)
            val audio = JSONArray()
            for ((language, value) in
                listOf(
                    "en" to "PaperForge offline reading. Code zero zero one two three.",
                    "cmn" to "纸匠离线朗读，一二三。",
                )) {
                val voice =
                    tts.voices.first {
                        !it.isNetworkConnectionRequired &&
                            (it.locale.language == language ||
                                (language == "cmn" && it.locale.language == "zh"))
                    }
                val done = CompletableDeferred<Unit>()
                val bytes = AtomicInteger()
                val file = File(proof, "$language.wav")
                main {
                    tts.voice = voice
                    tts.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}

                            override fun onDone(id: String?) {
                                done.complete(Unit)
                            }

                            @Deprecated("Legacy")
                            override fun onError(id: String?) {
                                done.completeExceptionally(
                                    IllegalStateException("Audio synthesis failed: $id")
                                )
                            }

                            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                                bytes.addAndGet(audio?.size ?: 0)
                            }
                        }
                    )
                    assertEquals(
                        TextToSpeech.SUCCESS,
                        tts.synthesizeToFile(value, Bundle(), file, "proof-$language"),
                    )
                }
                withTimeout(20000) { done.await() }
                assertTrue(file.length() > 1000)
                val header = file.inputStream().use { it.readNBytes(12) }
                assertEquals("RIFF", String(header, 0, 4))
                assertEquals("WAVE", String(header, 8, 4))
                audio.put(
                    JSONObject()
                        .put("language", language)
                        .put("voice", voice.name)
                        .put("file", file.name)
                        .put("bytes", file.length())
                        .put("audioCallbackBytes", bytes.get())
                )
            }
            File(proof, "proof.json")
                .writeText(
                    JSONObject()
                        .put("engine", ready.engineId)
                        .put("installedOfflineVoices", ready.voices.size)
                        .put("selectedEnglish", english)
                        .put("firstRange", JSONArray(listOf(first.offset, first.end)))
                        .put("secondRange", JSONArray(listOf(second.offset, second.end)))
                        .put("preciseRange", first.preciseRange)
                        .put("resumeOffset", resumed.offset)
                        .put("pausedOffset", paused.offset)
                        .put("closedStopsPlayback", !speech.state.value.playing)
                        .put("audio", audio)
                        .toString(2)
                )
        } finally {
            main {
                speech.close()
                reference?.shutdown()
            }
            UserSettings.set("speechVoice", originalVoice)
            UserSettings.set("speechSpeed", originalSpeed)
            check(
                UserSettings.prefs
                    .edit()
                    .putString("speechVoice", originalVoice)
                    .putFloat("speechSpeed", originalSpeed)
                    .commit()
            )
        }
    }
}
