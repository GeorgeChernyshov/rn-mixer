package com.example.polandandroidarms.audiosink

import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.annotation.IntDef
import androidx.media3.common.C

internal class AudioTimestampPoller(audioTrack: AudioTrack) {

    private val audioTimestamp = AudioTimestampWrapper(audioTrack)

    private var state = 0
    private var initializeSystemTimeUs: Long = 0
    private var sampleIntervalUs: Long = 0
    private var lastTimestampSampleTimeUs: Long = 0
    private var initialTimestampPositionFrames: Long = 0

    /**
     * Creates a new audio timestamp poller.
     *
     * @param audioTrack The audio track that will provide timestamps.
     */
    init {
        reset()
    }

    /**
     * Polls the timestamp if required and returns whether it was updated. If `true`, the latest
     * timestamp is available via [.getTimestampSystemTimeUs] and [ ][.getTimestampPositionFrames], and the caller should call [.acceptTimestamp] if the
     * timestamp was valid, or [.rejectTimestamp] otherwise. The values returned by [ ][.hasTimestamp] and [.hasAdvancingTimestamp] may be updated.
     *
     * @param systemTimeUs The current system time, in microseconds.
     * @return Whether the timestamp was updated.
     */
    fun maybePollTimestamp(systemTimeUs: Long): Boolean {
        if ((systemTimeUs - lastTimestampSampleTimeUs) < sampleIntervalUs) {
            return false
        }
        lastTimestampSampleTimeUs = systemTimeUs
        var updatedTimestamp = audioTimestamp.maybeUpdateTimestamp()
        when (state) {
            STATE_INITIALIZING -> if (updatedTimestamp) {
                if (audioTimestamp.timestampSystemTimeUs >= initializeSystemTimeUs) {
                    // We have an initial timestamp, but don't know if it's advancing yet.
                    initialTimestampPositionFrames = audioTimestamp.timestampPositionFrames
                    updateState(STATE_TIMESTAMP)
                } else {
                    // Drop the timestamp, as it was sampled before the last reset.
                    updatedTimestamp = false
                }
            } else if (systemTimeUs - initializeSystemTimeUs > INITIALIZING_DURATION_US) {
                // We haven't received a timestamp for a while, so they probably aren't available for the
                // current audio route. Poll infrequently in case the route changes later.
                // TODO: Ideally we should listen for audio route changes in order to detect when a
                // timestamp becomes available again.
                updateState(STATE_NO_TIMESTAMP)
            }

            STATE_TIMESTAMP -> if (updatedTimestamp) {
                val timestampPositionFrames = audioTimestamp.timestampPositionFrames
                if (timestampPositionFrames > initialTimestampPositionFrames) {
                    updateState(STATE_TIMESTAMP_ADVANCING)
                }
            } else {
                reset()
            }

            STATE_TIMESTAMP_ADVANCING -> if (!updatedTimestamp) {
                // The audio route may have changed, so reset polling.
                reset()
            }

            STATE_NO_TIMESTAMP -> if (updatedTimestamp) {
                // The audio route may have changed, so reset polling.
                reset()
            }

            STATE_ERROR -> {}
            else -> throw IllegalStateException()
        }
        return updatedTimestamp
    }

    /**
     * Rejects the timestamp last polled in [.maybePollTimestamp]. The instance will enter
     * the error state and poll timestamps infrequently until the next call to [ ][.acceptTimestamp].
     */
    fun rejectTimestamp() {
        updateState(STATE_ERROR)
    }

    /**
     * Accepts the timestamp last polled in [.maybePollTimestamp]. If the instance is in
     * the error state, it will begin to poll timestamps frequently again.
     */
    fun acceptTimestamp() {
        if (state == STATE_ERROR) {
            reset()
        }
    }

    /**
     * Returns whether this instance has a timestamp that can be used to calculate the audio track
     * position. If `true`, call [.getTimestampSystemTimeUs] and [ ][.getTimestampSystemTimeUs] to access the timestamp.
     */
    fun hasTimestamp(): Boolean {
        return state == STATE_TIMESTAMP || state == STATE_TIMESTAMP_ADVANCING
    }

    /**
     * Returns whether this instance has an advancing timestamp. If `true`, call [ ][.getTimestampSystemTimeUs] and [.getTimestampSystemTimeUs] to access the timestamp. A
     * current position for the track can be extrapolated based on elapsed real time since the system
     * time at which the timestamp was sampled.
     */
    fun hasAdvancingTimestamp(): Boolean {
        return state == STATE_TIMESTAMP_ADVANCING
    }

    /** Resets polling. Should be called whenever the audio track is paused or resumed.  */
    fun reset() {
        if (audioTimestamp != null) {
            updateState(STATE_INITIALIZING)
        }
    }

    val timestampSystemTimeUs: Long
        /**
         * If [.maybePollTimestamp] or [.hasTimestamp] returned `true`, returns
         * the system time at which the latest timestamp was sampled, in microseconds.
         */
        get() = audioTimestamp.timestampSystemTimeUs

