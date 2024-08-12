package com.example.polandandroidarms.audiosink

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class ToFloatPcmAudioProcessor : BaseAudioProcessor() {
    @Throws(UnhandledAudioFormatException::class)
    public override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding: @PcmEncoding Int = inputAudioFormat.encoding
        if (!Util.isEncodingHighResolutionPcm(encoding)) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (encoding != C.ENCODING_PCM_FLOAT
        ) AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT
        )
        else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position

        val buffer: ByteBuffer
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_24BIT -> {
                buffer = replaceOutputBuffer((size / 3) * 4)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger =
                        (((inputBuffer[i].toInt() and 0xFF) shl 8)
                                or ((inputBuffer[i + 1].toInt() and 0xFF) shl 16)
                                or ((inputBuffer[i + 2].toInt() and 0xFF) shl 24))
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 3
                }
            }

            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer((size / 3) * 4)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger =
                        (((inputBuffer[i + 2].toInt() and 0xFF) shl 8)
                                or ((inputBuffer[i + 1].toInt() and 0xFF) shl 16)
                                or ((inputBuffer[i].toInt() and 0xFF) shl 24))
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 3
                }
            }

            C.ENCODING_PCM_32BIT -> {
                buffer = replaceOutputBuffer(size)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger =
                        ((inputBuffer[i].toInt() and 0xFF)
                                or ((inputBuffer[i + 1].toInt() and 0xFF) shl 8)
                                or ((inputBuffer[i + 2].toInt() and 0xFF) shl 16)
                                or ((inputBuffer[i + 3].toInt() and 0xFF) shl 24))
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 4
                }
            }

            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer(size)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger =
                        ((inputBuffer[i + 3].toInt() and 0xFF)
                                or ((inputBuffer[i + 2].toInt() and 0xFF) shl 8)
                                or ((inputBuffer[i + 1].toInt() and 0xFF) shl 16)
                                or ((inputBuffer[i].toInt() and 0xFF) shl 24))
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 4
                }
            }

            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_INVALID, Format.NO_VALUE ->         // Never happens.
                throw IllegalStateException()

            else ->
                throw IllegalStateException()
        }
        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
    }

    companion object {
        private val FLOAT_NAN_AS_INT = java.lang.Float.floatToIntBits(Float.NaN)
        private const val PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR = 1.0 / 0x7FFFFFFF

        /**
         * Converts the provided 32-bit integer to a 32-bit float value and writes it to `buffer`.
         *
         * @param pcm32BitInt The 32-bit integer value to convert to 32-bit float in [-1.0, 1.0].
         * @param buffer The output buffer.
         */
        private fun writePcm32BitFloat(pcm32BitInt: Int, buffer: ByteBuffer) {
            val pcm32BitFloat = (PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR * pcm32BitInt).toFloat()
            var floatBits = java.lang.Float.floatToIntBits(pcm32BitFloat)
            if (floatBits == FLOAT_NAN_AS_INT) {
                floatBits = java.lang.Float.floatToIntBits(0.0.toFloat())
            }
            buffer.putInt(floatBits)
        }
    }
}