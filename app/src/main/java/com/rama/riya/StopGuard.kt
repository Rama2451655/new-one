package com.rama.riya

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/** The words that interrupt Riya while she is talking. */
object StopWords {
    const val GRAMMAR = "[\"stop\", \"wait\", \"enough\", \"cancel\", \"quiet\", \"silence\", \"[unk]\"]"
    private val hit = Regex("\\b(stop|wait|enough|cancel|quiet|silence)\\b")

    fun isStop(json: String): Boolean {
        val o = try { JSONObject(json) } catch (e: Exception) { return false }
        val t = o.optString("text").ifEmpty { o.optString("partial") }
        return hit.containsMatchIn(t.lowercase())
    }
}

/**
 * Listens for "stop" ONLY while Riya is speaking (works with Google hearing too).
 * Uses a tiny offline recogniser that knows just a few words, so it reacts instantly and never sends audio anywhere.
 */
class StopGuard(
    private val ctx: Context,
    private val modelName: String,
    private val onStop: () -> Unit
) {
    companion object {
        @Volatile private var model: Model? = null

        private fun copy(ctx: Context, path: String, dest: File) {
            val names = ctx.assets.list(path)
            if (names == null || names.isEmpty()) {
                dest.parentFile?.mkdirs()
                ctx.assets.open(path).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            } else {
                dest.mkdirs()
                for (n in names) copy(ctx, "$path/$n", File(dest, n))
            }
        }

        @Synchronized
        private fun loadModel(ctx: Context, name: String): Model? {
            model?.let { return it }
            return try {
                val dest = File(ctx.filesDir, "vosk/$name")
                val ok = File(dest, ".copied")
                if (!ok.exists()) {
                    val src = "models/$name"
                    val l = ctx.assets.list(src)
                    if (l == null || l.isEmpty()) return null
                    dest.deleteRecursively()
                    copy(ctx, src, dest)
                    ok.writeText("1")
                }
                Model(dest.absolutePath).also { model = it }
            } catch (e: Throwable) { null }
        }
    }

    @Volatile private var gen = 0

    fun begin() {
        val my = ++gen
        val t = Thread { run(my) }
        t.isDaemon = true
        t.start()
    }

    fun end() { gen++ }

    private fun run(my: Int) {
        var mic: AudioRecord? = null
        var rec: Recognizer? = null
        try {
            val m = loadModel(ctx, modelName) ?: return
            if (gen != my) return
            rec = Recognizer(m, 16000.0f, StopWords.GRAMMAR)
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16000 * 2)
            )
            mic = a
            if (a.state != AudioRecord.STATE_INITIALIZED) return
            a.startRecording()
            val buf = ShortArray(800)
            while (gen == my) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(20); continue }
                val full = rec.acceptWaveForm(buf, n)
                if (StopWords.isStop(if (full) rec.result else rec.partialResult)) {
                    if (gen == my) { gen++; onStop() }
                    break
                }
            }
        } catch (e: Throwable) {
            // stop-word listening is optional
        } finally {
            try { mic?.stop() } catch (e: Throwable) { }
            try { mic?.release() } catch (e: Throwable) { }
            try { rec?.close() } catch (e: Throwable) { }
        }
    }
}
