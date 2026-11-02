package com.zibi.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records one voice message to a temp .m4a (AAC, 16 kHz mono — what whisper expects).
 * Tap to start ([start]), tap again to stop ([stop]) and get the bytes.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    @Synchronized
    fun start(): File {
        cancel()
        val out = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioEncodingBitRate(64_000)
        mr.setAudioSamplingRate(16_000)
        mr.setOutputFile(out.absolutePath)
        mr.prepare()
        mr.start()
        recorder = mr
        file = out
        return out
    }

    /** Stops and returns the recorded bytes, or null if nothing was recorded. */
    @Synchronized
    fun stop(): ByteArray? {
        val mr = recorder ?: return null
        recorder = null
        val out = file
        file = null
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // stop() throws when nothing was recorded; treat as empty
        }
        mr.release()
        val bytes = out?.takeIf { it.length() > 0 }?.readBytes()
        out?.delete()
        return bytes
    }

    @Synchronized
    fun cancel() {
        recorder?.let {
            try { it.stop() } catch (e: RuntimeException) { }
            it.release()
        }
        recorder = null
        file?.delete()
        file = null
    }
}
