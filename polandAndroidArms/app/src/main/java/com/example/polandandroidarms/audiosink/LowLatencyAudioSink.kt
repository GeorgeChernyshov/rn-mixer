package com.example.polandandroidarms.audiosink

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.metrics.LogSessionId
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.DoNotInline
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.Ac4Util
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.OpusUtil
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class LowLatencyAudioSink/* mediaTimeUs= */  /* audioTrackPositionUs= *//* routedDevice= */ private constructor(
    builder: Builder
) : AudioSink {
    /**
     * Thrown when the audio track has provided a spurious timestamp, if [ ][.failOnSpuriousAudioTimestamp] is set.
     */
    class InvalidAudioTrackTimestampException
    /**
     * Creates a new invalid timestamp exception with the specified message.
     *
     * @param message The detail message for this exception.
     */(message: String) : RuntimeException(message)


    @Deprecated("Use {@link androidx.media3.common.audio.AudioProcessorChain}.")
    interface AudioProcessorChain : androidx.media3.common.audio.AudioProcessorChain

    /**
     * The default audio processor chain, which applies a (possibly empty) chain of user-defined audio
     * processors followed by [SilenceSkippingAudioProcessor] and [SonicAudioProcessor].
     */
    @Suppress("deprecation")
    class DefaultAudioProcessorChain(
        audioProcessors: Array<AudioProcessor?>,
        silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
        sonicAudioProcessor: SonicAudioProcessor
    ) : AudioProcessorChain {
        // The passed-in type may be more specialized than AudioProcessor[], so allocate a new array
        // rather than using Arrays.copyOf.
        private val audioProcessors: Array<AudioProcessor?> = arrayOfNulls(audioProcessors.size + 2)
        private val silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor
        private val sonicAudioProcessor: SonicAudioProcessor

        /**
         * Creates a new default chain of audio processors, with the user-defined `audioProcessors` applied before silence skipping and speed adjustment processors.
         */
        constructor(vararg audioProcessors: AudioProcessor?) : this(
            audioProcessors.asSequence()
                .toList()
                .toTypedArray(),
            SilenceSkippingAudioProcessor(),
            SonicAudioProcessor()
        )

        /**
         * Creates a new default chain of audio processors, with the user-defined `audioProcessors` applied before silence skipping and speed adjustment processors.
         */
        init {
            System.arraycopy( /* src= */
                audioProcessors,  /* srcPos= */
                0,  /* dest= */
                this.audioProcessors,  /* destPos= */
                0,  /* length= */
                audioProcessors.size
            )
            this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor
            this.sonicAudioProcessor = sonicAudioProcessor
            this.audioProcessors[audioProcessors.size] = silenceSkippingAudioProcessor
            this.audioProcessors[audioProcessors.size + 1] = sonicAudioProcessor
        }

        override fun getAudioProcessors(): Array<AudioProcessor> {
            return audioProcessors.filterNotNull().toTypedArray()
        }

        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
            sonicAudioProcessor.setSpeed(playbackParameters.speed)
            sonicAudioProcessor.setPitch(playbackParameters.pitch)
            return playbackParameters
        }

        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
            silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
            return skipSilenceEnabled
        }

        override fun getMediaDuration(playoutDuration: Long): Long {
            return if (sonicAudioProcessor.isActive
            ) sonicAudioProcessor.getMediaDuration(playoutDuration)
            else playoutDuration
        }

        override fun getSkippedOutputFrameCount(): Long {
            return silenceSkippingAudioProcessor.skippedFrames
        }
    }

    /** Provides the buffer size to use when creating an [AudioTrack].  */
    interface AudioTrackBufferSizeProvider {
        /**
         * Returns the buffer size to use when creating an [AudioTrack] for a specific format and
         * output mode.
         *
         * @param minBufferSizeInBytes The minimum buffer size in bytes required to play this format.
         * See [AudioTrack.getMinBufferSize].
         * @param encoding The [C.Encoding] of the format.
         * @param outputMode How the audio will be played. One of the [output modes][OutputMode].
         * @param pcmFrameSize The size of the PCM frames if the `encoding` is PCM, 1 otherwise,
         * in bytes.
         * @param sampleRate The sample rate of the format, in Hz.
         * @param bitrate The bitrate of the audio stream if the stream is compressed, or [     ][Format.NO_VALUE] if `encoding` is PCM or the bitrate is not known.
         * @param maxAudioTrackPlaybackSpeed The maximum speed the content will be played using [     ][AudioTrack.setPlaybackParams]. 0.5 is 2x slow motion, 1 is real time, 2 is 2x fast
         * forward, etc. This will be `1` unless [     ][Builder.setEnableAudioTrackPlaybackParams] is enabled.
         * @return The computed buffer size in bytes. It should always be `>=
         * minBufferSizeInBytes`. The computed buffer size must contain an integer number of frames:
         * `bufferSizeInBytes % pcmFrameSize == 0`.
         */
        fun getBufferSizeInBytes(
            minBufferSizeInBytes: Int,
            encoding: @C.Encoding Int,
            outputMode: OutputMode,
            pcmFrameSize: Int,
            sampleRate: Int,
            bitrate: Int,
            maxAudioTrackPlaybackSpeed: Double
        ): Int
    }

    /** A builder to create [DefaultAudioSink] instances.  */
    class Builder(val context: Context?) {
        var audioCapabilities: AudioCapabilities?
        var audioProcessorChain: androidx.media3.common.audio.AudioProcessorChain? = null
        var enableFloatOutput: Boolean = false
        var enableAudioTrackPlaybackParams: Boolean = false

        private var buildCalled = false
        var audioOffloadSupportProvider: AudioOffloadSupportProvider? = null
        var audioOffloadListener: ExoPlayer.AudioOffloadListener? = null

        init {
            audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
        }

        @Deprecated("These {@linkplain AudioCapabilities audio capabilities} are only used in the\n" + "          absence of a {@linkplain Context context}. In the case when the {@code Context} is {@code\n" + "     *     null} and the {@code audioCapabilities} is not set to the {@code Builder}, the default\n" + "          capabilities (no encoded audio passthrough support) should be assumed.")
        fun setAudioCapabilities(audioCapabilities: AudioCapabilities?): Builder {
            Assertions.checkNotNull(audioCapabilities)
            this.audioCapabilities = audioCapabilities
            return this
        }

        /**
         * Sets an array of [AudioProcessors][AudioProcessor]s that will process PCM audio before
         * output. May be empty. Equivalent of `setAudioProcessorChain(new
         * DefaultAudioProcessorChain(audioProcessors)`.
         *
         *
         * The default value is an empty array.
         */
        fun setAudioProcessors(audioProcessors: Array<AudioProcessor>): Builder {
            Assertions.checkNotNull(audioProcessors)
            return setAudioProcessorChain(DefaultAudioProcessorChain(*audioProcessors))
        }

        /**
         * Sets the [androidx.media3.common.audio.AudioProcessorChain] to process audio before
         * playback. The instance passed in must not be reused in other sinks. Processing chains are
         * only supported for PCM playback (not passthrough or offload).
         *
         *
         * By default, no processing will be applied.
         */
        fun setAudioProcessorChain(
            audioProcessorChain: androidx.media3.common.audio.AudioProcessorChain?
        ): Builder {
            Assertions.checkNotNull(audioProcessorChain)
            this.audioProcessorChain = audioProcessorChain
            return this
        }

        /**
         * Sets whether to enable 32-bit float output or integer output. Where possible, 32-bit float
         * output will be used if the input is 32-bit float, and also if the input is high resolution
         * (24-bit or 32-bit) integer PCM. Float output is supported from API level 21. Audio processing
         * (for example, speed adjustment) will not be available when float output is in use.
         *
         *
         * The default value is `false`.
         */
        fun setEnableFloatOutput(enableFloatOutput: Boolean): Builder {
            this.enableFloatOutput = enableFloatOutput
            return this
        }

        /**
         * Sets whether to control the playback speed using the platform implementation (see [ ][AudioTrack.setPlaybackParams]), if supported. If set to `false`, speed
         * up/down of the audio will be done by ExoPlayer (see [SonicAudioProcessor]). Platform
         * speed adjustment is lower latency, but less reliable.
         *
         *
         * The default value is `false`.
         */
        fun setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams: Boolean): Builder {
            this.enableAudioTrackPlaybackParams = enableAudioTrackPlaybackParams
            return this
        }

        /**
         * Sets an [AudioOffloadSupportProvider] to provide the sink's offload support
         * capabilities for a given [Format] and [AudioAttributes] for calls to [ ][.getFormatOffloadSupport].
         *
         *
         * If this setter is not called, then the [DefaultAudioSink] uses an instance of [ ].
         */
        fun setAudioOffloadSupportProvider(
            audioOffloadSupportProvider: DefaultAudioSink.AudioOffloadSupportProvider?
        ): Builder {
            this.audioOffloadSupportProvider = audioOffloadSupportProvider
            return this
        }

        /**
         * Sets an optional [AudioOffloadListener] to receive events relevant to offloaded
         * playback.
         *
         *
         * The default value is null.
         */
        fun setExperimentalAudioOffloadListener(
            audioOffloadListener: ExoPlayer.AudioOffloadListener?
        ): Builder {
            this.audioOffloadListener = audioOffloadListener
            return this
        }

        /** Builds the [LowLatencyAudioSink]. Must only be called once per Builder instance.  */
        fun build(): LowLatencyAudioSink {
            Assertions.checkState(!buildCalled)
            buildCalled = true
            if (audioProcessorChain == null) {
                audioProcessorChain = DefaultAudioProcessorChain()
            }
            if (audioOffloadSupportProvider == null) {
                audioOffloadSupportProvider = DefaultAudioOffloadSupportProvider(context)
            }
            return LowLatencyAudioSink(this)
        }
    }

    /**
     * Whether to throw an [InvalidAudioTrackTimestampException] when a spurious timestamp is
     * reported from [AudioTrack.getTimestamp].
     *
     *
     * The flag must be set before creating a player. Should be set to `true` for testing and
     * debugging purposes only.
     */
    var failOnSpuriousAudioTimestamp: Boolean = false
    private val releaseExecutorLock: Any = Any()

    @GuardedBy("releaseExecutorLock")
    private var releaseExecutor: ExecutorService? = null

    @GuardedBy("releaseExecutorLock")
    private var pendingReleaseCount: Int = 0

    private var context: Context? = null
    private var audioProcessorChain: androidx.media3.common.audio.AudioProcessorChain? = null
    private var enableFloatOutput = false
    private var channelMappingAudioProcessor: ChannelMappingAudioProcessor? = null
    private var trimmingAudioProcessor: TrimmingAudioProcessor? = null
    private var toIntPcmAvailableAudioProcessors: ImmutableList<AudioProcessor>? = null
    private var toFloatPcmAvailableAudioProcessors: ImmutableList<AudioProcessor>? = null
    private var releasingConditionVariable: ConditionVariable? = null
    private var audioTrackPositionTracker: AudioTrackPositionTracker? = null
    private var mediaPositionParametersCheckpoints: ArrayDeque<MediaPositionParameters>? = null
    private var preferAudioTrackPlaybackParams = false
    private var offloadMode: @AudioSink.OffloadMode Int = 0
    private var offloadStreamEventCallbackV29: StreamEventCallbackV29? = null
    private var initializationExceptionPendingExceptionHolder: PendingExceptionHolder<AudioSink.InitializationException>? =
        null
    private var writeExceptionPendingExceptionHolder: PendingExceptionHolder<AudioSink.WriteException>? = null
    private var audioOffloadSupportProvider: AudioOffloadSupportProvider? = null
    private var audioOffloadListener: ExoPlayer.AudioOffloadListener? = null

    private var playerId: PlayerId? = null
    private var listener: AudioSink.Listener? = null
    private var pendingConfiguration: Configuration? = null
    private var configuration: Configuration? = null
    private var audioProcessingPipeline: AudioProcessingPipeline? = null
    private var audioTrack: AudioTrack? = null
    private var audioCapabilities: AudioCapabilities? = null
    private var audioCapabilitiesReceiver: AudioCapabilitiesReceiver? = null
    private var onRoutingChangedListener: OnRoutingChangedListenerApi24? = null

    private var audioAttributes: AudioAttributes
    private var afterDrainParameters: MediaPositionParameters? = null
    private var mediaPositionParameters: MediaPositionParameters? = null
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false

    private var avSyncHeader: ByteBuffer? = null
    private var bytesUntilNextAvSync = 0

    private var submittedPcmBytes: Long = 0
    private var submittedEncodedFrames: Long = 0
    private var writtenPcmBytes: Long = 0
    private var writtenEncodedFrames: Long = 0
    private var framesPerEncodedSample = 0
    private var startMediaTimeUsNeedsSync = false
    private var startMediaTimeUsNeedsInit = false
    private var startMediaTimeUs: Long = 0
    private var volume = 0f

    private var inputBuffer: ByteBuffer? = null
    private var inputBufferAccessUnitCount = 0
    private var outputBuffer: ByteBuffer? = null
    private var preV21OutputBuffer: ByteArray? = null
    private var preV21OutputBufferOffset = 0
    private var handledEndOfStream = false
    private var stoppedAudioTrack = false
    private var handledOffloadOnPresentationEnded = false

    private var playing = false
    private var externalAudioSessionIdProvided = false
    private var audioSessionId = 0
    private var auxEffectInfo: AuxEffectInfo? = null
    private var preferredDevice: AudioDeviceInfoApi23? = null
    private var tunneling = false
    private var lastTunnelingAvSyncPresentationTimeUs: Long = 0
    private var lastFeedElapsedRealtimeMs: Long = 0
    private var offloadDisabledUntilNextConfiguration = false
    private var isWaitingForOffloadEndOfStreamHandled = false
    private var playbackLooper: Looper? = null
    private var skippedOutputFrameCountAtLastPosition: Long = 0
    private var accumulatedSkippedSilenceDurationUs: Long = 0
    private var reportSkippedSilenceHandler: Handler? = null

    init {
        context = builder.context
        audioAttributes = AudioAttributes.DEFAULT
        audioCapabilities = context?.let {
            AudioCapabilities.getCapabilities(
                it,
                audioAttributes!!,  /* routedDevice= */null
            )
        } ?: builder.audioCapabilities
        audioProcessorChain = builder.audioProcessorChain
        enableFloatOutput = builder.enableFloatOutput
        preferAudioTrackPlaybackParams = builder.enableAudioTrackPlaybackParams
        offloadMode = AudioSink.OFFLOAD_MODE_DISABLED
        audioOffloadSupportProvider = Assertions.checkNotNull(builder.audioOffloadSupportProvider)
        releasingConditionVariable = ConditionVariable(Clock.DEFAULT)
        releasingConditionVariable!!.open()
        audioTrackPositionTracker = AudioTrackPositionTracker(PositionTrackerListener())
        channelMappingAudioProcessor = ChannelMappingAudioProcessor()
        trimmingAudioProcessor = TrimmingAudioProcessor()
        toIntPcmAvailableAudioProcessors =
            ImmutableList.of(
                ToInt16PcmAudioProcessor(), channelMappingAudioProcessor, trimmingAudioProcessor
            )
        toFloatPcmAvailableAudioProcessors = ImmutableList.of(ToFloatPcmAudioProcessor())
        volume = 1f
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
        auxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
        mediaPositionParameters =
            MediaPositionParameters(
                PlaybackParameters.DEFAULT,  /* mediaTimeUs= */0,  /* audioTrackPositionUs= */0
            )
        playbackParameters = PlaybackParameters.DEFAULT
        skipSilenceEnabled = DEFAULT_SKIP_SILENCE
        mediaPositionParametersCheckpoints = ArrayDeque()
        initializationExceptionPendingExceptionHolder =
            PendingExceptionHolder(AUDIO_TRACK_RETRY_DURATION_MS.toLong())
        writeExceptionPendingExceptionHolder =
            PendingExceptionHolder(AUDIO_TRACK_RETRY_DURATION_MS.toLong())
        audioOffloadListener = builder.audioOffloadListener
    }


    // AudioSink implementation.
    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setPlayerId(playerId: PlayerId?) {
        this.playerId = playerId
    }

    override fun setClock(clock: Clock) {
        audioTrackPositionTracker!!.setClock(clock!!)
    }

    override fun supportsFormat(format: Format): Boolean {
        return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getFormatSupport(format: Format): @AudioSink.SinkFormatSupport Int {
        maybeStartAudioCapabilitiesReceiver()
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType) {
            if (!Util.isEncodingLinearPcm(format.pcmEncoding)) {
                Log.w(TAG, "Invalid PCM encoding: " + format.pcmEncoding)
                return AudioSink.SINK_FORMAT_UNSUPPORTED
            }
            if (format.pcmEncoding == C.ENCODING_PCM_16BIT
                || (enableFloatOutput && format.pcmEncoding == C.ENCODING_PCM_FLOAT)
            ) {
                return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            }
            // We can resample all linear PCM encodings to 16-bit integer PCM, which AudioTrack is
            // guaranteed to support.
            return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
        if (audioCapabilities!!.isPassthroughPlaybackSupported(format, audioAttributes!!)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (offloadDisabledUntilNextConfiguration) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return audioOffloadSupportProvider!!.getAudioOffloadSupport(format, audioAttributes)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!isAudioTrackInitialized() || startMediaTimeUsNeedsInit) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        var positionUs = audioTrackPositionTracker!!.getCurrentPositionUs(sourceEnded)
        positionUs = min(
            positionUs.toDouble(),
            configuration!!.framesToDurationUs(getWrittenFrames()).toDouble()
        )
            .toLong()
        return applySkipping(applyMediaPositionParameters(positionUs))
    }

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        var outputChannels = outputChannels
        var audioProcessingPipeline: AudioProcessingPipeline
        val inputPcmFrameSize: Int
        val outputMode: OutputMode
        val outputEncoding: @C.Encoding Int
        val outputSampleRate: Int
        val outputChannelConfig: Int
        val outputPcmFrameSize: Int
        val enableAudioTrackPlaybackParams: Boolean
        var enableOffloadGapless = false

        maybeStartAudioCapabilitiesReceiver()
        if (MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType) {
            Assertions.checkArgument(Util.isEncodingLinearPcm(inputFormat.pcmEncoding))

            inputPcmFrameSize =
                Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount)

            val pipelineProcessors = ImmutableList.Builder<AudioProcessor>()
            if (shouldUseFloatOutput(inputFormat.pcmEncoding)) {
                pipelineProcessors.addAll(toFloatPcmAvailableAudioProcessors)
            } else {
                pipelineProcessors.addAll(toIntPcmAvailableAudioProcessors)
                pipelineProcessors.add(*audioProcessorChain!!.audioProcessors)
            }
            audioProcessingPipeline = AudioProcessingPipeline(pipelineProcessors.build())

            // If the underlying processors of the new pipeline are the same as the existing pipeline,
            // then use the existing one when the configuration is used.
            if (audioProcessingPipeline == this.audioProcessingPipeline) {
                audioProcessingPipeline = this.audioProcessingPipeline!!
            }

            trimmingAudioProcessor!!.setTrimFrameCount(
                inputFormat.encoderDelay, inputFormat.encoderPadding
            )

            channelMappingAudioProcessor!!.setChannelMap(outputChannels)

            var outputFormat = AudioProcessor.AudioFormat(inputFormat)
            try {
                outputFormat = audioProcessingPipeline.configure(outputFormat)
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                throw AudioSink.ConfigurationException(e, inputFormat)
            }

            outputMode = OutputMode.OUTPUT_MODE_PCM
            outputEncoding = outputFormat.encoding
            outputSampleRate = outputFormat.sampleRate
            outputChannelConfig = Util.getAudioTrackChannelConfig(outputFormat.channelCount)
            outputPcmFrameSize = Util.getPcmFrameSize(outputEncoding, outputFormat.channelCount)
            enableAudioTrackPlaybackParams = preferAudioTrackPlaybackParams
        } else {
            // Audio processing is not supported in offload or passthrough mode.
            audioProcessingPipeline = AudioProcessingPipeline(ImmutableList.of())
            inputPcmFrameSize = C.LENGTH_UNSET
            outputSampleRate = inputFormat.sampleRate
            outputPcmFrameSize = C.LENGTH_UNSET
            val audioOffloadSupport =
                if (offloadMode != AudioSink.OFFLOAD_MODE_DISABLED
                ) getFormatOffloadSupport(inputFormat)
                else AudioOffloadSupport.DEFAULT_UNSUPPORTED
            if (offloadMode != AudioSink.OFFLOAD_MODE_DISABLED && audioOffloadSupport.isFormatSupported) {
                outputMode = OutputMode.OUTPUT_MODE_OFFLOAD
                outputEncoding =
                    MimeTypes.getEncoding(
                        Assertions.checkNotNull(inputFormat.sampleMimeType),
                        inputFormat.codecs
                    )
                outputChannelConfig = Util.getAudioTrackChannelConfig(inputFormat.channelCount)
                // Offload requires AudioTrack playback parameters to apply speed changes quickly.
                enableAudioTrackPlaybackParams = true
                enableOffloadGapless = audioOffloadSupport.isGaplessSupported
            } else {
                outputMode = OutputMode.OUTPUT_MODE_PASSTHROUGH
                val encodingAndChannelConfig =
                    audioCapabilities!!.getEncodingAndChannelConfigForPassthrough(
                        inputFormat, audioAttributes!!
                    )
                if (encodingAndChannelConfig == null) {
                    throw AudioSink.ConfigurationException(
                        "Unable to configure passthrough for: $inputFormat", inputFormat
                    )
                }
                outputEncoding = encodingAndChannelConfig.first
                outputChannelConfig = encodingAndChannelConfig.second
                // Passthrough only supports AudioTrack playback parameters, but we only enable it this was
                // specifically requested by the app.
                enableAudioTrackPlaybackParams = preferAudioTrackPlaybackParams
            }
        }

        if (outputEncoding == C.ENCODING_INVALID) {
            throw AudioSink.ConfigurationException(
                "Invalid output encoding (mode=$outputMode) for: $inputFormat", inputFormat
            )
        }
        if (outputChannelConfig == AudioFormat.CHANNEL_INVALID) {
            throw AudioSink.ConfigurationException(
                "Invalid output channel config (mode=$outputMode) for: $inputFormat",
                inputFormat
            )
        }

        // Replace unknown bitrate by maximum allowed bitrate for DTS Express to avoid allocating an
        // AudioTrack buffer for the much larger maximum bitrate of the underlying DTS-HD encoding.
        var bitrate = inputFormat.bitrate
        if (MimeTypes.AUDIO_DTS_EXPRESS == inputFormat.sampleMimeType && bitrate == Format.NO_VALUE) {
            bitrate = DtsUtil.DTS_EXPRESS_MAX_RATE_BITS_PER_SECOND
        }

        val bufferSize =
            if (specifiedBufferSize != 0)
                specifiedBufferSize
            else 512
        offloadDisabledUntilNextConfiguration = false
        val pendingConfiguration =
            Configuration(
                inputFormat,
                inputPcmFrameSize,
                outputMode,
                outputPcmFrameSize,
                outputSampleRate,
                outputChannelConfig,
                outputEncoding,
                bufferSize,
                audioProcessingPipeline,
                enableAudioTrackPlaybackParams,
                enableOffloadGapless,
                tunneling
            )
        if (isAudioTrackInitialized()) {
            this.pendingConfiguration = pendingConfiguration
        } else {
            configuration = pendingConfiguration
        }
    }

    private fun setupAudioProcessors() {
        audioProcessingPipeline = configuration!!.audioProcessingPipeline
        audioProcessingPipeline!!.flush()
    }

    @Throws(AudioSink.InitializationException::class)
    private fun initializeAudioTrack(): Boolean {
        // If we're asynchronously releasing a previous audio track then we wait until it has been
        // released. This guarantees that we cannot end up in a state where we have multiple audio
        // track instances. Without this guarantee it would be possible, in extreme cases, to exhaust
        // the shared memory that's available for audio track buffers. This would in turn cause the
        // initialization of the audio track to fail.
        if (!releasingConditionVariable!!.isOpen) {
            return false
        }

        audioTrack = buildAudioTrackWithRetry()
        if (isOffloadedPlayback(audioTrack)) {
            registerStreamEventCallbackV29(audioTrack)
            if (configuration!!.enableOffloadGapless) {
                audioTrack!!.setOffloadDelayPadding(
                    configuration!!.inputFormat.encoderDelay,
                    configuration!!.inputFormat.encoderPadding
                )
            }
        }
        if (Util.SDK_INT >= 31 && playerId != null) {
            Api31.setLogSessionIdOnAudioTrack(audioTrack, playerId!!)
        }
        audioSessionId = audioTrack!!.audioSessionId
        audioTrackPositionTracker!!.setAudioTrack(
            audioTrack!!,  /* isPassthrough= */
            configuration!!.outputMode == OutputMode.OUTPUT_MODE_PASSTHROUGH,
            configuration!!.outputEncoding,
            configuration!!.outputPcmFrameSize,
            configuration!!.bufferSize
        )
        setVolumeInternal()

        if (auxEffectInfo!!.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
            audioTrack!!.attachAuxEffect(auxEffectInfo!!.effectId)
            audioTrack!!.setAuxEffectSendLevel(auxEffectInfo!!.sendLevel)
        }
        if (preferredDevice != null) {
            Api23.setPreferredDeviceOnAudioTrack(audioTrack!!, preferredDevice)
            if (audioCapabilitiesReceiver != null) {
                audioCapabilitiesReceiver!!.setRoutedDevice(preferredDevice!!.audioDeviceInfo)
            }
        }
        if (Util.SDK_INT >= 24 && audioCapabilitiesReceiver != null) {
            onRoutingChangedListener =
                OnRoutingChangedListenerApi24(audioTrack, audioCapabilitiesReceiver!!)
        }
        startMediaTimeUsNeedsInit = true

        if (listener != null) {
            listener!!.onAudioTrackInitialized(configuration!!.buildAudioTrackConfig())
        }

        return true
    }

    override fun play() {
        playing = true
        if (isAudioTrackInitialized()) {
            audioTrackPositionTracker!!.start()
            audioTrack!!.play()
        }
    }

    override fun handleDiscontinuity() {
        startMediaTimeUsNeedsSync = true
    }

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(
        buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int
    ): Boolean {
        Assertions.checkArgument(inputBuffer == null || buffer === inputBuffer)

        if (pendingConfiguration != null) {
            if (!drainToEndOfStream()) {
                // There's still pending data in audio processors to write to the track.
                return false
            } else if (!pendingConfiguration!!.canReuseAudioTrack(configuration)) {
                playPendingData()
                if (hasPendingData()) {
                    // We're waiting for playout on the current audio track to finish.
                    return false
                }
                flush()
            } else {
                // The current audio track can be reused for the new configuration.
                configuration = pendingConfiguration
                pendingConfiguration = null
                if (audioTrack != null && isOffloadedPlayback(audioTrack)
                    && configuration!!.enableOffloadGapless
                ) {
                    // If the first track is very short (typically <1s), the offload AudioTrack might
                    // not have started yet. Do not call setOffloadEndOfStream as it would throw.
                    if (audioTrack!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack!!.setOffloadEndOfStream()
                        audioTrackPositionTracker!!.expectRawPlaybackHeadReset()
                    }
                    audioTrack!!.setOffloadDelayPadding(
                        configuration!!.inputFormat.encoderDelay,
                        configuration!!.inputFormat.encoderPadding
                    )
                    isWaitingForOffloadEndOfStreamHandled = true
                }
            }
            // Re-apply playback parameters.
            applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
        }

        if (!isAudioTrackInitialized()) {
            try {
                if (!initializeAudioTrack()) {
                    // Not yet ready for initialization of a new AudioTrack.
                    return false
                }
            } catch (e: AudioSink.InitializationException) {
                if (e.isRecoverable) {
                    throw e // Do not delay the exception if it can be recovered at higher level.
                }
                initializationExceptionPendingExceptionHolder!!.throwExceptionIfDeadlineIsReached(e)
                return false
            }
        }
        initializationExceptionPendingExceptionHolder!!.clear()

        if (startMediaTimeUsNeedsInit) {
            startMediaTimeUs = max(0.0, presentationTimeUs.toDouble()).toLong()
            startMediaTimeUsNeedsSync = false
            startMediaTimeUsNeedsInit = false

            if (useAudioTrackPlaybackParams()) {
                setAudioTrackPlaybackParametersV23()
            }
            applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)

            if (playing) {
                play()
            }
        }

        if (!audioTrackPositionTracker!!.mayHandleBuffer(getWrittenFrames())) {
            return false
        }

        if (inputBuffer == null) {
            // We are seeing this buffer for the first time.
            Assertions.checkArgument(buffer.order() == ByteOrder.LITTLE_ENDIAN)
            if (!buffer.hasRemaining()) {
                // The buffer is empty.
                return true
            }

            if (configuration!!.outputMode != OutputMode.OUTPUT_MODE_PCM && framesPerEncodedSample == 0) {
                // If this is the first encoded sample, calculate the sample size in frames.
                framesPerEncodedSample =
                    getFramesPerEncodedSample(configuration!!.outputEncoding, buffer)
                if (framesPerEncodedSample == 0) {
                    // We still don't know the number of frames per sample, so drop the buffer.
                    // For TrueHD this can occur after some seek operations, as not every sample starts with
                    // a syncframe header. If we chunked samples together so the extracted samples always
                    // started with a syncframe header, the chunks would be too large.
                    return true
                }
            }

            if (afterDrainParameters != null) {
                if (!drainToEndOfStream()) {
                    // Don't process any more input until draining completes.
                    return false
                }
                applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
                afterDrainParameters = null
            }

            // Check that presentationTimeUs is consistent with the expected value.
            val expectedPresentationTimeUs = (
                    startMediaTimeUs
                            + configuration!!.inputFramesToDurationUs(
                        getSubmittedFrames() - trimmingAudioProcessor!!.trimmedFrameCount
                    ))
            if (!startMediaTimeUsNeedsSync
                && abs((expectedPresentationTimeUs - presentationTimeUs).toDouble()) > 200000
            ) {
                if (listener != null) {
                    listener!!.onAudioSinkError(
                        AudioSink.UnexpectedDiscontinuityException(
                            presentationTimeUs, expectedPresentationTimeUs
                        )
                    )
                }
                startMediaTimeUsNeedsSync = true
            }
            if (startMediaTimeUsNeedsSync) {
                if (!drainToEndOfStream()) {
                    // Don't update timing until pending AudioProcessor buffers are completely drained.
                    return false
                }
                // Adjust startMediaTimeUs to be consistent with the current buffer's start time and the
                // number of bytes submitted.
                val adjustmentUs = presentationTimeUs - expectedPresentationTimeUs
                startMediaTimeUs += adjustmentUs
                startMediaTimeUsNeedsSync = false
                // Re-apply playback parameters because the startMediaTimeUs changed.
                applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
                if (listener != null && adjustmentUs != 0L) {
                    listener!!.onPositionDiscontinuity()
                }
            }

            if (configuration!!.outputMode == OutputMode.OUTPUT_MODE_PCM) {
                submittedPcmBytes += buffer.remaining().toLong()
            } else {
                submittedEncodedFrames += framesPerEncodedSample.toLong() * encodedAccessUnitCount
            }

            inputBuffer = buffer
            inputBufferAccessUnitCount = encodedAccessUnitCount
        }

        processBuffers(presentationTimeUs)

        if (!inputBuffer!!.hasRemaining()) {
            inputBuffer = null
            inputBufferAccessUnitCount = 0
            return true
        }

        if (audioTrackPositionTracker!!.isStalled(getWrittenFrames())) {
            Log.w(TAG, "Resetting stalled audio track")
            flush()
            return true
        }

        return false
    }

    @Throws(AudioSink.InitializationException::class)
    private fun buildAudioTrackWithRetry(): AudioTrack {
        try {
            return buildAudioTrack(Assertions.checkNotNull(configuration))
        } catch (initialFailure: AudioSink.InitializationException) {
            maybeDisableOffload()
            throw initialFailure
        }
    }

    @Throws(AudioSink.InitializationException::class)
    private fun buildAudioTrack(configuration: Configuration): AudioTrack {
        try {
            val audioTrack = configuration.buildAudioTrack(audioAttributes, audioSessionId)
            audioOffloadListener?.onOffloadedPlayback(isOffloadedPlayback(audioTrack))
            return audioTrack
        } catch (e: AudioSink.InitializationException) {
            if (listener != null) {
                listener!!.onAudioSinkError(e)
            }
            throw e
        }
    }

    @RequiresApi(29)
    private fun registerStreamEventCallbackV29(audioTrack: AudioTrack?) {
        if (offloadStreamEventCallbackV29 == null) {
            // Must be lazily initialized to receive stream event callbacks on the current (playback)
            // thread as the constructor is not called in the playback thread.
            offloadStreamEventCallbackV29 = StreamEventCallbackV29()
        }
        offloadStreamEventCallbackV29!!.register(audioTrack)
    }

    /**
     * Repeatedly drains and feeds the [AudioProcessingPipeline] until [ ][.writeBuffer] is not accepting any more input or there is no more input to
     * feed into the pipeline.
     *
     *
     * If the [AudioProcessingPipeline] is not [ ][AudioProcessingPipeline.isOperational], input buffers are passed straight to
     * [.writeBuffer].
     *
     * @param avSyncPresentationTimeUs The tunneling AV sync presentation time for the current buffer,
     * or [C.TIME_END_OF_SOURCE] when draining remaining buffers at the end of the stream.
     */
    @Throws(AudioSink.WriteException::class)
    private fun processBuffers(avSyncPresentationTimeUs: Long) {
        if (!audioProcessingPipeline!!.isOperational) {
            writeBuffer(
                (if (inputBuffer != null) inputBuffer else AudioProcessor.EMPTY_BUFFER)!!,
                avSyncPresentationTimeUs
            )
            return
        }

        while (!audioProcessingPipeline!!.isEnded) {
            var bufferToWrite: ByteBuffer
            while (audioProcessingPipeline!!.output.also { bufferToWrite = it }.hasRemaining()) {
                writeBuffer(bufferToWrite, avSyncPresentationTimeUs)
                if (bufferToWrite.hasRemaining()) {
                    // writeBuffer method is providing back pressure.
                    return
                }
            }
            if (inputBuffer == null || !inputBuffer!!.hasRemaining()) {
                return
            }
            audioProcessingPipeline!!.queueInput(inputBuffer!!)
        }
    }

    /**
     * Queues end of stream and then fully drains all buffers.
     *
     * @return Whether the buffers have been fully drained.
     */
    @Throws(AudioSink.WriteException::class)
    private fun drainToEndOfStream(): Boolean {
        if (!audioProcessingPipeline!!.isOperational) {
            if (outputBuffer == null) {
                return true
            }
            writeBuffer(outputBuffer!!, C.TIME_END_OF_SOURCE)
            return outputBuffer == null
        }

        audioProcessingPipeline!!.queueEndOfStream()
        processBuffers(C.TIME_END_OF_SOURCE)
        return (audioProcessingPipeline!!.isEnded
                && (outputBuffer == null || !outputBuffer!!.hasRemaining()))
    }

    /**
     * Writes the provided buffer to the audio track.
     *
     * @param buffer The buffer to write.
     * @param avSyncPresentationTimeUs The tunneling AV sync presentation time for the buffer, or
     * [C.TIME_END_OF_SOURCE] when draining remaining buffers at the end of the stream.
     */
    @Throws(AudioSink.WriteException::class)
    private fun writeBuffer(buffer: ByteBuffer, avSyncPresentationTimeUs: Long) {
        var avSyncPresentationTimeUs = avSyncPresentationTimeUs
        if (!buffer.hasRemaining()) {
            return
        }
        if (outputBuffer != null) {
            Assertions.checkArgument(outputBuffer === buffer)
        } else {
            outputBuffer = buffer
            if (Util.SDK_INT < 21) {
                val bytesRemaining = buffer.remaining()
                if (preV21OutputBuffer == null || preV21OutputBuffer!!.size < bytesRemaining) {
                    preV21OutputBuffer = ByteArray(bytesRemaining)
                }
                val originalPosition = buffer.position()
                buffer[preV21OutputBuffer, 0, bytesRemaining]
                buffer.position(originalPosition)
                preV21OutputBufferOffset = 0
            }
        }
        val bytesRemaining = buffer.remaining()
        var bytesWrittenOrError = 0 // Error if negative
        if (Util.SDK_INT < 21) { // outputMode == OUTPUT_MODE_PCM.
            // Work out how many bytes we can write without the risk of blocking.
            var bytesToWrite = audioTrackPositionTracker!!.getAvailableBufferSize(writtenPcmBytes)
            if (bytesToWrite > 0) {
                bytesToWrite = min(bytesRemaining.toDouble(), bytesToWrite.toDouble()).toInt()
                bytesWrittenOrError =
                    audioTrack!!.write(preV21OutputBuffer!!, preV21OutputBufferOffset, bytesToWrite)
                if (bytesWrittenOrError > 0) { // No error
                    preV21OutputBufferOffset += bytesWrittenOrError
                    buffer.position(buffer.position() + bytesWrittenOrError)
                }
            }
        } else if (tunneling) {
            Assertions.checkState(avSyncPresentationTimeUs != C.TIME_UNSET)
            if (avSyncPresentationTimeUs == C.TIME_END_OF_SOURCE) {
                // Audio processors during tunneling are required to produce buffers immediately when
                // queuing, so we can assume the timestamp during draining at the end of the stream is the
                // same as the timestamp of the last sample we processed.
                avSyncPresentationTimeUs = lastTunnelingAvSyncPresentationTimeUs
            } else {
                lastTunnelingAvSyncPresentationTimeUs = avSyncPresentationTimeUs
            }
            bytesWrittenOrError =
                writeNonBlockingWithAvSyncV21(
                    audioTrack, buffer, bytesRemaining, avSyncPresentationTimeUs
                )
        } else {
            bytesWrittenOrError = writeNonBlockingV21(audioTrack, buffer, bytesRemaining)
        }

        lastFeedElapsedRealtimeMs = SystemClock.elapsedRealtime()

        if (bytesWrittenOrError < 0) {
            val error = bytesWrittenOrError

            // Treat a write error on a previously successful offload channel as recoverable
            // without disabling offload. Offload will be disabled if offload channel was not successfully
            // written to or when a new AudioTrack is created, if no longer supported.
            var isRecoverable = false
            if (isAudioTrackDeadObject(error)) {
                if (getWrittenFrames() > 0) {
                    isRecoverable = true
                } else if (isOffloadedPlayback(audioTrack)) {
                    maybeDisableOffload()
                    isRecoverable = true
                }
            }

            val e = AudioSink.WriteException(error, configuration!!.inputFormat, isRecoverable)
            if (listener != null) {
                listener!!.onAudioSinkError(e)
            }
            if (e.isRecoverable) {
                // Change to the audio capabilities supported by all the devices during the error recovery.
                audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                throw e // Do not delay the exception if it can be recovered at higher level.
            }
            writeExceptionPendingExceptionHolder!!.throwExceptionIfDeadlineIsReached(e)
            return
        }
        writeExceptionPendingExceptionHolder!!.clear()

        val bytesWritten = bytesWrittenOrError

        if (isOffloadedPlayback(audioTrack)) {
            // After calling AudioTrack.setOffloadEndOfStream, the AudioTrack internally stops and
            // restarts during which AudioTrack.write will return 0. This situation must be detected to
            // prevent reporting the buffer as full even though it is not which could lead ExoPlayer to
            // sleep forever waiting for a onDataRequest that will never come.
            if (writtenEncodedFrames > 0) {
                isWaitingForOffloadEndOfStreamHandled = false
            }

            // Consider the offload buffer as full if the AudioTrack is playing and AudioTrack.write could
            // not write all the data provided to it. This relies on the assumption that AudioTrack.write
            // always writes as much as possible.
            if (playing && listener != null && bytesWritten < bytesRemaining && !isWaitingForOffloadEndOfStreamHandled) {
                listener!!.onOffloadBufferFull()
            }
        }

        if (configuration!!.outputMode == OutputMode.OUTPUT_MODE_PCM) {
            writtenPcmBytes += bytesWritten.toLong()
        }
        if (bytesWritten == bytesRemaining) {
            if (configuration!!.outputMode != OutputMode.OUTPUT_MODE_PCM) {
                // When playing non-PCM, the inputBuffer is never processed, thus the last inputBuffer
                // must be the current input buffer.
                Assertions.checkState(buffer === inputBuffer)
                writtenEncodedFrames += framesPerEncodedSample.toLong() * inputBufferAccessUnitCount
            }
            outputBuffer = null
        }
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() {
        if (!handledEndOfStream && isAudioTrackInitialized() && drainToEndOfStream()) {
            playPendingData()
            handledEndOfStream = true
        }
    }

    private fun maybeDisableOffload() {
        if (!configuration!!.outputModeIsOffload()) {
            return
        }
        // Offload was requested, but may not be available. There are cases when this can occur even if
        // AudioManager.isOffloadedPlaybackSupported returned true. For example, due to use of an
        // AudioPlaybackCaptureConfiguration. Disable offload until the sink is next configured.
        offloadDisabledUntilNextConfiguration = true
    }

    private fun isAudioTrackDeadObject(status: Int): Boolean {
        return ((Util.SDK_INT >= 24 && status == AudioTrack.ERROR_DEAD_OBJECT)
                || status == ERROR_NATIVE_DEAD_OBJECT)
    }

    override fun isEnded(): Boolean {
        return !isAudioTrackInitialized() || (handledEndOfStream && !hasPendingData())
    }

    override fun hasPendingData(): Boolean {
        return (isAudioTrackInitialized()
                && (Util.SDK_INT < 29 || !audioTrack!!.isOffloadedPlayback
                || !handledOffloadOnPresentationEnded)
                && audioTrackPositionTracker!!.hasPendingData(getWrittenFrames()))
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters =
            PlaybackParameters(
                Util.constrainValue(
                    playbackParameters.speed,
                    MIN_PLAYBACK_SPEED,
                    MAX_PLAYBACK_SPEED
                ),
                Util.constrainValue(playbackParameters.pitch, MIN_PITCH, MAX_PITCH)
            )
        if (useAudioTrackPlaybackParams()) {
            setAudioTrackPlaybackParametersV23()
        } else {
            setAudioProcessorPlaybackParameters(playbackParameters)
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
        // Skip silence is applied together with the AudioProcessor playback parameters after draining
        // the pipeline. Force a drain by re-applying the current playback parameters.
        setAudioProcessorPlaybackParameters(
            (if (useAudioTrackPlaybackParams()) PlaybackParameters.DEFAULT else playbackParameters)!!
        )
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        if (this.audioAttributes == audioAttributes) {
            return
        }
        this.audioAttributes = audioAttributes
        if (tunneling) {
            // The audio attributes are ignored in tunneling mode, so no need to reset.
            return
        }
        if (audioCapabilitiesReceiver != null) {
            audioCapabilitiesReceiver!!.setAudioAttributes(audioAttributes)
        }
        flush()
    }

    override fun getAudioAttributes(): AudioAttributes? {
        return audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        if (this.audioSessionId != audioSessionId) {
            this.audioSessionId = audioSessionId
            externalAudioSessionIdProvided = audioSessionId != C.AUDIO_SESSION_ID_UNSET
            flush()
        }
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        if (this.auxEffectInfo == auxEffectInfo) {
            return
        }
        val effectId = auxEffectInfo.effectId
        val sendLevel = auxEffectInfo.sendLevel
        if (audioTrack != null) {
            if (this.auxEffectInfo!!.effectId != effectId) {
                audioTrack!!.attachAuxEffect(effectId)
            }
            if (effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
                audioTrack!!.setAuxEffectSendLevel(sendLevel)
            }
        }
        this.auxEffectInfo = auxEffectInfo
    }

    @RequiresApi(23)
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        this.preferredDevice =
            if (audioDeviceInfo == null) null else AudioDeviceInfoApi23(audioDeviceInfo)
        if (audioCapabilitiesReceiver != null) {
            audioCapabilitiesReceiver!!.setRoutedDevice(audioDeviceInfo)
        }
        if (audioTrack != null) {
            Api23.setPreferredDeviceOnAudioTrack(audioTrack!!, this.preferredDevice)
        }
    }

    override fun enableTunnelingV21() {
        Assertions.checkState(externalAudioSessionIdProvided)
        if (!tunneling) {
            tunneling = true
            flush()
        }
    }

    override fun disableTunneling() {
        if (tunneling) {
            tunneling = false
            flush()
        }
    }

    @RequiresApi(29)
    override fun setOffloadMode(offloadMode: @AudioSink.OffloadMode Int) {
        Assertions.checkState(Util.SDK_INT >= 29)
        this.offloadMode = offloadMode
    }

    @RequiresApi(29)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        if (audioTrack != null && isOffloadedPlayback(audioTrack) && configuration != null && configuration!!.enableOffloadGapless) {
            audioTrack!!.setOffloadDelayPadding(delayInFrames, paddingInFrames)
        }
    }

    override fun setVolume(volume: Float) {
        if (this.volume != volume) {
            this.volume = volume
            setVolumeInternal()
        }
    }

    private fun setVolumeInternal() {
        if (!isAudioTrackInitialized()) {
            // Do nothing.
        } else setVolumeInternalV21(audioTrack, volume)
    }

    override fun pause() {
        playing = false
        if (isAudioTrackInitialized()
            && (audioTrackPositionTracker!!.pause() || isOffloadedPlayback(audioTrack))
        ) {
            audioTrack!!.pause()
        }
    }

    override fun flush() {
        if (isAudioTrackInitialized()) {
            resetSinkStateForFlush()

            if (audioTrackPositionTracker!!.isPlaying) {
                audioTrack!!.pause()
            }
            if (isOffloadedPlayback(audioTrack)) {
                Assertions.checkNotNull(offloadStreamEventCallbackV29).unregister(audioTrack)
            }
            if (Util.SDK_INT < 21 && !externalAudioSessionIdProvided) {
                // Prior to API level 21, audio sessions are not kept alive once there are no components
                // associated with them. If we generated the session ID internally, the only component
                // associated with the session is the audio track that's being released, and therefore
                // the session will not be kept alive. As a result, we need to generate a new session when
                // we next create an audio track.
                audioSessionId = C.AUDIO_SESSION_ID_UNSET
            }
            val oldAudioTrackConfig = configuration!!.buildAudioTrackConfig()
            if (pendingConfiguration != null) {
                configuration = pendingConfiguration
                pendingConfiguration = null
            }
            audioTrackPositionTracker!!.reset()
            if (Util.SDK_INT >= 24 && onRoutingChangedListener != null) {
                onRoutingChangedListener!!.release()
                onRoutingChangedListener = null
            }
            releaseAudioTrackAsync(
                audioTrack,
                releasingConditionVariable,
                listener,
                oldAudioTrackConfig
            )
            audioTrack = null
        }
        writeExceptionPendingExceptionHolder!!.clear()
        initializationExceptionPendingExceptionHolder!!.clear()
        skippedOutputFrameCountAtLastPosition = 0
        accumulatedSkippedSilenceDurationUs = 0
        reportSkippedSilenceHandler?.removeCallbacksAndMessages(null)
    }

    override fun reset() {
        flush()
        for (audioProcessor: AudioProcessor in toIntPcmAvailableAudioProcessors!!) {
            audioProcessor.reset()
        }
        for (audioProcessor: AudioProcessor in toFloatPcmAvailableAudioProcessors!!) {
            audioProcessor.reset()
        }
        if (audioProcessingPipeline != null) {
            audioProcessingPipeline!!.reset()
        }
        playing = false
        offloadDisabledUntilNextConfiguration = false
    }

    override fun release() {
        if (audioCapabilitiesReceiver != null) {
            audioCapabilitiesReceiver!!.unregister()
        }
    }


    // AudioCapabilitiesReceiver.Listener implementation.
    fun onAudioCapabilitiesChanged(audioCapabilities: AudioCapabilities) {
        val myLooper = Looper.myLooper()
        if (playbackLooper != myLooper) {
            val playbackLooperName =
                if (playbackLooper == null) "null" else playbackLooper!!.thread.name
            val myLooperName = if (myLooper == null) "null" else myLooper.thread.name
            throw IllegalStateException(
                "Current looper ("
                        + myLooperName
                        + ") is not the playback looper ("
                        + playbackLooperName
                        + ")"
            )
        }
        if (audioCapabilities != this.audioCapabilities) {
            this.audioCapabilities = audioCapabilities
            if (listener != null) {
                listener!!.onAudioCapabilitiesChanged()
            }
        }
    }


    // Internal methods.
    private fun resetSinkStateForFlush() {
        submittedPcmBytes = 0
        submittedEncodedFrames = 0
        writtenPcmBytes = 0
        writtenEncodedFrames = 0
        isWaitingForOffloadEndOfStreamHandled = false
        framesPerEncodedSample = 0
        mediaPositionParameters =
            MediaPositionParameters(
                playbackParameters,  /* mediaTimeUs= */0,  /* audioTrackPositionUs= */0
            )
        startMediaTimeUs = 0
        afterDrainParameters = null
        mediaPositionParametersCheckpoints!!.clear()
        inputBuffer = null
        inputBufferAccessUnitCount = 0
        outputBuffer = null
        stoppedAudioTrack = false
        handledEndOfStream = false
        handledOffloadOnPresentationEnded = false
        avSyncHeader = null
        bytesUntilNextAvSync = 0
        trimmingAudioProcessor!!.resetTrimmedFrameCount()
        setupAudioProcessors()
    }

    private fun setAudioTrackPlaybackParametersV23() {
        if (isAudioTrackInitialized()) {
            val playbackParams =
                PlaybackParams()
                    .allowDefaults()
                    .setSpeed(playbackParameters!!.speed)
                    .setPitch(playbackParameters!!.pitch)
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            try {
                audioTrack!!.playbackParams = playbackParams
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Failed to set playback params", e)
            }
            // Update the speed using the actual effective speed from the audio track.
            playbackParameters =
                PlaybackParameters(
                    audioTrack!!.playbackParams.speed, audioTrack!!.playbackParams.pitch
                )
            audioTrackPositionTracker!!.setAudioTrackPlaybackSpeed(playbackParameters!!.speed)
        }
    }

    private fun setAudioProcessorPlaybackParameters(playbackParameters: PlaybackParameters) {
        val mediaPositionParameters =
            MediaPositionParameters(
                playbackParameters,  /* mediaTimeUs= */
                C.TIME_UNSET,  /* audioTrackPositionUs= */
                C.TIME_UNSET
            )
        if (isAudioTrackInitialized()) {
            // Drain the audio processors so we can determine the frame position at which the new
            // parameters apply.
            this.afterDrainParameters = mediaPositionParameters
        } else {
            // Update the audio processor chain parameters now. They will be applied to the audio
            // processors during initialization.
            this.mediaPositionParameters = mediaPositionParameters
        }
    }

    private fun applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs: Long) {
        val audioProcessorPlaybackParameters: PlaybackParameters
        if (!useAudioTrackPlaybackParams()) {
            playbackParameters =
                if (shouldApplyAudioProcessorPlaybackParameters()
                ) audioProcessorChain!!.applyPlaybackParameters((playbackParameters)!!)
                else PlaybackParameters.DEFAULT
            audioProcessorPlaybackParameters = playbackParameters!!
        } else {
            audioProcessorPlaybackParameters = PlaybackParameters.DEFAULT
        }
        skipSilenceEnabled =
            if (shouldApplyAudioProcessorPlaybackParameters()
            ) audioProcessorChain!!.applySkipSilenceEnabled(skipSilenceEnabled)
            else DEFAULT_SKIP_SILENCE
        mediaPositionParametersCheckpoints!!.add(
            MediaPositionParameters(
                audioProcessorPlaybackParameters,  /* mediaTimeUs= */
                max(0.0, presentationTimeUs.toDouble()).toLong(),  /* audioTrackPositionUs= */
                configuration!!.framesToDurationUs(getWrittenFrames())
            )
        )
        setupAudioProcessors()
        if (listener != null) {
            listener!!.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        }
    }

    /**
     * Returns whether audio processor playback parameters should be applied in the current
     * configuration.
     */
    private fun shouldApplyAudioProcessorPlaybackParameters(): Boolean {
        // We don't apply speed/pitch adjustment using an audio processor in the following cases:
        // - in tunneling mode, because audio processing can change the duration of audio yet the video
        //   frame presentation times are currently not modified (see also
        //   https://github.com/google/ExoPlayer/issues/4803);
        // - when playing encoded audio via passthrough/offload, because modifying the audio stream
        //   would require decoding/re-encoding; and
        // - when outputting float PCM audio, because SonicAudioProcessor outputs 16-bit integer PCM.
        return ((!tunneling
                && (configuration!!.outputMode == OutputMode.OUTPUT_MODE_PCM
                ) && !shouldUseFloatOutput(configuration!!.inputFormat.pcmEncoding)))
    }

    private fun useAudioTrackPlaybackParams(): Boolean {
        return ((configuration != null
                ) && configuration!!.enableAudioTrackPlaybackParams
                && (Util.SDK_INT >= 23))
    }

    /**
     * Returns whether audio in the specified PCM encoding should be written to the audio track as
     * float PCM.
     */
    private fun shouldUseFloatOutput(pcmEncoding: @C.PcmEncoding Int): Boolean {
        return enableFloatOutput && Util.isEncodingHighResolutionPcm(pcmEncoding)
    }

    /**
     * Applies and updates media position parameters.
     *
     * @param positionUs The current audio track position, in microseconds.
     * @return The current media time, in microseconds.
     */
    private fun applyMediaPositionParameters(positionUs: Long): Long {
        while ((!mediaPositionParametersCheckpoints!!.isEmpty()
                    && positionUs >= mediaPositionParametersCheckpoints!!.first.audioTrackPositionUs)
        ) {
            // We are playing (or about to play) media with the new parameters, so update them.
            mediaPositionParameters = mediaPositionParametersCheckpoints!!.remove()
        }

        val playoutDurationSinceLastCheckpointUs =
            positionUs - mediaPositionParameters!!.audioTrackPositionUs
        if (mediaPositionParametersCheckpoints!!.isEmpty()) {
            val mediaDurationSinceLastCheckpointUs =
                audioProcessorChain!!.getMediaDuration(playoutDurationSinceLastCheckpointUs)
            return mediaPositionParameters!!.mediaTimeUs + mediaDurationSinceLastCheckpointUs
        } else {
            // The processor chain has been configured with new parameters, but we're still playing audio
            // that was processed using previous parameters. We can't scale the playout duration using the
            // processor chain in this case, so we fall back to scaling using the previous parameters'
            // target speed instead. Since the processor chain may not have achieved the target speed
            // precisely, we scale the duration to the next checkpoint (which will always be small) rather
            // than the duration from the previous checkpoint (which may be arbitrarily large). This
            // limits the amount of error that can be introduced due to a difference between the target
            // and actual speeds.
            val nextMediaPositionParameters =
                mediaPositionParametersCheckpoints!!.first
            val playoutDurationUntilNextCheckpointUs =
                nextMediaPositionParameters.audioTrackPositionUs - positionUs
            val mediaDurationUntilNextCheckpointUs =
                Util.getMediaDurationForPlayoutDuration(
                    playoutDurationUntilNextCheckpointUs,
                    mediaPositionParameters!!.playbackParameters!!.speed
                )
            return nextMediaPositionParameters.mediaTimeUs - mediaDurationUntilNextCheckpointUs
        }
    }

    private fun applySkipping(positionUs: Long): Long {
        val skippedOutputFrameCountAtCurrentPosition =
            audioProcessorChain!!.skippedOutputFrameCount
        val adjustedPositionUs =
            positionUs + configuration!!.framesToDurationUs(skippedOutputFrameCountAtCurrentPosition)
        if (skippedOutputFrameCountAtCurrentPosition > skippedOutputFrameCountAtLastPosition) {
            val silenceDurationUs =
                configuration!!.framesToDurationUs(
                    skippedOutputFrameCountAtCurrentPosition - skippedOutputFrameCountAtLastPosition
                )
            skippedOutputFrameCountAtLastPosition = skippedOutputFrameCountAtCurrentPosition
            handleSkippedSilence(silenceDurationUs)
        }
        return adjustedPositionUs
    }

    private fun handleSkippedSilence(silenceDurationUs: Long) {
        accumulatedSkippedSilenceDurationUs += silenceDurationUs
        if (reportSkippedSilenceHandler == null) {
            reportSkippedSilenceHandler = Handler((Looper.myLooper())!!)
        }
        reportSkippedSilenceHandler!!.removeCallbacksAndMessages(null)
        reportSkippedSilenceHandler!!.postDelayed(
            { this.maybeReportSkippedSilence() },  /* delayMillis= */
            REPORT_SKIPPED_SILENCE_DELAY_MS.toLong()
        )
    }

    private fun isAudioTrackInitialized(): Boolean {
        return audioTrack != null
    }

    private fun getSubmittedFrames(): Long {
        return if (configuration!!.outputMode == OutputMode.OUTPUT_MODE_PCM
        ) (submittedPcmBytes / configuration!!.inputPcmFrameSize)
        else submittedEncodedFrames
    }

    private fun getWrittenFrames(): Long {
        return if (configuration!!.outputMode == OutputMode.OUTPUT_MODE_PCM
        ) Util.ceilDivide(writtenPcmBytes, configuration!!.outputPcmFrameSize.toLong())
        else writtenEncodedFrames
    }

    private fun maybeStartAudioCapabilitiesReceiver() {
        if (audioCapabilitiesReceiver == null && context != null) {
            // Must be lazily initialized to receive audio capabilities receiver listener event on the
            // current (playback) thread as the constructor is not called in the playback thread.
            playbackLooper = Looper.myLooper()
            audioCapabilitiesReceiver =
                AudioCapabilitiesReceiver(
                    context!!,
                    object : AudioCapabilitiesReceiver.Listener {
                        override fun onAudioCapabilitiesChanged(audioCapabilities: AudioCapabilities?) {
                            this.onAudioCapabilitiesChanged(
                                audioCapabilities
                            )
                        }
                    },
                    (audioAttributes), preferredDevice
                )
            audioCapabilities = audioCapabilitiesReceiver!!.register()
        }
    }

    private fun isOffloadedPlayback(audioTrack: AudioTrack?): Boolean {
        return Util.SDK_INT >= 29 && audioTrack!!.isOffloadedPlayback
    }

    private fun getFramesPerEncodedSample(encoding: @C.Encoding Int, buffer: ByteBuffer): Int {
        when (encoding) {
            C.ENCODING_MP3 -> {
                val headerDataInBigEndian = Util.getBigEndianInt(buffer, buffer.position())
                val frameCount = MpegAudioUtil.parseMpegAudioFrameSampleCount(headerDataInBigEndian)
                if (frameCount == C.LENGTH_UNSET) {
                    throw IllegalArgumentException()
                }
                return frameCount
            }

            C.ENCODING_AAC_LC -> return AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT
            C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2 -> return AacUtil.AAC_HE_AUDIO_SAMPLE_COUNT
            C.ENCODING_AAC_XHE -> return AacUtil.AAC_XHE_AUDIO_SAMPLE_COUNT
            C.ENCODING_AAC_ELD -> return AacUtil.AAC_LD_AUDIO_SAMPLE_COUNT
            C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> return DtsUtil.parseDtsAudioSampleCount(
                buffer
            )

            C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> return Ac3Util.parseAc3SyncframeAudioSampleCount(
                buffer
            )

            C.ENCODING_AC4 -> return Ac4Util.parseAc4SyncframeAudioSampleCount(buffer)
            C.ENCODING_DOLBY_TRUEHD -> {
                val syncframeOffset = Ac3Util.findTrueHdSyncframeOffset(buffer)
                return if (syncframeOffset == C.INDEX_UNSET
                ) 0
                else ((Ac3Util.parseTrueHdSyncframeAudioSampleCount(buffer, syncframeOffset)
                        * Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT))
            }

            C.ENCODING_OPUS -> return OpusUtil.parseOggPacketAudioSampleCount(buffer)
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_8BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_AAC_ER_BSAC, C.ENCODING_INVALID, Format.NO_VALUE -> throw IllegalStateException(
                "Unexpected audio encoding: $encoding"
            )

            else -> throw IllegalStateException("Unexpected audio encoding: $encoding")
        }
    }

    private fun writeNonBlockingV21(audioTrack: AudioTrack?, buffer: ByteBuffer, size: Int): Int {
        return audioTrack!!.write(buffer, size, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun writeNonBlockingWithAvSyncV21(
        audioTrack: AudioTrack?, buffer: ByteBuffer, size: Int, presentationTimeUs: Long
    ): Int {
        if (Util.SDK_INT >= 26) {
            // The underlying platform AudioTrack writes AV sync headers directly.
            return audioTrack!!.write(
                buffer, size, AudioTrack.WRITE_NON_BLOCKING, presentationTimeUs * 1000
            )
        }
        if (avSyncHeader == null) {
            avSyncHeader = ByteBuffer.allocate(16)
            avSyncHeader!!.order(ByteOrder.BIG_ENDIAN)
            avSyncHeader!!.putInt(0x55550001)
        }
        if (bytesUntilNextAvSync == 0) {
            avSyncHeader!!.putInt(4, size)
            avSyncHeader!!.putLong(8, presentationTimeUs * 1000)
            avSyncHeader!!.position(0)
            bytesUntilNextAvSync = size
        }
        val avSyncHeaderBytesRemaining = avSyncHeader!!.remaining()
        if (avSyncHeaderBytesRemaining > 0) {
            val result =
                audioTrack!!.write(
                    (avSyncHeader)!!,
                    avSyncHeaderBytesRemaining,
                    AudioTrack.WRITE_NON_BLOCKING
                )
            if (result < 0) {
                bytesUntilNextAvSync = 0
                return result
            }
            if (result < avSyncHeaderBytesRemaining) {
                return 0
            }
        }
        val result = writeNonBlockingV21(audioTrack, buffer, size)
        if (result < 0) {
            bytesUntilNextAvSync = 0
            return result
        }
        bytesUntilNextAvSync -= result
        return result
    }

    private fun setVolumeInternalV21(audioTrack: AudioTrack?, volume: Float) {
        audioTrack!!.setVolume(volume)
    }

    private fun setVolumeInternalV3(audioTrack: AudioTrack?, volume: Float) {
        audioTrack!!.setStereoVolume(volume, volume)
    }

    private fun playPendingData() {
        if (!stoppedAudioTrack) {
            stoppedAudioTrack = true
            audioTrackPositionTracker!!.handleEndOfStream(getWrittenFrames())
            if (isOffloadedPlayback(audioTrack)) {
                // Reset handledOffloadOnPresentationEnded to track completion after
                // this following stop call.
                handledOffloadOnPresentationEnded = false
            }
            audioTrack!!.stop()
            bytesUntilNextAvSync = 0
        }
    }

    private fun releaseAudioTrackAsync(
        audioTrack: AudioTrack?,
        releasedConditionVariable: ConditionVariable?,
        listener: AudioSink.Listener?,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        // AudioTrack.release can take some time, so we call it on a background thread. The background
        // thread is shared statically to avoid creating many threads when multiple players are released
        // at the same time.
        releasedConditionVariable!!.close()
        val audioTrackThreadHandler = Handler((Looper.myLooper())!!)
        synchronized(releaseExecutorLock) {
            if (releaseExecutor == null) {
                releaseExecutor =
                    Util.newSingleThreadExecutor("ExoPlayer:AudioTrackReleaseThread")
            }
            pendingReleaseCount++
            releaseExecutor!!.execute(
                Runnable {
                    try {
                        audioTrack!!.flush()
                        audioTrack.release()
                    } finally {
                        if (listener != null && audioTrackThreadHandler.getLooper().getThread()
                                .isAlive()
                        ) {
                            audioTrackThreadHandler.post(Runnable {
                                listener.onAudioTrackReleased(
                                    audioTrackConfig
                                )
                            })
                        }
                        releasedConditionVariable.open()
                        synchronized(releaseExecutorLock) {
                            pendingReleaseCount--
                            if (pendingReleaseCount == 0) {
                                releaseExecutor!!.shutdown()
                                releaseExecutor = null
                            }
                        }
                    }
                })
        }
    }

    private class OnRoutingChangedListenerApi24(
        private val audioTrack: AudioTrack?,
        private val capabilitiesReceiver: AudioCapabilitiesReceiver
    ) {
        private var listener: AudioRouting.OnRoutingChangedListener?

        init {
            this.listener = AudioRouting.OnRoutingChangedListener { router: AudioRouting ->
                this.onRoutingChanged(
                    router
                )
            }
            val handler = Handler((Looper.myLooper())!!)
            audioTrack!!.addOnRoutingChangedListener(listener, handler)
        }

        @DoNotInline
        fun release() {
            audioTrack!!.removeOnRoutingChangedListener(Assertions.checkNotNull(listener))
            listener = null
        }

        @DoNotInline
        private fun onRoutingChanged(router: AudioRouting) {
            if (listener == null) {
                // Stale event.
                return
            }
            val routedDevice = router.routedDevice
            if (routedDevice != null) {
                capabilitiesReceiver.setRoutedDevice(router.routedDevice)
            }
        }
    }

    @RequiresApi(29)
    private inner class StreamEventCallbackV29() {
        private val handler: Handler
        private val callback: AudioTrack.StreamEventCallback

        init {
            handler = Handler((Looper.myLooper())!!)
            // Avoid StreamEventCallbackV29 inheriting directly from AudioTrack.StreamEventCallback as it
            // would cause a NoClassDefFoundError warning on load of DefaultAudioSink for SDK < 29.
            // See: https://github.com/google/ExoPlayer/issues/8058
            callback =
                object : AudioTrack.StreamEventCallback() {
                    override fun onDataRequest(track: AudioTrack, size: Int) {
                        if (track != audioTrack) {
                            // Stale event.
                            return
                        }
                        if (listener != null && playing) {
                            // Do not signal that the buffer is emptying if not playing as it is a transient
                            // state.
                            listener!!.onOffloadBufferEmptying()
                        }
                    }

                    override fun onPresentationEnded(track: AudioTrack) {
                        if (track != audioTrack) {
                            // Stale event.
                            return
                        }
                        handledOffloadOnPresentationEnded = true
                    }

                    override fun onTearDown(track: AudioTrack) {
                        if (track != audioTrack) {
                            // Stale event.
                            return
                        }
                        if (listener != null && playing) {
                            // The audio track was destroyed while in use. Thus a new AudioTrack needs to be
                            // created and its buffer filled, which will be done on the next handleBuffer call.
                            // Request this call explicitly in case ExoPlayer is sleeping waiting for a data
                            // request.
                            listener!!.onOffloadBufferEmptying()
                        }
                    }
                }
        }

        @DoNotInline
        fun register(audioTrack: AudioTrack?) {
            audioTrack!!.registerStreamEventCallback({ r: Runnable? ->
                handler.post(
                    (r)!!
                )
            }, callback)
        }

        @DoNotInline
        fun unregister(audioTrack: AudioTrack?) {
            audioTrack!!.unregisterStreamEventCallback(callback)
            handler.removeCallbacksAndMessages( /* token= */null)
        }
    }

    /** Stores parameters used to calculate the current media position.  */
    private class MediaPositionParameters(
        /** The playback parameters.  */
        val playbackParameters: PlaybackParameters?,
        /** The media time from which the playback parameters apply, in microseconds.  */
        val mediaTimeUs: Long,
        /** The audio track position from which the playback parameters apply, in microseconds.  */
        val audioTrackPositionUs: Long
    )

    private fun getAudioTrackMinBufferSize(
        sampleRateInHz: Int, channelConfig: Int, encoding: Int
    ): Int {
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, encoding)
        Assertions.checkState(minBufferSize != AudioTrack.ERROR_BAD_VALUE)
        return minBufferSize
    }

    /** Output mode of the audio sink.  */
    enum class OutputMode {
        OUTPUT_MODE_PCM,
        OUTPUT_MODE_OFFLOAD,
        OUTPUT_MODE_PASSTHROUGH;
    }

    companion object {
        /** The minimum duration of the skipped silence to be reported as discontinuity.  */
        private const val MINIMUM_REPORT_SKIPPED_SILENCE_DURATION_US: Int = 300000

        /**
         * The delay of reporting the skipped silence, during which the default audio sink checks if there
         * is any further skipped silence that is close to the delayed silence. If any, the further
         * skipped silence will be concatenated to the delayed one.
         */
        private const val REPORT_SKIPPED_SILENCE_DELAY_MS: Int = 100

        /** The default playback speed.  */
        const val DEFAULT_PLAYBACK_SPEED: Float = 1f

        /** The minimum allowed playback speed. Lower values will be constrained to fall in range.  */
        const val MIN_PLAYBACK_SPEED: Float = 0.1f

        /** The maximum allowed playback speed. Higher values will be constrained to fall in range.  */
        const val MAX_PLAYBACK_SPEED: Float = 8f

        /** The minimum allowed pitch factor. Lower values will be constrained to fall in range.  */
        const val MIN_PITCH: Float = 0.1f

        /** The maximum allowed pitch factor. Higher values will be constrained to fall in range.  */
        const val MAX_PITCH: Float = 8f

        /** The default skip silence flag.  */
        private const val DEFAULT_SKIP_SILENCE: Boolean = false

        /**
         * Native error code equivalent of [AudioTrack.ERROR_DEAD_OBJECT] to workaround missing
         * error code translation on some devices.
         *
         *
         * On some devices, AudioTrack native error codes are not always converted to their SDK
         * equivalent.
         *
         *
         * For example: [AudioTrack.write] can return -32 instead of [ ][AudioTrack.ERROR_DEAD_OBJECT].
         */
        private const val ERROR_NATIVE_DEAD_OBJECT: Int = -32

        /**
         * The duration for which failed attempts to initialize or write to the audio track may be retried
         * before throwing an exception, in milliseconds.
         */
        private const val AUDIO_TRACK_RETRY_DURATION_MS: Int = 100

        private const val TAG: String = "DefaultAudioSink"
    }

    private inner class PositionTrackerListener() : AudioTrackPositionTracker.Listener {
        override fun onPositionFramesMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        ) {
            val message =
                (("Spurious audio timestamp (frame position mismatch): "
                        + audioTimestampPositionFrames
                        + ", "
                        + audioTimestampSystemTimeUs
                        + ", "
                        + systemTimeUs
                        + ", "
                        + playbackPositionUs
                        + ", "
                        + getSubmittedFrames()
                        + ", "
                        + getWrittenFrames()))
            if (failOnSpuriousAudioTimestamp) {
                throw InvalidAudioTrackTimestampException(message)
            }
            Log.w(TAG, message)
        }

        override fun onSystemTimeUsMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        ) {
            val message =
                (("Spurious audio timestamp (system clock mismatch): "
                        + audioTimestampPositionFrames
                        + ", "
                        + audioTimestampSystemTimeUs
                        + ", "
                        + systemTimeUs
                        + ", "
                        + playbackPositionUs
                        + ", "
                        + getSubmittedFrames()
                        + ", "
                        + getWrittenFrames()))
            if (failOnSpuriousAudioTimestamp) {
                throw InvalidAudioTrackTimestampException(message)
            }
            Log.w(TAG, message)
        }

        override fun onInvalidLatency(latencyUs: Long) {
            Log.w(
                TAG,
                "Ignoring impossibly large audio latency: $latencyUs"
            )
        }

        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            if (listener != null) {
                listener!!.onPositionAdvancing(playoutStartSystemTimeMs)
            }
        }

        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long) {
            if (listener != null) {
                val elapsedSinceLastFeedMs: Long =
                    SystemClock.elapsedRealtime() - lastFeedElapsedRealtimeMs
                listener!!.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }
        }
    }

    /** Stores configuration relating to the audio format.  */
    private class Configuration(
        val inputFormat: Format,
        val inputPcmFrameSize: Int,
        val outputMode: OutputMode,
        val outputPcmFrameSize: Int,
        val outputSampleRate: Int,
        val outputChannelConfig: Int,
        val outputEncoding: @C.Encoding Int,
        val bufferSize: Int,
        val audioProcessingPipeline: AudioProcessingPipeline,
        val enableAudioTrackPlaybackParams: Boolean,
        val enableOffloadGapless: Boolean,
        val tunneling: Boolean
    ) {
        fun copyWithBufferSize(bufferSize: Int): Configuration {
            return Configuration(
                inputFormat,
                inputPcmFrameSize,
                outputMode,
                outputPcmFrameSize,
                outputSampleRate,
                outputChannelConfig,
                outputEncoding,
                bufferSize,
                audioProcessingPipeline,
                enableAudioTrackPlaybackParams,
                enableOffloadGapless,
                tunneling
            )
        }

        /** Returns if the configurations are sufficiently compatible to reuse the audio track.  */
        fun canReuseAudioTrack(newConfiguration: Configuration?): Boolean {
            return (newConfiguration!!.outputMode == outputMode
                    ) && (newConfiguration.outputEncoding == outputEncoding
                    ) && (newConfiguration.outputSampleRate == outputSampleRate
                    ) && (newConfiguration.outputChannelConfig == outputChannelConfig
                    ) && (newConfiguration.outputPcmFrameSize == outputPcmFrameSize
                    ) && (newConfiguration.enableAudioTrackPlaybackParams == enableAudioTrackPlaybackParams
                    ) && (newConfiguration.enableOffloadGapless == enableOffloadGapless)
        }

        fun inputFramesToDurationUs(frameCount: Long): Long {
            return Util.sampleCountToDurationUs(frameCount, inputFormat.sampleRate)
        }

        fun framesToDurationUs(frameCount: Long): Long {
            return Util.sampleCountToDurationUs(
                frameCount,
                outputSampleRate
            )
        }

        fun buildAudioTrackConfig(): AudioSink.AudioTrackConfig {
            return AudioSink.AudioTrackConfig(
                outputEncoding,
                outputSampleRate,
                outputChannelConfig,
                tunneling,
                outputMode == OutputMode.OUTPUT_MODE_OFFLOAD,
                bufferSize
            )
        }

        @Throws(AudioSink.InitializationException::class)
        fun buildAudioTrack(audioAttributes: AudioAttributes?, audioSessionId: Int): AudioTrack {
            val audioTrack: AudioTrack
            try {
                audioTrack = createAudioTrack(audioAttributes, audioSessionId)
            } catch (e: UnsupportedOperationException) {
                throw AudioSink.InitializationException(
                    AudioTrack.STATE_UNINITIALIZED,
                    outputSampleRate,
                    outputChannelConfig,
                    bufferSize,
                    inputFormat,  /* isRecoverable= */
                    outputModeIsOffload(),
                    e
                )
            } catch (e: IllegalArgumentException) {
                throw AudioSink.InitializationException(
                    AudioTrack.STATE_UNINITIALIZED,
                    outputSampleRate,
                    outputChannelConfig,
                    bufferSize,
                    inputFormat,
                    outputModeIsOffload(),
                    e
                )
            }

            val state = audioTrack.state
            if (state != AudioTrack.STATE_INITIALIZED) {
                try {
                    audioTrack.release()
                } catch (e: Exception) {
                    // The track has already failed to initialize, so it wouldn't be that surprising if
                    // release were to fail too. Swallow the exception.
                }
                throw AudioSink.InitializationException(
                    state,
                    outputSampleRate,
                    outputChannelConfig,
                    bufferSize,
                    inputFormat,  /* isRecoverable= */
                    outputModeIsOffload(),  /* audioTrackException= */
                    null
                )
            }
            return audioTrack
        }

        private fun createAudioTrack(
            audioAttributes: AudioAttributes?,
            audioSessionId: Int
        ): AudioTrack {
            if (Util.SDK_INT >= 29) {
                return createAudioTrackV29(audioAttributes, audioSessionId)
            } else if (Util.SDK_INT >= 21) {
                return createAudioTrackV21(audioAttributes, audioSessionId)
            } else {
                return createAudioTrackV9(audioAttributes, audioSessionId)
            }
        }

        @RequiresApi(29)
        private fun createAudioTrackV29(
            audioAttributes: AudioAttributes?,
            audioSessionId: Int
        ): AudioTrack {
            val audioFormat =
                Util.getAudioFormat(
                    outputSampleRate,
                    outputChannelConfig,
                    outputEncoding
                )
            val audioTrackAttributes =
                getAudioTrackAttributesV21(audioAttributes, tunneling)
            return AudioTrack.Builder()
                .setAudioAttributes(audioTrackAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setSessionId(audioSessionId)
                .setOffloadedPlayback(outputMode == OutputMode.OUTPUT_MODE_OFFLOAD)
                .build()
        }

        private fun createAudioTrackV21(
            audioAttributes: AudioAttributes?,
            audioSessionId: Int
        ): AudioTrack {
            return AudioTrack(
                getAudioTrackAttributesV21(audioAttributes, tunneling),
                Util.getAudioFormat(
                    outputSampleRate,
                    outputChannelConfig,
                    outputEncoding
                ),
                bufferSize,
                AudioTrack.MODE_STREAM,
                audioSessionId
            )
        }

        @Suppress("deprecation") // Using deprecated AudioTrack constructor.
        private fun createAudioTrackV9(
            audioAttributes: AudioAttributes?,
            audioSessionId: Int
        ): AudioTrack {
            val streamType = Util.getStreamTypeForAudioUsage(
                audioAttributes!!.usage
            )
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
                return AudioTrack(
                    streamType,
                    outputSampleRate,
                    outputChannelConfig,
                    outputEncoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            } else {
                // Re-attach to the same audio session.
                return AudioTrack(
                    streamType,
                    outputSampleRate,
                    outputChannelConfig,
                    outputEncoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    audioSessionId
                )
            }
        }

        fun outputModeIsOffload(): Boolean {
            return outputMode == OutputMode.OUTPUT_MODE_OFFLOAD
        }

        companion object {
            private fun getAudioTrackAttributesV21(
                audioAttributes: AudioAttributes?, tunneling: Boolean
            ): android.media.AudioAttributes {
                if (tunneling) {
                    return audioTrackTunnelingAttributesV21
                } else {
                    return audioAttributes!!.audioAttributesV21.audioAttributes
                }
            }

            private val audioTrackTunnelingAttributesV21: android.media.AudioAttributes
                get() = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setFlags(android.media.AudioAttributes.FLAG_HW_AV_SYNC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
        }
    }

    private class PendingExceptionHolder<T : Exception?>(private val throwDelayMs: Long) {
        private var pendingException: T? = null
        private var throwDeadlineMs: Long = 0

        fun throwExceptionIfDeadlineIsReached(exception: T) {
            val nowMs = SystemClock.elapsedRealtime()
            if (pendingException == null) {
                pendingException = exception
                throwDeadlineMs = nowMs + throwDelayMs
            }
            if (nowMs >= throwDeadlineMs) {
                if (pendingException !== exception) {
                    // All retry exception are probably the same, thus only save the last one to save memory.
                    pendingException!!.addSuppressed(exception)
                }
                val pendingException = this.pendingException
                clear()
                throw pendingException!!
            }
        }

        fun clear() {
            pendingException = null
        }
    }

    private fun maybeReportSkippedSilence() {
        if (accumulatedSkippedSilenceDurationUs >= MINIMUM_REPORT_SKIPPED_SILENCE_DURATION_US) {
            // If the existing silence is already long enough, report the silence
            listener!!.onSilenceSkipped()
            accumulatedSkippedSilenceDurationUs = 0
        }
    }

    class AudioDeviceInfoApi23
    /**
     * Creates the audio device info wrapper.
     *
     * @param audioDeviceInfo The platform [AudioDeviceInfo].
     */(
        /** The platform [AudioDeviceInfo].  */
        val audioDeviceInfo: AudioDeviceInfo
    )

    private object Api23 {
        @DoNotInline
        fun setPreferredDeviceOnAudioTrack(
            audioTrack: AudioTrack,
            audioDeviceInfo: AudioDeviceInfoApi23?
        ) {
            audioTrack.setPreferredDevice(
                audioDeviceInfo?.audioDeviceInfo
            )
        }
    }

    @RequiresApi(31)
    private object Api31 {
        @DoNotInline
        fun setLogSessionIdOnAudioTrack(audioTrack: AudioTrack?, playerId: PlayerId) {
            val logSessionId = playerId.logSessionId
            if (logSessionId != LogSessionId.LOG_SESSION_ID_NONE) {
                audioTrack!!.logSessionId = logSessionId
            }
        }
    }
}