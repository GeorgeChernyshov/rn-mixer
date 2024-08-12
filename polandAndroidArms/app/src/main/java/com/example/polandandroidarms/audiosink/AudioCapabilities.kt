package com.example.polandandroidarms.audiosink

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioProfile
import android.media.AudioTrack
import android.net.Uri
import android.provider.Settings
import android.util.Pair
import android.util.SparseArray
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.example.polandandroidarms.audiosink.LowLatencyAudioSink.AudioDeviceInfoApi23
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.primitives.Ints
import kotlin.math.max

@UnstableApi
class AudioCapabilities private constructor(audioProfiles: List<AudioProfile>) {
    private val encodingToAudioProfile: SparseArray<AudioProfile>

    /** Returns the maximum number of channels the device can play at the same time.  */
    val maxChannelCount: Int


    @Deprecated("Use {@link #getCapabilities(Context, AudioAttributes, AudioDeviceInfo)} instead.")
    constructor(supportedEncodings: IntArray?, maxChannelCount: Int) : this(
        getAudioProfiles(
            supportedEncodings,
            maxChannelCount
        )
    )

    init {
        encodingToAudioProfile = SparseArray()
        for (i in audioProfiles.indices) {
            val audioProfile = audioProfiles[i]
            encodingToAudioProfile.put(audioProfile.encoding, audioProfile)
        }
        var maxChannelCount = 0
        for (i in 0 until encodingToAudioProfile.size()) {
            maxChannelCount = max(
                maxChannelCount.toDouble(),
                encodingToAudioProfile.valueAt(i).maxChannelCount.toDouble()
            )
                .toInt()
        }
        this.maxChannelCount = maxChannelCount
    }

    /**
     * Returns whether this device supports playback of the specified audio `encoding`.
     *
     * @param encoding One of [C.Encoding]'s `ENCODING_*` constants.
     * @return Whether this device supports playback the specified audio `encoding`.
     */
    fun supportsEncoding(encoding: @C.Encoding Int): Boolean {
        return Util.contains(encodingToAudioProfile, encoding)
    }


    @Deprecated("Use {@link #isPassthroughPlaybackSupported(Format, AudioAttributes)} instead.")
    fun isPassthroughPlaybackSupported(format: Format): Boolean {
        return isPassthroughPlaybackSupported(format, AudioAttributes.DEFAULT)
    }

    /** Returns whether the device can do passthrough playback for `format`.  */
    fun isPassthroughPlaybackSupported(format: Format, audioAttributes: AudioAttributes): Boolean {
        return getEncodingAndChannelConfigForPassthrough(format, audioAttributes) != null
    }


    @Deprecated(
        """Use {@link #getEncodingAndChannelConfigForPassthrough(Format, AudioAttributes)}
        instead."""
    )
    fun getEncodingAndChannelConfigForPassthrough(format: Format): Pair<Int, Int>? {
        return getEncodingAndChannelConfigForPassthrough(format, AudioAttributes.DEFAULT)
    }

