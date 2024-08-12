package com.example.polandandroidarms.audiosink

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

@UnstableApi
class AudioCapabilitiesReceiver internal constructor(
    context: Context,
    listener: Listener?,
    audioAttributes: AudioAttributes?,
    routedDevice: LowLatencyAudioSink.AudioDeviceInfoApi23?
) {
    /** Listener notified when audio capabilities change.  */
    interface Listener {
        /**
         * Called when the audio capabilities change.
         *
         * @param audioCapabilities The current audio capabilities for the device.
         */
        fun onAudioCapabilitiesChanged(audioCapabilities: AudioCapabilities?)
    }

    private val context: Context
    private val listener: Listener
    private val handler: Handler
    private val audioDeviceCallback: AudioDeviceCallbackV23?
    private val hdmiAudioPlugBroadcastReceiver: BroadcastReceiver?
    private val externalSurroundSoundSettingObserver: ExternalSurroundSoundSettingObserver?

    private var audioCapabilities: AudioCapabilities? = null
    private var routedDevice: LowLatencyAudioSink.AudioDeviceInfoApi23?
    private var audioAttributes: AudioAttributes?
    private var registered = false


    @Deprecated(
        """Use {@link #AudioCapabilitiesReceiver(Context, Listener, AudioAttributes,
   *     AudioDeviceInfo)} instead."""
    )
    constructor(context: Context, listener: Listener?) : this(
        context,
        listener,
        AudioAttributes.DEFAULT,  /* routedDevice= */
        null as AudioDeviceInfo?
    )

    /**
     * @param context A context for registering the receiver.
     * @param listener The listener to notify when audio capabilities change.
     * @param audioAttributes The [AudioAttributes].
     * @param routedDevice The [AudioDeviceInfo] audio will be routed to if known, or null to
     * assume the default route.
     */
    constructor(
        context: Context,
        listener: Listener?,
        audioAttributes: AudioAttributes?,
        routedDevice: AudioDeviceInfo?
    ) : this(
        context,
        listener,
        audioAttributes,
        routedDevice?.let {
            LowLatencyAudioSink.AudioDeviceInfoApi23(it)
        }
    )

    /* package */
    init {
        var context = context
        context = context.applicationContext
        this.context = context
        this.listener = Assertions.checkNotNull(listener)
        this.audioAttributes = audioAttributes
        this.routedDevice = routedDevice
        handler = Util.createHandlerForCurrentOrMainLooper()
        audioDeviceCallback = if (Util.SDK_INT >= 23) AudioDeviceCallbackV23() else null
        hdmiAudioPlugBroadcastReceiver =
            if (Util.SDK_INT >= 21) HdmiAudioPlugBroadcastReceiver() else null
        val externalSurroundSoundUri = AudioCapabilities.externalSurroundSoundGlobalSettingUri
        externalSurroundSoundSettingObserver =
            if (externalSurroundSoundUri != null
            ) ExternalSurroundSoundSettingObserver(
                handler, context.contentResolver, externalSurroundSoundUri
            )
            else null
    }

    /**
     * Updates the [AudioAttributes] used by this instance.
     *
     * @param audioAttributes The [AudioAttributes].
     */
    fun setAudioAttributes(audioAttributes: AudioAttributes?) {
        this.audioAttributes = audioAttributes
        onNewAudioCapabilities(
            AudioCapabilities.getCapabilitiesInternal(
                context,
                audioAttributes!!, routedDevice
            )
        )
    }

    /**
     * Updates the [AudioDeviceInfo] audio will be routed to.
     *
     * @param routedDevice The [AudioDeviceInfo] audio will be routed to if known, or null to
     * assume the default route.
     */
    fun setRoutedDevice(routedDevice: AudioDeviceInfo?) {
        if (Util.areEqual(
                routedDevice,
                if (this.routedDevice == null) null else this.routedDevice!!.audioDeviceInfo
            )
        ) {
            return
        }
        this.routedDevice = routedDevice?.let {
            LowLatencyAudioSink.AudioDeviceInfoApi23(it)
        }
        onNewAudioCapabilities(
            AudioCapabilities.getCapabilitiesInternal(
                context,
                audioAttributes!!, this.routedDevice
            )
        )
    }

    /**
     * Registers the receiver, meaning it will notify the listener when audio capability changes
     * occur. The current audio capabilities will be returned. It is important to call [ ][.unregister] when the receiver is no longer required.
     *
     * @return The current audio capabilities for the device.
     */
    fun register(): AudioCapabilities {
        if (registered) {
            return Assertions.checkNotNull(audioCapabilities)
        }
        registered = true
        externalSurroundSoundSettingObserver?.register()
        if (Util.SDK_INT >= 23 && audioDeviceCallback != null) {
            Api23.registerAudioDeviceCallback(context, audioDeviceCallback, handler)
        }
        var stickyIntent: Intent? = null
        if (hdmiAudioPlugBroadcastReceiver != null) {
            val intentFilter = IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG)
            stickyIntent =
                context.registerReceiver(
                    hdmiAudioPlugBroadcastReceiver,
                    intentFilter,  /* broadcastPermission= */
                    null,
                    handler
                )
        }
        audioCapabilities =
            AudioCapabilities.getCapabilitiesInternal(
                context, stickyIntent, audioAttributes!!, routedDevice
            )
        return audioCapabilities!!
    }

    /**
     * Unregisters the receiver, meaning it will no longer notify the listener when audio capability
     * changes occur.
     */
    fun unregister() {
        if (!registered) {
            return
        }
        audioCapabilities = null
        if (Util.SDK_INT >= 23 && audioDeviceCallback != null) {
            Api23.unregisterAudioDeviceCallback(context, audioDeviceCallback)
        }
        if (hdmiAudioPlugBroadcastReceiver != null) {
            context.unregisterReceiver(hdmiAudioPlugBroadcastReceiver)
        }
        externalSurroundSoundSettingObserver?.unregister()
        registered = false
    }

    private fun onNewAudioCapabilities(newAudioCapabilities: AudioCapabilities) {
        if (registered && newAudioCapabilities != audioCapabilities) {
            audioCapabilities = newAudioCapabilities
            listener.onAudioCapabilitiesChanged(newAudioCapabilities)
        }
    }

    private inner class HdmiAudioPlugBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isInitialStickyBroadcast) {
                onNewAudioCapabilities(
                    AudioCapabilities.getCapabilitiesInternal(
                        context, intent, audioAttributes!!, routedDevice
                    )
                )
            }
        }
    }

    private inner class ExternalSurroundSoundSettingObserver(
        handler: Handler?,
        private val resolver: ContentResolver,
        private val settingUri: Uri
    ) :
        ContentObserver(handler) {
        fun register() {
            resolver.registerContentObserver(settingUri,  /* notifyForDescendants= */false, this)
        }

        fun unregister() {
            resolver.unregisterContentObserver(this)
        }

        override fun onChange(selfChange: Boolean) {
            onNewAudioCapabilities(
                AudioCapabilities.getCapabilitiesInternal(
                    context,
                    audioAttributes!!, routedDevice
                )
            )
        }
    }

    @RequiresApi(23)
    private inner class AudioDeviceCallbackV23 : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            onNewAudioCapabilities(
                AudioCapabilities.getCapabilitiesInternal(
                    context,
                    audioAttributes!!, routedDevice
                )
            )
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (Util.contains(removedDevices, routedDevice)) {
                routedDevice = null
            }
            onNewAudioCapabilities(
                AudioCapabilities.getCapabilitiesInternal(
                    context,
                    audioAttributes!!, routedDevice
                )
            )
        }
    }

    private object Api23 {
        @DoNotInline
        fun registerAudioDeviceCallback(
            context: Context, callback: AudioDeviceCallback?, handler: Handler?
        ) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Assertions.checkNotNull(audioManager).registerAudioDeviceCallback(callback, handler)
        }

        @DoNotInline
        fun unregisterAudioDeviceCallback(
            context: Context, callback: AudioDeviceCallback?
        ) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Assertions.checkNotNull(audioManager).unregisterAudioDeviceCallback(callback)
        }
    }
}