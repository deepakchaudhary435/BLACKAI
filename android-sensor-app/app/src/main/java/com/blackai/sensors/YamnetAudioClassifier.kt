package com.blackai.sensors

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device YAMNet inference helper.
 *
 * Input: exactly 15,600 mono float samples at 16 kHz, normalized to [-1, 1].
 * This class does not open the microphone, save audio, or transmit audio.
 * A caller must obtain audio only during a clearly user-started, visible session.
 */
class YamnetAudioClassifier(context: Context) : Closeable {
    data class Prediction(val label: String, val score: Float)

    private val interpreter: Interpreter
    private val labels: List<String>

    init {
        val modelBytes = context.assets.open("yamnet.tflite").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()
        interpreter = Interpreter(modelBuffer)
        labels = context.assets.open("yamnet_label_list.txt").bufferedReader().useLines { it.toList() }
        require(labels.size == 521) { "YAMNet label file must contain 521 labels" }
    }

    @Synchronized
    fun classify(samples16kMono: FloatArray): List<Prediction> {
        require(samples16kMono.size == 15600) { "Expected 0.975 seconds (15,600 samples) at 16 kHz" }
        val input = ByteBuffer.allocateDirect(15600 * 4).order(ByteOrder.nativeOrder())
        samples16kMono.forEach { sample -> input.putFloat(sample.coerceIn(-1f, 1f)) }
        input.rewind()
        val output = Array(1) { FloatArray(521) }
        interpreter.run(input, output)
        return output[0].indices
            .map { Prediction(labels[it], output[0][it]) }
            .sortedByDescending { it.score }
            .take(8)
    }

    override fun close() {
        interpreter.close()
    }
}
