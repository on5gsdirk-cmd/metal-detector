package be.on5gs.pidetector.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import be.on5gs.pidetector.core.AudioSettings
import be.on5gs.pidetector.core.SoundControl
import be.on5gs.pidetector.core.SoundGenerator

/** Speelt continu gegenereerd geluid af via AudioTrack (streaming, lage latentie). */
class AudioEngine {
    private val sampleRate = 44100
    private val generator = SoundGenerator(sampleRate)
    @Volatile private var running = false
    private var thread: Thread? = null

    var settings: AudioSettings
        get() = generator.settings
        set(v) { generator.settings = v }

    var control: SoundControl
        get() = generator.control
        set(v) { generator.control = v }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "pi-audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val chunk = ShortArray(512)
        try {
            track.play()
            while (running) {
                generator.render(chunk)
                track.write(chunk, 0, chunk.size)
            }
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
