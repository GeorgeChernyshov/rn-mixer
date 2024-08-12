package com.example.polandandroidarms.audiosink

import android.media.AudioTrack
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@UnstableApi
internal class AudioTrackPositionTracker(listener: Listener?) {
    /** Listener for position tracker events.  */
    interface Listener {
        /**
         * Called when the position tracker's position has increased for the first time since it was
         * last paused or reset.
         *
         * @param playoutStartSystemTimeMs The approximate derived [System.currentTimeMillis] at
         * which playout started.
         */
        fun onPositionAdvancing(playoutStartSystemTimeMs: Long)

        /**
         * Called when the frame position is too far from the expected frame position.
         *
         * @param audioTimestampPositionFrames The frame position of the last known audio track
         * timestamp.
         * @param audioTimestampSystemTimeUs The system time associated with the last known audio track
         * timestamp, in microseconds.
         * @param systemTimeUs The current time.
         * @param playbackPositionUs The current playback head position in microseconds.
         */
        fun onPositionFramesMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        )

        /**
         * Called when the system time associated with the last known audio track timestamp is
         * unexpectedly far from the current time.
         *
         * @param audioTimestampPositionFrames The frame position of the last known audio track
         * timestamp.
         * @param audioTimestampSystemTimeUs The system time associated with the last known audio track
         * timestamp, in microseconds.
         * @param systemTimeUs The current time.
         * @param playbackPositionUs The current playback head position in microseconds.
         */
        fun onSystemTimeUsMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        )

        /**
         * Called when the audio track has provided an invalid latency.
         *
         * @param latencyUs The reported latency in microseconds.
         */
        fun onInvalidLatency(latencyUs: Long)

        /**
         * Called when the audio track runs out of data to play.
         *
         * @param bufferSize The size of the sink's buffer, in bytes.
         * @param bufferSizeMs The size of the sink's buffer, in milliseconds, if it is configured for
         * PCM output. [C.TIME_UNSET] if it is configured for encoded audio output, as the
         * buffered media can have a variable bitrate so the duration may be unknown.
         */
        fun onUnderrun(bufferSize: Int, bufferSizeMs: Long)
    }

    private val listener = Assertions.checkNotNull(listener)
    private val playheadOffsets: LongArray

    private var audioTrack: AudioTrack? = null
    private var outputPcmFrameSize = 0
    private var bufferSize = 0
    private var audioTimestampPoller: AudioTimestampPoller? = null
    private var outputSampleRate = 0
    private var needsPassthroughWorkarounds = false
    private var bufferSizeUs: Long = 0
    private var audioTrackPlaybackSpeed = 0f
    private var notifiedPositionIncreasing = false

    private var smoothedPlayheadOffsetUs: Long = 0
    private var lastPlayheadSampleTimeUs: Long = 0

    private var getLatencyMethod: Method? = null
    private var latencyUs: Long = 0
    private var hasData = false

    private var isOutputPcm = false
    private var lastLatencySampleTimeUs: Long = 0
    private var lastRawPlaybackHeadPositionSampleTimeMs: Long = 0
    private var rawPlaybackHeadPosition: Long = 0
    private var rawPlaybackHeadWrapCount: Long = 0
    private var passthroughWorkaroundPauseOffset: Long = 0
    private var nextPlayheadOffsetIndex = 0
    private var playheadOffsetCount = 0
    private var stopTimestampUs: Long = 0
    private var forceResetWorkaroundTimeMs: Long = 0
    private var stopPlaybackHeadPosition: Long = 0
    private var endPlaybackHeadPosition: Long = 0

    // Results from the previous call to getCurrentPositionUs.
    private var lastPositionUs: Long = 0
    private var lastSystemTimeUs: Long = 0
    private var lastSampleUsedGetTimestampMode = false

    // Results from the last call to getCurrentPositionUs that used a different sample mode.
    private var previousModePositionUs: Long = 0
    private var previousModeSystemTimeUs: Long = 0

    /**
     * Whether to expect a raw playback head reset.
     *
     *
     * When an [AudioTrack] is reused during offloaded playback, rawPlaybackHeadPosition is
     * reset upon track transition. [AudioTrackPositionTracker] must be notified of the
     * impending reset and keep track of total accumulated rawPlaybackHeadPosition.
     */
    private var expectRawPlaybackHeadReset = false

    private var sumRawPlaybackHeadPosition: Long = 0

    private var clock: Clock

    /**
     * Creates a new audio track position tracker.
     *
     * @param listener A listener for position tracking events.
     */
    init {
        try {
            getLatencyMethod =
                AudioTrack::class.java.getMethod("getLatency", null)
        } catch (e: NoSuchMethodException) {
            // There's no guarantee this method exists. Do nothing.
        }
        playheadOffsets = LongArray(MAX_PLAYHEAD_OFFSET_COUNT)
        clock = Clock.DEFAULT
    }

    /**
     * Sets the [AudioTrack] to wrap. Subsequent method calls on this instance relate to this
     * track's position, until the next call to [.reset].
     *
     * @param audioTrack The audio track to wrap.
     * @param isPassthrough Whether passthrough mode is being used.
     * @param outputEncoding The encoding of the audio track.
     * @param outputPcmFrameSize For PCM output encodings, the frame size. The value is ignored
     * otherwise.
     * @param bufferSize The audio track buffer size in bytes.
     */
    fun setAudioTrack(
        audioTrack: AudioTrack,
        isPassthrough: Boolean,
        outputEncoding: @C.Encoding Int,
        outputPcmFrameSize: Int,
        bufferSize: Int
    ) {
        this.audioTrack = audioTrack
        this.outputPcmFrameSize = outputPcmFrameSize
        this.bufferSize = bufferSize
        audioTimestampPoller = AudioTimestampPoller(audioTrack)
        outputSampleRate = audioTrack.sampleRate
        isOutputPcm = Util.isEncodingLinearPcm(outputEncoding)
        bufferSizeUs =
            if (isOutputPcm
            ) Util.sampleCountToDurationUs(
                (bufferSize / outputPcmFrameSize).toLong(),
                outputSampleRate
            )
            else C.TIME_UNSET
        rawPlaybackHeadPosition = 0
        rawPlaybackHeadWrapCount = 0
        expectRawPlaybackHeadReset = false
        sumRawPlaybackHeadPosition = 0
        passthroughWorkaroundPauseOffset = 0
        hasData = false
        stopTimestampUs = C.TIME_UNSET
        forceResetWorkaroundTimeMs = C.TIME_UNSET
        lastLatencySampleTimeUs = 0
        latencyUs = 0
        audioTrackPlaybackSpeed = 1f
    }

    fun setAudioTrackPlaybackSpeed(audioTrackPlaybackSpeed: Float) {
        this.audioTrackPlaybackSpeed = audioTrackPlaybackSpeed
        // Extrapolation from the last audio timestamp relies on the audio rate being constant, so we
        // reset audio timestamp tracking and wait for a new timestamp.
        if (audioTimestampPoller != null) {
            audioTimestampPoller!!.reset()
        }
        resetSyncParams()
    }

    fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (Assertions.checkNotNull(this.audioTrack).playState == PLAYSTATE_PLAYING) {
            maybeSampleSyncParams()
        }

        // If the device supports it, use the playback timestamp from AudioTrack.getTimestamp.
        // Otherwise, derive a smoothed position by sampling the track's frame position.
        val systemTimeUs = clock.nanoTime() / 1000
        var positionUs: Long
        val audioTimestampPoller = Assertions.checkNotNull(this.audioTimestampPoller)
        val useGetTimestampMode = audioTimestampPoller.hasAdvancingTimestamp()
        if (useGetTimestampMode) {
            // Calculate the speed-adjusted position using the timestamp (which may be in the future).
            val timestampPositionFrames = audioTimestampPoller.timestampPositionFrames
            val timestampPositionUs =
                Util.sampleCountToDurationUs(timestampPositionFrames, outputSampleRate)
            var elapsedSinceTimestampUs =
                systemTimeUs - audioTimestampPoller.timestampSystemTimeUs
            elapsedSinceTimestampUs =
                Util.getMediaDurationForPlayoutDuration(
                    elapsedSinceTimestampUs,
                    audioTrackPlaybackSpeed
                )
            positionUs = timestampPositionUs + elapsedSinceTimestampUs
        } else {
            positionUs = if (playheadOffsetCount == 0) {
                // The AudioTrack has started, but we don't have any samples to compute a smoothed position.
                playbackHeadPositionUs
            } else {
                // getPlaybackHeadPositionUs() only has a granularity of ~20 ms, so we base the position off
                // the system clock (and a smoothed offset between it and the playhead position) so as to
                // prevent jitter in the reported positions.
                Util.getMediaDurationForPlayoutDuration(
                    systemTimeUs + smoothedPlayheadOffsetUs, audioTrackPlaybackSpeed
                )
            }
            if (!sourceEnded) {
                positionUs = max(0.0, (positionUs - latencyUs).toDouble()).toLong()
            }
        }

        if (lastSampleUsedGetTimestampMode != useGetTimestampMode) {
            // We've switched sampling mode.
            previousModeSystemTimeUs = lastSystemTimeUs
            previousModePositionUs = lastPositionUs
        }
        val elapsedSincePreviousModeUs = systemTimeUs - previousModeSystemTimeUs
        if (elapsedSincePreviousModeUs < MODE_SWITCH_SMOOTHING_DURATION_US) {
            // Use a ramp to smooth between the old mode and the new one to avoid introducing a sudden
            // jump if the two modes disagree.
            val previousModeProjectedPositionUs = (
                    previousModePositionUs
                            + Util.getMediaDurationForPlayoutDuration(
                        elapsedSincePreviousModeUs, audioTrackPlaybackSpeed
                    ))
            // A ramp consisting of 1000 points distributed over MODE_SWITCH_SMOOTHING_DURATION_US.
            val rampPoint = (elapsedSincePreviousModeUs * 1000) / MODE_SWITCH_SMOOTHING_DURATION_US
            positionUs *= rampPoint
            positionUs += (1000 - rampPoint) * previousModeProjectedPositionUs
            positionUs /= 1000
        }

        if (!notifiedPositionIncreasing && positionUs > lastPositionUs) {
            notifiedPositionIncreasing = true
            val mediaDurationSinceLastPositionUs = Util.usToMs(positionUs - lastPositionUs)
            val playoutDurationSinceLastPositionUs =
                Util.getPlayoutDurationForMediaDuration(
                    mediaDurationSinceLastPositionUs, audioTrackPlaybackSpeed
                )
            val playoutStartSystemTimeMs =
                clock.currentTimeMillis() - Util.usToMs(playoutDurationSinceLastPositionUs)
            listener.onPositionAdvancing(playoutStartSystemTimeMs)
        }

        lastSystemTimeUs = systemTimeUs
        lastPositionUs = positionUs
        lastSampleUsedGetTimestampMode = useGetTimestampMode

        return positionUs
    }

    /** Starts position tracking. Must be called immediately before [AudioTrack.play].  */
    fun start() {
        if (stopTimestampUs != C.TIME_UNSET) {
            stopTimestampUs = Util.msToUs(clock.elapsedRealtime())
        }
        Assertions.checkNotNull(audioTimestampPoller).reset()
    }

    val isPlaying: Boolean
        /** Returns whether the audio track is in the playing state.  */
        get() = Assertions.checkNotNull(audioTrack).playState == PLAYSTATE_PLAYING

    /**
     * Checks the state of the audio track and returns whether the caller can write data to the track.
     * Notifies [Listener.onUnderrun] if the track has underrun.
     *
     * @param writtenFrames The number of frames that have been written.
     * @return Whether the caller can write data to the track.
     */
    fun mayHandleBuffer(writtenFrames: Long): Boolean {
        val playState = Assertions.checkNotNull(audioTrack).playState
        if (needsPassthroughWorkarounds) {
            // An AC-3 audio track continues to play data written while it is paused. Stop writing so its
            // buffer empties. See [Internal: b/18899620].
            if (playState == PLAYSTATE_PAUSED) {
                // We force an underrun to pause the track, so don't notify the listener in this case.
                hasData = false
                return false
            }

            // A new AC-3 audio track's playback position continues to increase from the old track's
            // position for a short time after is has been released. Avoid writing data until the playback
            // head position actually returns to zero.
            if (playState == PLAYSTATE_STOPPED && playbackHeadPosition == 0L) {
                return false
            }
        }

        val hadData = hasData
        hasData = hasPendingData(writtenFrames)
        if (hadData && !hasData && playState != PLAYSTATE_STOPPED) {
            listener.onUnderrun(bufferSize, Util.usToMs(bufferSizeUs))
        }

        return true
    }

    /**
     * Returns an estimate of the number of additional bytes that can be written to the audio track's
     * buffer without running out of space.
     *
     *
     * May only be called if the output encoding is one of the PCM encodings.
     *
     * @param writtenBytes The number of bytes written to the audio track so far.
     * @return An estimate of the number of bytes that can be written.
     */
    fun getAvailableBufferSize(writtenBytes: Long): Int {
        val bytesPending = (writtenBytes - (playbackHeadPosition * outputPcmFrameSize)).toInt()
        return bufferSize - bytesPending
    }

    /** Returns whether the track is in an invalid state and must be recreated.  */
    fun isStalled(writtenFrames: Long): Boolean {
        return forceResetWorkaroundTimeMs != C.TIME_UNSET && writtenFrames > 0 && (clock.elapsedRealtime() - forceResetWorkaroundTimeMs
                >= FORCE_RESET_WORKAROUND_TIMEOUT_MS)
    }

    /**
     * Records the writing position at which the stream ended, so that the reported position can
     * continue to increment while remaining data is played out.
     *
     * @param writtenFrames The number of frames that have been written.
     */
    fun handleEndOfStream(writtenFrames: Long) {
        stopPlaybackHeadPosition = playbackHeadPosition
        stopTimestampUs = Util.msToUs(clock.elapsedRealtime())
        endPlaybackHeadPosition = writtenFrames
    }

    /**
     * Returns whether the audio track has any pending data to play out at its current position.
     *
     * @param writtenFrames The number of frames written to the audio track.
     * @return Whether the audio track has any pending data to play out.
     */
    fun hasPendingData(writtenFrames: Long): Boolean {
        val currentPositionUs = getCurrentPositionUs( /* sourceEnded= */false)
        return (writtenFrames > Util.durationUsToSampleCount(currentPositionUs, outputSampleRate)
                || forceHasPendingData())
    }

    /**
     * Pauses the audio track position tracker, returning whether the audio track needs to be paused
     * to cause playback to pause. If `false` is returned the audio track will pause without
     * further interaction, as the end of stream has been handled.
     */
    fun pause(): Boolean {
        resetSyncParams()
        if (stopTimestampUs == C.TIME_UNSET) {
            // The audio track is going to be paused, so reset the timestamp poller to ensure it doesn't
            // supply an advancing position.
            Assertions.checkNotNull(audioTimestampPoller).reset()
            return true
        }
        stopPlaybackHeadPosition = playbackHeadPosition
        // We've handled the end of the stream already, so there's no need to pause the track.
        return false
    }

    /**
     * Sets up the position tracker to expect a reset in raw playback head position due to reusing an
     * [AudioTrack] and an impending track transition.
     */
    fun expectRawPlaybackHeadReset() {
        expectRawPlaybackHeadReset = true
        if (audioTimestampPoller != null) {
            audioTimestampPoller!!.expectTimestampFramePositionReset()
        }
    }

    /**
     * Resets the position tracker. Should be called when the audio track previously passed to [ ][.setAudioTrack] is no longer in use.
     */
    fun reset() {
        resetSyncParams()
        audioTrack = null
        audioTimestampPoller = null
    }

    /**
     * Sets the [Clock].
     *
     * @param clock The [Clock].
     */
    fun setClock(clock: Clock) {
        this.clock = clock
    }

    private fun maybeSampleSyncParams() {
        val systemTimeUs = clock.nanoTime() / 1000
        if (systemTimeUs - lastPlayheadSampleTimeUs >= MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US) {
            val playbackPositionUs = playbackHeadPositionUs
            if (playbackPositionUs == 0L) {
                // The AudioTrack hasn't output anything yet.
                return
            }
            // Take a new sample and update the smoothed offset between the system clock and the playhead.
            playheadOffsets[nextPlayheadOffsetIndex] = (
                    Util.getPlayoutDurationForMediaDuration(
                        playbackPositionUs,
                        audioTrackPlaybackSpeed
                    )
                            - systemTimeUs)
            nextPlayheadOffsetIndex = (nextPlayheadOffsetIndex + 1) % MAX_PLAYHEAD_OFFSET_COUNT
            if (playheadOffsetCount < MAX_PLAYHEAD_OFFSET_COUNT) {
                playheadOffsetCount++
            }
            lastPlayheadSampleTimeUs = systemTimeUs
            smoothedPlayheadOffsetUs = 0
            for (i in 0 until playheadOffsetCount) {
                smoothedPlayheadOffsetUs += playheadOffsets[i] / playheadOffsetCount
            }
        }

        if (needsPassthroughWorkarounds) {
            // Don't sample the timestamp and latency if this is an AC-3 passthrough AudioTrack on
            // platform API versions 21/22, as incorrect values are returned. See [Internal: b/21145353].
            return
        }

        maybePollAndCheckTimestamp(systemTimeUs)
        maybeUpdateLatency(systemTimeUs)
    }

    private fun maybePollAndCheckTimestamp(systemTimeUs: Long) {
        val audioTimestampPoller = Assertions.checkNotNull(this.audioTimestampPoller)
        if (!audioTimestampPoller.maybePollTimestamp(systemTimeUs)) {
            return
        }

        // Check the timestamp and accept/reject it.
        val timestampSystemTimeUs = audioTimestampPoller.timestampSystemTimeUs
        val timestampPositionFrames = audioTimestampPoller.timestampPositionFrames
        val playbackPositionUs = playbackHeadPositionUs
        if (abs((timestampSystemTimeUs - systemTimeUs).toDouble()) > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
            listener.onSystemTimeUsMismatch(
                timestampPositionFrames, timestampSystemTimeUs, systemTimeUs, playbackPositionUs
            )
            audioTimestampPoller.rejectTimestamp()
        } else if (abs(
                (Util.sampleCountToDurationUs(
                    timestampPositionFrames,
                    outputSampleRate
                ) - playbackPositionUs).toDouble()
            ) > MAX_AUDIO_TIMESTAMP_OFFSET_US
        ) {
            listener.onPositionFramesMismatch(
                timestampPositionFrames, timestampSystemTimeUs, systemTimeUs, playbackPositionUs
            )
            audioTimestampPoller.rejectTimestamp()
        } else {
            audioTimestampPoller.acceptTimestamp()
        }
    }

    private fun maybeUpdateLatency(systemTimeUs: Long) {
        if (isOutputPcm && getLatencyMethod != null && systemTimeUs - lastLatencySampleTimeUs >= MIN_LATENCY_SAMPLE_INTERVAL_US) {
            try {
                // Compute the audio track latency, excluding the latency due to the buffer (leaving
                // latency due to the mixer and audio hardware driver).
                latencyUs = (
                        Util.castNonNull(
                            getLatencyMethod!!.invoke(
                                Assertions.checkNotNull(
                                    audioTrack
                                )
                            ) as Int
                        ) * 1000L
                                - bufferSizeUs)
                // Check that the latency is non-negative.
                latencyUs = max(latencyUs.toDouble(), 0.0).toLong()
                // Check that the latency isn't too large.
                if (latencyUs > MAX_LATENCY_US) {
                    listener.onInvalidLatency(latencyUs)
                    latencyUs = 0
                }
            } catch (e: Exception) {
                // The method existed, but doesn't work. Don't try again.
                getLatencyMethod = null
            }
            lastLatencySampleTimeUs = systemTimeUs
        }
    }

    private fun resetSyncParams() {
        smoothedPlayheadOffsetUs = 0
        playheadOffsetCount = 0
        nextPlayheadOffsetIndex = 0
        lastPlayheadSampleTimeUs = 0
        lastSystemTimeUs = 0
        previousModeSystemTimeUs = 0
        notifiedPositionIncreasing = false
    }

    /**
     * If passthrough workarounds are enabled, pausing is implemented by forcing the AudioTrack to
     * underrun. In this case, still behave as if we have pending data, otherwise writing won't
     * resume.
     */
    private fun forceHasPendingData(): Boolean {
        return needsPassthroughWorkarounds && Assertions.checkNotNull(
            audioTrack
        ).playState == AudioTrack.PLAYSTATE_PAUSED && playbackHeadPosition == 0L
    }

    private val playbackHeadPositionUs: Long
        get() = Util.sampleCountToDurationUs(playbackHeadPosition, outputSampleRate)

    private val playbackHeadPosition: Long
        /**
         * [AudioTrack.getPlaybackHeadPosition] returns a value intended to be interpreted as an
         * unsigned 32 bit integer, which also wraps around periodically. This method returns the playback
         * head position as a long that will only wrap around if the value exceeds [Long.MAX_VALUE]
         * (which in practice will never happen).
         *
         * @return The playback head position, in frames.
         */
        get() {
            val currentTimeMs = clock.elapsedRealtime()
            if (stopTimestampUs != C.TIME_UNSET) {
                if (Assertions.checkNotNull(this.audioTrack).playState == AudioTrack.PLAYSTATE_PAUSED) {
                    // If AudioTrack is paused while stopping, then return cached playback head position.
                    return stopPlaybackHeadPosition
                }
                // Simulate the playback head position up to the total number of frames submitted.
                val elapsedTimeSinceStopUs = Util.msToUs(currentTimeMs) - stopTimestampUs
                val mediaTimeSinceStopUs =
                    Util.getMediaDurationForPlayoutDuration(
                        elapsedTimeSinceStopUs,
                        audioTrackPlaybackSpeed
                    )
                val framesSinceStop =
                    Util.durationUsToSampleCount(mediaTimeSinceStopUs, outputSampleRate)
                return min(
                    endPlaybackHeadPosition.toDouble(),
                    (stopPlaybackHeadPosition + framesSinceStop).toDouble()
                )
                    .toLong()
            }
            if (currentTimeMs - lastRawPlaybackHeadPositionSampleTimeMs
                >= RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS
            ) {
                updateRawPlaybackHeadPosition(currentTimeMs)
                lastRawPlaybackHeadPositionSampleTimeMs = currentTimeMs
            }
            return rawPlaybackHeadPosition + sumRawPlaybackHeadPosition + (rawPlaybackHeadWrapCount shl 32)
        }

    private fun updateRawPlaybackHeadPosition(currentTimeMs: Long) {
        val audioTrack = Assertions.checkNotNull(this.audioTrack)
        val state = audioTrack.playState
        if (state == PLAYSTATE_STOPPED) {
            // The audio track hasn't been started. Keep initial zero timestamp.
            return
        }
        var rawPlaybackHeadPosition = 0xFFFFFFFFL and audioTrack.playbackHeadPosition.toLong()
        if (needsPassthroughWorkarounds) {
            // Work around an issue with passthrough/direct AudioTracks on platform API versions 21/22
            // where the playback head position jumps back to zero on paused passthrough/direct audio
            // tracks. See [Internal: b/19187573].
            if (state == PLAYSTATE_PAUSED && rawPlaybackHeadPosition == 0L) {
                passthroughWorkaroundPauseOffset = this.rawPlaybackHeadPosition
            }
            rawPlaybackHeadPosition += passthroughWorkaroundPauseOffset
        }

        if (Util.SDK_INT <= 29) {
            if (rawPlaybackHeadPosition == 0L && this.rawPlaybackHeadPosition > 0 && state == PLAYSTATE_PLAYING) {
                // If connecting a Bluetooth audio device fails, the AudioTrack may be left in a state
                // where its Java API is in the playing state, but the native track is stopped. When this
                // happens the playback head position gets stuck at zero. In this case, return the old
                // playback head position and force the track to be reset after
                // {@link #FORCE_RESET_WORKAROUND_TIMEOUT_MS} has elapsed.
                if (forceResetWorkaroundTimeMs == C.TIME_UNSET) {
                    forceResetWorkaroundTimeMs = currentTimeMs
                }
                return
            } else {
                forceResetWorkaroundTimeMs = C.TIME_UNSET
            }
        }

        if (this.rawPlaybackHeadPosition > rawPlaybackHeadPosition) {
            if (expectRawPlaybackHeadReset) {
                sumRawPlaybackHeadPosition += this.rawPlaybackHeadPosition
                expectRawPlaybackHeadReset = false
            } else {
                // The value must have wrapped around.
                rawPlaybackHeadWrapCount++
            }
        }
        this.rawPlaybackHeadPosition = rawPlaybackHeadPosition
    }

    companion object {
        /**
         * @see AudioTrack.PLAYSTATE_STOPPED
         */
        private const val PLAYSTATE_STOPPED = AudioTrack.PLAYSTATE_STOPPED

        /**
         * @see AudioTrack.PLAYSTATE_PAUSED
         */
        private const val PLAYSTATE_PAUSED = AudioTrack.PLAYSTATE_PAUSED

        /**
         * @see AudioTrack.PLAYSTATE_PLAYING
         */
        private const val PLAYSTATE_PLAYING = AudioTrack.PLAYSTATE_PLAYING

        /**
         * AudioTrack timestamps are deemed spurious if they are offset from the system clock by more than
         * this amount.
         *
         *
         * This is a fail safe that should not be required on correctly functioning devices.
         */
        private const val MAX_AUDIO_TIMESTAMP_OFFSET_US = 5 * C.MICROS_PER_SECOND

        /**
         * AudioTrack latencies are deemed impossibly large if they are greater than this amount.
         *
         *
         * This is a fail safe that should not be required on correctly functioning devices.
         */
        private const val MAX_LATENCY_US = 5 * C.MICROS_PER_SECOND

        /** The duration of time used to smooth over an adjustment between position sampling modes.  */
        private const val MODE_SWITCH_SMOOTHING_DURATION_US = C.MICROS_PER_SECOND

        /** Minimum update interval for getting the raw playback head position, in milliseconds.  */
        private const val RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS: Long = 5

        private const val FORCE_RESET_WORKAROUND_TIMEOUT_MS: Long = 200

        private const val MAX_PLAYHEAD_OFFSET_COUNT = 10
        private const val MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US = 30000
        private const val MIN_LATENCY_SAMPLE_INTERVAL_US = 500000
    }
}