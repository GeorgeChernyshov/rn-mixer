package com.example.polandandroidarms

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
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
            for (i in 0 until 20) {
                buildAudioRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    MediaCodecSelector.DEFAULT,
                    false,
                    it,
                    eventHandler,
                    audioRendererEventListener,
                    renderers
                )
            }
        }

        return renderers.toTypedArray()
    }
}