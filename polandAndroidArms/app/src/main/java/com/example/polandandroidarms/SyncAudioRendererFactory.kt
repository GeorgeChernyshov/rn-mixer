package com.example.polandandroidarms

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
class SyncAudioRendererFactory(private val context: Context) : DefaultRenderersFactory(context) {

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {

        val renderers: ArrayList<Renderer> = ArrayList()

        super.buildAudioSink(
            context,
            false,
            false
        )?.let {
            for (i in 0 until 1) {
                val audioRenderer = SyncMediaCodecAudioRenderer(
                    context = context,
                    codecAdapterFactory = codecAdapterFactory,
                    mediaCodecSelector = MediaCodecSelector.DEFAULT,
                    enableDecoderFallback = false,
                    eventHandler = eventHandler,
                    eventListener = audioRendererEventListener,
                    audioSink = it
                )

                renderers.add(audioRenderer)
            }
        }

        return renderers.toTypedArray()
    }
}