    val timestampPositionFrames: Long
        /**
         * If [.maybePollTimestamp] or [.hasTimestamp] returned `true`, returns
         * the latest timestamp's position in frames.
         */
        get() = audioTimestamp.timestampPositionFrames

    /**
     * Sets up the poller to expect a reset in audio track frame position due to an impending track
     * transition and reusing of the [AudioTrack].
     */
    fun expectTimestampFramePositionReset() {
        audioTimestamp.expectTimestampFramePositionReset()
    }

    private fun updateState(state: Int) {
        this.state = state
        when (state) {
            STATE_INITIALIZING -> {
                // Force polling a timestamp immediately, and poll quickly.
                lastTimestampSampleTimeUs = 0
                initialTimestampPositionFrames = C.INDEX_UNSET.toLong()
                initializeSystemTimeUs = System.nanoTime() / 1000
                sampleIntervalUs = FAST_POLL_INTERVAL_US.toLong()
            }

            STATE_TIMESTAMP -> sampleIntervalUs = FAST_POLL_INTERVAL_US.toLong()
            STATE_TIMESTAMP_ADVANCING, STATE_NO_TIMESTAMP -> sampleIntervalUs =
                SLOW_POLL_INTERVAL_US.toLong()

            STATE_ERROR -> sampleIntervalUs = ERROR_POLL_INTERVAL_US.toLong()
            else -> throw IllegalStateException()
        }
    }

    private class AudioTimestampWrapper(private val audioTrack: AudioTrack) {
        private val audioTimestamp = AudioTimestamp()

        private var rawTimestampFramePositionWrapCount: Long = 0
        private var lastTimestampRawPositionFrames: Long = 0
        var timestampPositionFrames: Long = 0
            private set

        /**
         * Whether to expect a raw playback head reset.
         *
         *
         * When an [AudioTrack] is reused during offloaded playback, the [ ][AudioTimestamp.framePosition] is reset upon track transition. [AudioTimestampWrapper]
         * must be notified of the impending reset and keep track of total accumulated `AudioTimestamp.framePosition`.
         */
        private var expectTimestampFramePositionReset = false

        private var accumulatedRawTimestampFramePosition: Long = 0

        /**
         * Attempts to update the audio track timestamp. Returns `true` if the timestamp was
         * updated, in which case the updated timestamp system time and position can be accessed with
         * [.getTimestampSystemTimeUs] and [.getTimestampPositionFrames]. Returns `false` if no timestamp is available, in which case those methods should not be called.
         */
        fun maybeUpdateTimestamp(): Boolean {
            val updated = audioTrack.getTimestamp(audioTimestamp)
            if (updated) {
                val rawPositionFrames = audioTimestamp.framePosition
                if (lastTimestampRawPositionFrames > rawPositionFrames) {
                    if (expectTimestampFramePositionReset) {
                        accumulatedRawTimestampFramePosition += lastTimestampRawPositionFrames
                        expectTimestampFramePositionReset = false
                    } else {
                        // The value must have wrapped around.
                        rawTimestampFramePositionWrapCount++
                    }
                }
                lastTimestampRawPositionFrames = rawPositionFrames
                timestampPositionFrames =
                    (rawPositionFrames
                            + accumulatedRawTimestampFramePosition
                            + (rawTimestampFramePositionWrapCount shl 32))
            }
            return updated
        }

        val timestampSystemTimeUs: Long
            get() = audioTimestamp.nanoTime / 1000

        fun expectTimestampFramePositionReset() {
            expectTimestampFramePositionReset = true
        }
    }

    companion object {
        /** State when first initializing.  */
        private const val STATE_INITIALIZING = 0

        /** State when we have a timestamp and we don't know if it's advancing.  */
        private const val STATE_TIMESTAMP = 1

        /** State when we have a timestamp and we know it is advancing.  */
        private const val STATE_TIMESTAMP_ADVANCING = 2

        /** State when the no timestamp is available.  */
        private const val STATE_NO_TIMESTAMP = 3

        /** State when the last timestamp was rejected as invalid.  */
        private const val STATE_ERROR = 4

        /** The polling interval for [.STATE_INITIALIZING] and [.STATE_TIMESTAMP].  */
        private const val FAST_POLL_INTERVAL_US = 10000

        /**
         * The polling interval for [.STATE_TIMESTAMP_ADVANCING] and [.STATE_NO_TIMESTAMP].
         */
        private const val SLOW_POLL_INTERVAL_US = 10000000

        /** The polling interval for [.STATE_ERROR].  */
        private const val ERROR_POLL_INTERVAL_US = 500000

        /**
         * The minimum duration to remain in [.STATE_INITIALIZING] if no timestamps are being
         * returned before transitioning to [.STATE_NO_TIMESTAMP].
         */
        private const val INITIALIZING_DURATION_US = 500000
    }
}