    /**
     * Returns the encoding and channel config to use when configuring an [AudioTrack] in
     * passthrough mode for the specified [Format] and [AudioAttributes]. Returns `null` if passthrough of the format is unsupported.
     *
     * @param format The [Format].
     * @param audioAttributes The [AudioAttributes].
     * @return The encoding and channel config to use, or `null` if passthrough of the format is
     * unsupported.
     */
    fun getEncodingAndChannelConfigForPassthrough(
        format: Format, audioAttributes: AudioAttributes
    ): Pair<Int, Int>? {
        var encoding: @C.Encoding Int =
            MimeTypes.getEncoding(Assertions.checkNotNull(format.sampleMimeType), format.codecs)
        // Check that this is an encoding known to work for passthrough. This avoids trying to use
        // passthrough with an encoding where the device/app reports it's capable but it is untested or
        // known to be broken (for example AAC-LC).
        if (!ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.containsKey(encoding)) {
            return null
        }

        if (encoding == C.ENCODING_E_AC3_JOC && !supportsEncoding(C.ENCODING_E_AC3_JOC)) {
            // E-AC3 receivers support E-AC3 JOC streams (but decode only the base layer).
            encoding = C.ENCODING_E_AC3
        } else if ((encoding == C.ENCODING_DTS_HD && !supportsEncoding(C.ENCODING_DTS_HD))
            || (encoding == C.ENCODING_DTS_UHD_P2 && !supportsEncoding(C.ENCODING_DTS_UHD_P2))
        ) {
            // DTS receivers support DTS-HD streams (but decode only the core layer).
            encoding = C.ENCODING_DTS
        }
        if (!supportsEncoding(encoding)) {
            return null
        }

        val audioProfile = Assertions.checkNotNull(
            encodingToAudioProfile[encoding]
        )
        val channelCount: Int
        if (format.channelCount == Format.NO_VALUE || encoding == C.ENCODING_E_AC3_JOC) {
            // In HLS chunkless preparation, the format channel count and sample rate may be unset. See
            // https://github.com/google/ExoPlayer/issues/10204 and b/222127949 for more details.
            // For E-AC3 JOC, the format is object based so the format channel count is arbitrary.
            val sampleRate =
                if (format.sampleRate != Format.NO_VALUE) format.sampleRate else DEFAULT_SAMPLE_RATE_HZ
            channelCount =
                audioProfile.getMaxSupportedChannelCountForPassthrough(sampleRate, audioAttributes)
        } else {
            channelCount = format.channelCount
            if (format.sampleMimeType == MimeTypes.AUDIO_DTS_X && Util.SDK_INT < 33) {
                // Some DTS:X TVs reports ACTION_HDMI_AUDIO_PLUG.EXTRA_MAX_CHANNEL_COUNT as 8
                // instead of 10. See https://github.com/androidx/media/issues/396
                if (channelCount > 10) {
                    return null
                }
            } else if (!audioProfile.supportsChannelCount(channelCount)) {
                return null
            }
        }
        val channelConfig = getChannelConfigForPassthrough(channelCount)
        if (channelConfig == AudioFormat.CHANNEL_INVALID) {
            return null
        }
        return Pair.create(encoding, channelConfig)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is AudioCapabilities) {
            return false
        }
        val audioCapabilities = other
        return (Util.contentEquals(encodingToAudioProfile, audioCapabilities.encodingToAudioProfile)
                && maxChannelCount == audioCapabilities.maxChannelCount)
    }

    override fun hashCode(): Int {
        return maxChannelCount + 31 * Util.contentHashCode(encodingToAudioProfile)
    }

    override fun toString(): String {
        return ("AudioCapabilities[maxChannelCount="
                + maxChannelCount
                + ", audioProfiles="
                + encodingToAudioProfile
                + "]")
    }

    private class AudioProfile {
        val encoding: @C.Encoding Int
        val maxChannelCount: Int
        private val channelMasks: ImmutableSet<Int>?

        @RequiresApi(33)
        constructor(encoding: @C.Encoding Int, channelMasks: Set<Int>?) {
            this.encoding = encoding
            this.channelMasks = ImmutableSet.copyOf(channelMasks)
            var maxChannelCount = 0
            for (channelMask in this.channelMasks) {
                maxChannelCount =
                    max(maxChannelCount.toDouble(), Integer.bitCount(channelMask).toDouble())
                        .toInt()
            }
            this.maxChannelCount = maxChannelCount
        }

        constructor(encoding: @C.Encoding Int, maxChannelCount: Int) {
            this.encoding = encoding
            this.maxChannelCount = maxChannelCount
            this.channelMasks = null
        }

        fun supportsChannelCount(channelCount: Int): Boolean {
            if (channelMasks == null) {
                return channelCount <= maxChannelCount
            }

            val channelMask = Util.getAudioTrackChannelConfig(channelCount)
            if (channelMask == AudioFormat.CHANNEL_INVALID) {
                return false
            }
            return channelMasks.contains(channelMask)
        }

        fun getMaxSupportedChannelCountForPassthrough(
            sampleRate: Int, audioAttributes: AudioAttributes
        ): Int {
            if (channelMasks != null) {
                // We built the AudioProfile on API 33.
                return maxChannelCount
            } else if (Util.SDK_INT >= 29) {
                return Api29.getMaxSupportedChannelCountForPassthrough(
                    encoding, sampleRate, audioAttributes
                )
            }
            return Assertions.checkNotNull(
                ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.getOrDefault(encoding, 0)
            )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is AudioProfile) {
                return false
            }
            val audioProfile = other
            return encoding == audioProfile.encoding && maxChannelCount == audioProfile.maxChannelCount && Util.areEqual(
                channelMasks,
                audioProfile.channelMasks
            )
        }

        override fun hashCode(): Int {
            var result = encoding
            result = 31 * result + maxChannelCount
            result = 31 * result + (channelMasks?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String {
            return ("AudioProfile[format="
                    + encoding
                    + ", maxChannelCount="
                    + maxChannelCount
                    + ", channelMasks="
                    + channelMasks
                    + "]")
        }

        companion object {
            val DEFAULT_AUDIO_PROFILE: AudioProfile = if ((Util.SDK_INT >= 33)
            ) AudioProfile(
                C.ENCODING_PCM_16BIT,
                getAllChannelMasksForMaxChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
            )
            else AudioProfile(C.ENCODING_PCM_16BIT, DEFAULT_MAX_CHANNEL_COUNT)

            private fun getAllChannelMasksForMaxChannelCount(maxChannelCount: Int): ImmutableSet<Int> {
                val allChannelMasks = ImmutableSet.Builder<Int>()
                for (i in 1..maxChannelCount) {
                    allChannelMasks.add(Util.getAudioTrackChannelConfig(i))
                }
                return allChannelMasks.build()
            }
        }
    }

    @RequiresApi(23)
    private object Api23 {
        @DoNotInline
        fun isBluetoothConnected(
            audioManager: AudioManager?, currentDevice: LowLatencyAudioSink.AudioDeviceInfoApi23?
        ): Boolean {
            // Check the current device if known or all devices otherwise.
            val audioDeviceInfos =
                if (currentDevice == null
                ) Assertions.checkNotNull(audioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                else arrayOf(currentDevice.audioDeviceInfo)
            val allBluetoothDeviceTypesSet =
                allBluetoothDeviceTypes
            for (audioDeviceInfo in audioDeviceInfos) {
                if (allBluetoothDeviceTypesSet.contains(audioDeviceInfo.type)) {
                    return true
                }
            }
            return false
        }

        @get:DoNotInline
        private val allBluetoothDeviceTypes: ImmutableSet<Int>
            /**
             * Returns all the possible bluetooth device types that can be returned by [ ][AudioDeviceInfo.getType].
             *
             *
             * The types [AudioDeviceInfo.TYPE_BLUETOOTH_A2DP] and [ ][AudioDeviceInfo.TYPE_BLUETOOTH_SCO] are included from API 23. And the types [ ][AudioDeviceInfo.TYPE_BLE_HEADSET] and [AudioDeviceInfo.TYPE_BLE_SPEAKER] are added from
             * API 31. And the type [AudioDeviceInfo.TYPE_BLE_BROADCAST] is added from API 33.
             */
            get() {
                val allBluetoothDeviceTypes =
                    ImmutableSet.Builder<Int>()
                        .add(
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        )
                if (Util.SDK_INT >= 31) {
                    allBluetoothDeviceTypes.add(
                        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER
                    )
                }
                if (Util.SDK_INT >= 33) {
                    allBluetoothDeviceTypes.add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
                }
                return allBluetoothDeviceTypes.build()
            }
    }

    @RequiresApi(29)
    private object Api29 {
        @DoNotInline
        fun getDirectPlaybackSupportedEncodings(
            audioAttributes: AudioAttributes
        ): ImmutableList<Int> {
            val supportedEncodingsListBuilder = ImmutableList.builder<Int>()
            for (encoding in ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.keys) {
                if (Util.SDK_INT < Util.getApiLevelThatAudioFormatIntroducedAudioEncoding(encoding)) {
                    // Example: AudioFormat.ENCODING_DTS_UHD_P2 is supported only from API 34.
                    continue
                }
                if (AudioTrack.isDirectPlaybackSupported(
                        AudioFormat.Builder()
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(encoding)
                            .setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
                            .build(),
                        audioAttributes.audioAttributesV21.audioAttributes
                    )
                ) {
                    supportedEncodingsListBuilder.add(encoding)
                }
            }
            supportedEncodingsListBuilder.add(AudioFormat.ENCODING_PCM_16BIT)
            return supportedEncodingsListBuilder.build()
        }

        /**
         * Returns the maximum number of channels supported for passthrough playback of audio in the
         * given format, or `0` if the format is unsupported.
         */
        @DoNotInline
        fun getMaxSupportedChannelCountForPassthrough(
            encoding: @C.Encoding Int, sampleRate: Int, audioAttributes: AudioAttributes
        ): Int {
            // TODO(internal b/234351617): Query supported channel masks directly once it's supported,
            // see also b/25994457.
            for (channelCount in DEFAULT_MAX_CHANNEL_COUNT downTo 1) {
                val channelConfig = Util.getAudioTrackChannelConfig(channelCount)
                if (channelConfig == AudioFormat.CHANNEL_INVALID) {
                    continue
                }
                val audioFormat =
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                if (AudioTrack.isDirectPlaybackSupported(
                        audioFormat, audioAttributes.audioAttributesV21.audioAttributes
                    )
                ) {
                    return channelCount
                }
            }
            return 0
        }
    }

    @RequiresApi(33)
    private object Api33 {
        @DoNotInline
        fun getCapabilitiesInternalForDirectPlayback(
            audioManager: AudioManager, audioAttributes: AudioAttributes
        ): AudioCapabilities {
            val directAudioProfiles =
                audioManager.getDirectProfilesForAttributes(
                    audioAttributes.audioAttributesV21.audioAttributes
                )
            return AudioCapabilities(getAudioProfiles(directAudioProfiles))
        }

        @DoNotInline
        fun getDefaultRoutedDeviceForAttributes(
            audioManager: AudioManager?, audioAttributes: AudioAttributes
        ): LowLatencyAudioSink.AudioDeviceInfoApi23? {
            val audioDevices: List<AudioDeviceInfo>
            try {
                audioDevices =
                    Assertions.checkNotNull(audioManager)
                        .getAudioDevicesForAttributes(
                            audioAttributes.audioAttributesV21.audioAttributes
                        )
            } catch (e: RuntimeException) {
                // Audio manager failed to retrieve devices.
                // TODO: b/306324391 - Remove once https://github.com/robolectric/robolectric/commit/442dff
                //  is released.
                return null
            }
            if (audioDevices.isEmpty()) {
                // Can't find current device.
                return null
            }
            // List only has more than one element if output devices are duplicated, so we assume the
            // first device in the list has all the information we need.
            return AudioDeviceInfoApi23(audioDevices[0])
        }
    }

    companion object {
        // TODO(internal b/283945513): Have separate default max channel counts in `AudioCapabilities`
        // for PCM and compressed audio.
        @VisibleForTesting /* package */
        val DEFAULT_MAX_CHANNEL_COUNT: Int = 10

        @VisibleForTesting /* package */
        val DEFAULT_SAMPLE_RATE_HZ: Int = 48000

        /** The minimum audio capabilities supported by all devices.  */
        val DEFAULT_AUDIO_CAPABILITIES: AudioCapabilities = AudioCapabilities(
            ImmutableList.of(
                AudioProfile.DEFAULT_AUDIO_PROFILE
            )
        )

        /** Encodings supported when the device specifies external surround sound.  */
        @SuppressLint("InlinedApi") // Compile-time access to integer constants defined in API 21.
        private val EXTERNAL_SURROUND_SOUND_ENCODINGS: ImmutableList<Int> = ImmutableList.of(
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3
        )

        /**
         * All surround sound encodings that a device may be capable of playing mapped to a maximum
         * channel count.
         */
        @VisibleForTesting /* package */
        val ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS: ImmutableMap<Int, Int> =
            ImmutableMap.Builder<Int, Int>()
                .put(C.ENCODING_AC3, 6)
                .put(C.ENCODING_AC4, 6)
                .put(C.ENCODING_DTS, 6)
                .put(C.ENCODING_DTS_UHD_P2, 10)
                .put(C.ENCODING_E_AC3_JOC, 6)
                .put(C.ENCODING_E_AC3, 8)
                .put(C.ENCODING_DTS_HD, 8)
                .put(C.ENCODING_DOLBY_TRUEHD, 8)
                .buildOrThrow()

        /** Global settings key for devices that can specify external surround sound.  */
        private const val EXTERNAL_SURROUND_SOUND_KEY = "external_surround_sound_enabled"

        /**
         * Global setting key for devices that want to force the usage of [ ][.EXTERNAL_SURROUND_SOUND_KEY] over other signals like HDMI.
         */
        private const val FORCE_EXTERNAL_SURROUND_SOUND_KEY = "use_external_surround_sound_flag"


        @Deprecated("Use {@link #getCapabilities(Context, AudioAttributes, AudioDeviceInfo)} instead.")
        fun getCapabilities(context: Context): AudioCapabilities {
            return getCapabilities(context, AudioAttributes.DEFAULT,  /* routedDevice= */null)
        }

        /**
         * Returns the current audio capabilities.
         *
         * @param context A context for obtaining the current audio capabilities.
         * @param audioAttributes The [AudioAttributes] to obtain capabilities for.
         * @param routedDevice The [AudioDeviceInfo] audio will be routed to if known, or null to
         * assume the default route.
         * @return The current audio capabilities for the device.
         */
        fun getCapabilities(
            context: Context, audioAttributes: AudioAttributes, routedDevice: AudioDeviceInfo?
        ): AudioCapabilities {
            val routedDeviceApi23 =
                if (Util.SDK_INT >= 23 && routedDevice != null) AudioDeviceInfoApi23(routedDevice) else null
            return getCapabilitiesInternal(context, audioAttributes, routedDeviceApi23)
        }

        @SuppressLint("UnprotectedReceiver") // ACTION_HDMI_AUDIO_PLUG is protected since API 16
                /* package */ fun getCapabilitiesInternal(
            context: Context,
            audioAttributes: AudioAttributes,
            routedDevice: AudioDeviceInfoApi23?
        ): AudioCapabilities {
            val intent =
                context.registerReceiver( /* receiver= */
                    null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                )
            return getCapabilitiesInternal(context, intent, audioAttributes, routedDevice)
        }

        @SuppressLint("InlinedApi") /* package */ fun getCapabilitiesInternal(
            context: Context,
            intent: Intent?,
            audioAttributes: AudioAttributes,
            routedDevice: AudioDeviceInfoApi23?
        ): AudioCapabilities {
            val audioManager =
                Assertions.checkNotNull(context.getSystemService(Context.AUDIO_SERVICE)) as AudioManager
            val currentDevice =
                routedDevice
                    ?: (if (Util.SDK_INT >= 33
                    ) Api33.getDefaultRoutedDeviceForAttributes(audioManager, audioAttributes)
                    else null)

            if (Util.SDK_INT >= 33 && (Util.isTv(context) || Util.isAutomotive(context))) {
                // TV or automotive devices generally shouldn't support audio offload for surround encodings,
                // so the encodings we get from AudioManager.getDirectProfilesForAttributes should include
                // the PCM encodings and surround encodings for passthrough mode.
                return Api33.getCapabilitiesInternalForDirectPlayback(audioManager, audioAttributes)
            }

            // If a connection to Bluetooth device is detected, we only return the minimum capabilities that
            // is supported by all the devices.
            if (Util.SDK_INT >= 23 && Api23.isBluetoothConnected(audioManager, currentDevice)) {
                return DEFAULT_AUDIO_CAPABILITIES
            }

            val supportedEncodings = ImmutableSet.Builder<Int>()
            supportedEncodings.add(C.ENCODING_PCM_16BIT)

            // AudioTrack.isDirectPlaybackSupported returns true for encodings that are supported for audio
            // offload, as well as for encodings we want to list for passthrough mode. Therefore we only use
            // it on TV and automotive devices, which generally shouldn't support audio offload for surround
            // encodings.
            if (Util.SDK_INT >= 29 && (Util.isTv(context) || Util.isAutomotive(context))) {
                supportedEncodings.addAll(Api29.getDirectPlaybackSupportedEncodings(audioAttributes))
                return AudioCapabilities(
                    getAudioProfiles(
                        Ints.toArray(supportedEncodings.build()),
                        DEFAULT_MAX_CHANNEL_COUNT
                    )
                )
            }

            val contentResolver = context.contentResolver
            val forceExternalSurroundSoundSetting =
                Settings.Global.getInt(contentResolver, FORCE_EXTERNAL_SURROUND_SOUND_KEY, 0) == 1
            if ((forceExternalSurroundSoundSetting || deviceMaySetExternalSurroundSoundGlobalSetting())
                && Settings.Global.getInt(contentResolver, EXTERNAL_SURROUND_SOUND_KEY, 0) == 1
            ) {
                supportedEncodings.addAll(EXTERNAL_SURROUND_SOUND_ENCODINGS)
            }

            if (intent != null && !forceExternalSurroundSoundSetting && intent.getIntExtra(
                    AudioManager.EXTRA_AUDIO_PLUG_STATE,
                    0
                ) == 1
            ) {
                val encodingsFromExtra = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
                if (encodingsFromExtra != null) {
                    supportedEncodings.addAll(Ints.asList(*encodingsFromExtra))
                }
                return AudioCapabilities(
                    getAudioProfiles(
                        Ints.toArray(supportedEncodings.build()),
                        intent.getIntExtra(
                            AudioManager.EXTRA_MAX_CHANNEL_COUNT,  /* defaultValue= */
                            DEFAULT_MAX_CHANNEL_COUNT
                        )
                    )
                )
            }

            return AudioCapabilities(
                getAudioProfiles(
                    Ints.toArray(supportedEncodings.build()),  /* maxChannelCount= */
                    DEFAULT_MAX_CHANNEL_COUNT
                )
            )
        }

        val externalSurroundSoundGlobalSettingUri: Uri?
            /**
             * Returns the global settings [Uri] used by the device to specify external surround sound,
             * or null if the device does not support this functionality.
             */
            get() = if (deviceMaySetExternalSurroundSoundGlobalSetting()
            ) Settings.Global.getUriFor(EXTERNAL_SURROUND_SOUND_KEY)
            else null

        private fun deviceMaySetExternalSurroundSoundGlobalSetting(): Boolean {
            return "Amazon" == Util.MANUFACTURER || "Xiaomi" == Util.MANUFACTURER
        }

        private fun getChannelConfigForPassthrough(channelCount: Int): Int {
            var channelCount = channelCount
            if (Util.SDK_INT <= 28) {
                // In passthrough mode the channel count used to configure the audio track doesn't affect how
                // the stream is handled, except that some devices do overly-strict channel configuration
                // checks. Therefore we override the channel count so that a known-working channel
                // configuration is chosen in all cases. See [Internal: b/29116190].
                if (channelCount == 7) {
                    channelCount = 8
                } else if (channelCount == 3 || channelCount == 4 || channelCount == 5) {
                    channelCount = 6
                }
            }

            // Workaround for Nexus Player not reporting support for mono passthrough. See
            // [Internal: b/34268671].
            if (Util.SDK_INT <= 26 && "fugu" == Util.DEVICE && channelCount == 1) {
                channelCount = 2
            }

            return Util.getAudioTrackChannelConfig(channelCount)
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        @RequiresApi(33)
        private fun getAudioProfiles(
            audioProfiles: List<android.media.AudioProfile>
        ): ImmutableList<AudioProfile> {
            val formatToChannelMasks: MutableMap<Int, MutableSet<Int>> = HashMap()
            // Enforce the support of stereo 16bit-PCM.
            formatToChannelMasks[C.ENCODING_PCM_16BIT] =
                HashSet(Ints.asList(AudioFormat.CHANNEL_OUT_STEREO))
            for (i in audioProfiles.indices) {
                val audioProfile = audioProfiles[i]
                if ((audioProfile.encapsulationType
                            == android.media.AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937)
                ) {
                    // Skip the IEC61937 encapsulation because we don't support it yet.
                    continue
                }
                val encoding = audioProfile.format
                if (!Util.isEncodingLinearPcm(encoding)
                    && !ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.containsKey(encoding)
                ) {
                    continue
                }
                if (formatToChannelMasks.containsKey(encoding)) {
                    Assertions.checkNotNull(
                        formatToChannelMasks[encoding]
                    )
                        .addAll(Ints.asList(*audioProfile.channelMasks))
                } else {
                    formatToChannelMasks[encoding] =
                        HashSet(Ints.asList(*audioProfile.channelMasks))
                }
            }

            val localAudioProfiles: ImmutableList.Builder<AudioProfile> = ImmutableList.builder()
            for ((key, value) in formatToChannelMasks) {
                localAudioProfiles.add(
                    AudioProfile(key, value)
                )
            }
            return localAudioProfiles.build()
        }

        private fun getAudioProfiles(
            supportedEncodings: IntArray?, maxChannelCount: Int
        ): ImmutableList<AudioProfile> {
            var supportedEncodings = supportedEncodings
            val audioProfiles: ImmutableList.Builder<AudioProfile> = ImmutableList.builder()
            if (supportedEncodings == null) {
                supportedEncodings = IntArray(0)
            }
            for (i in supportedEncodings.indices) {
                val encoding = supportedEncodings[i]
                audioProfiles.add(AudioProfile(encoding, maxChannelCount))
            }
            return audioProfiles.build()
        }
    }
}