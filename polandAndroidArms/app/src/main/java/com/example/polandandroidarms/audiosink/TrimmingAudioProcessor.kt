package com.example.polandandroidarms.audiosink

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import kotlin.math.min

@OptIn(UnstableApi::class)
internal class TrimmingAudioProcessor : BaseAudioProcessor() {
    private var trimStartFrames = 0
    private var trimEndFrames = 0
    private var reconfigurationPending = false

    private var pendingTrimStartBytes = 0
    private var endBuffer: ByteArray
    private var endBufferSize = 0

    /**
     * Returns the number of audio frames trimmed since the last call to [ ][.resetTrimmedFrameCount].
     */
    var trimmedFrameCount: Long = 0
        private set

    /** Creates a new audio processor for trimming samples from the start/end of data.  */
    init {
        endBuffer = Util.EMPTY_BYTE_ARRAY
    }

    /**
     * Sets the number of audio frames to trim from the start and end of audio passed to this
     * processor. After calling this method, call [.configure] to apply the new
     * trimming frame counts.
     *
     *
     * See [AudioSink.configure].
     *
     * @param trimStartFrames The number of audio frames to trim from the start of audio.
     * @param trimEndFrames The number of audio frames to trim from the end of audio.
     */
    fun setTrimFrameCount(trimStartFrames: Int, trimEndFrames: Int) {
        this.trimStartFrames = trimStartFrames
        this.trimEndFrames = trimEndFrames
    }

    /** Sets the trimmed frame count returned by [.getTrimmedFrameCount] to zero.  */
    fun resetTrimmedFrameCount() {
        trimmedFrameCount = 0
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        return (durationUs
                - Util.sampleCountToDurationUs( /* sampleCount= */
            (trimEndFrames + trimStartFrames).toLong(), inputAudioFormat.sampleRate
        ))
    }

    @Throws(UnhandledAudioFormatException::class)
    public override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != OUTPUT_ENCODING) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        reconfigurationPending = true
        return if (trimStartFrames != 0 || trimEndFrames != 0) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var remaining = limit - position

        if (remaining == 0) {
            return
        }

        // Trim any pending start bytes from the input buffer.
        val trimBytes =
            min(remaining.toDouble(), pendingTrimStartBytes.toDouble()).toInt()
        trimmedFrameCount += (trimBytes / inputAudioFormat.bytesPerFrame).toLong()
        pendingTrimStartBytes -= trimBytes
        inputBuffer.position(position + trimBytes)
        if (pendingTrimStartBytes > 0) {
            // Nothing to output yet.
            return
        }
        remaining -= trimBytes

        // endBuffer must be kept as full as possible, so that we trim the right amount of media if we
        // don't receive any more input. After taking into account the number of bytes needed to keep
        // endBuffer as full as possible, the output should be any surplus bytes currently in endBuffer
        // followed by any surplus bytes in the new inputBuffer.
        var remainingBytesToOutput = endBufferSize + remaining - endBuffer.size
        val buffer = replaceOutputBuffer(remainingBytesToOutput)

        // Output from endBuffer.
        val endBufferBytesToOutput = Util.constrainValue(remainingBytesToOutput, 0, endBufferSize)
        buffer.put(endBuffer, 0, endBufferBytesToOutput)
        remainingBytesToOutput -= endBufferBytesToOutput

        // Output from inputBuffer, restoring its limit afterwards.
        val inputBufferBytesToOutput = Util.constrainValue(remainingBytesToOutput, 0, remaining)
        inputBuffer.limit(inputBuffer.position() + inputBufferBytesToOutput)
        buffer.put(inputBuffer)
        inputBuffer.limit(limit)
        remaining -= inputBufferBytesToOutput

        // Compact endBuffer, then repopulate it using the new input.
        endBufferSize -= endBufferBytesToOutput
        System.arraycopy(endBuffer, endBufferBytesToOutput, endBuffer, 0, endBufferSize)
        inputBuffer[endBuffer, endBufferSize, remaining]
        endBufferSize += remaining

        buffer.flip()
    }

    override fun getOutput(): ByteBuffer {
        if (super.isEnded() && endBufferSize > 0) {
            // Because audio processors may be drained in the middle of the stream we assume that the
            // contents of the end buffer need to be output. For gapless transitions, configure will
            // always be called, so the end buffer is cleared in onQueueEndOfStream.
            replaceOutputBuffer(endBufferSize).put(endBuffer, 0, endBufferSize).flip()
            endBufferSize = 0
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean {
        return super.isEnded() && endBufferSize == 0
    }

    override fun onQueueEndOfStream() {
        if (reconfigurationPending) {
            // Trim audio in the end buffer.
            if (endBufferSize > 0) {
                trimmedFrameCount += (endBufferSize / inputAudioFormat.bytesPerFrame).toLong()
            }
            endBufferSize = 0
        }
    }

    override fun onFlush() {
        if (reconfigurationPending) {
            // Flushing activates the new configuration, so prepare to trim bytes from the start/end.
            reconfigurationPending = false
            endBuffer = ByteArray(trimEndFrames * inputAudioFormat.bytesPerFrame)
            pendingTrimStartBytes = trimStartFrames * inputAudioFormat.bytesPerFrame
        }

        // TODO(internal b/77292509): Flushing occurs to activate a configuration (handled above) but
        // also when seeking within a stream. This implementation currently doesn't handle seek to start
        // (where we need to trim at the start again), nor seeks to non-zero positions before start
        // trimming has occurred (where we should set pendingTrimStartBytes to zero). These cases can be
        // fixed by trimming in queueInput based on timestamp, once that information is available.

        // Any data in the end buffer should no longer be output if we are playing from a different
        // position, so discard it and refill the buffer using new input.
        endBufferSize = 0
    }

    override fun onReset() {
        endBuffer = Util.EMPTY_BYTE_ARRAY
    }

    companion object {
        private const val OUTPUT_ENCODING: @PcmEncoding Int = C.ENCODING_PCM_16BIT
    }
}