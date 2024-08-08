package com.example.polandandroidarms

import android.annotation.SuppressLint
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection.Definition
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import com.google.common.collect.ImmutableList

@UnstableApi
class SyncAudioTrackSelector : TrackSelector() {

    private val trackSelectionFactory = AdaptiveTrackSelection.Factory()
    private var currentRendererTrackGroups: Array<TrackGroupArray?>? = null

    override fun selectTracks(
        rendererCapabilities: Array<out RendererCapabilities>,
        trackGroups: TrackGroupArray,
        periodId: MediaPeriodId,
        timeline: Timeline
    ): TrackSelectorResult {
        val rendererTrackGroupCounts = IntArray(rendererCapabilities.size + 1)
        val rendererTrackGroups = Array<MutableList<TrackGroup>?>(
            rendererCapabilities.size + 1
        ) { ArrayList() }

        // Associate each track group to a preferred renderer, and evaluate the support that the
        // renderer provides for each track in the group.
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]

            val rendererIndex = with(rendererCapabilities) {
                indices.find {
                    rendererTrackGroupCounts[it] == 0
                } ?: size
            }

            // Stash the results.
            rendererTrackGroups[rendererIndex]!!.add(group)
            rendererTrackGroupCounts[rendererIndex]++
        }

        // Create a track group array for each renderer, and trim each rendererFormatSupports entry.
        val rendererTrackGroupArrays = arrayOfNulls<TrackGroupArray>(rendererCapabilities.size)
        val rendererNames = arrayOfNulls<String>(rendererCapabilities.size)
        val rendererTrackTypes = IntArray(rendererCapabilities.size)
        for (i in rendererCapabilities.indices) {
            rendererTrackGroupArrays[i] = TrackGroupArray(
                *rendererTrackGroups[i]!!.toTypedArray()
            )

            rendererNames[i] = rendererCapabilities[i].name
            rendererTrackTypes[i] = rendererCapabilities[i].trackType
        }

        // Not sure about this as well
        val result: Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>> =
            selectTracks(
                rendererTrackGroupArrays,
                periodId,
                timeline
            )

        val tracks = buildTracks(rendererTrackGroupArrays, result.second)

        return TrackSelectorResult(result.first, result.second, tracks, rendererTrackGroupArrays)
    }

    // TrackSelector implementation.
    override fun onSelectionActivated(info: Any?) {
        currentRendererTrackGroups = info as Array<TrackGroupArray?>?
    }

    // MappingTrackSelector implementation.
    private fun selectTracks(
        rendererTrackGroups: Array<TrackGroupArray?>,
        mediaPeriodId: MediaPeriodId,
        timeline: Timeline
    ): Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>> {
        val rendererCount = rendererTrackGroups.size
        val definitions = selectAllTracks(rendererTrackGroups)

        val rendererTrackSelections: Array<ExoTrackSelection?> =
            trackSelectionFactory.createTrackSelections(
                definitions, bandwidthMeter, mediaPeriodId, timeline
            )

        // Initialize the renderer configurations to the default configuration for all renderers with
        // selections, and null otherwise.
        val rendererConfigurations = arrayOfNulls<RendererConfiguration>(rendererCount)
        for (i in 0 until rendererCount) {
            val rendererEnabled = (rendererTrackSelections[i] != null)
            rendererConfigurations[i] = if (rendererEnabled) RendererConfiguration.DEFAULT else null
        }

        return Pair.create(rendererConfigurations, rendererTrackSelections)
    }

    // Track selection prior to overrides and disabled flags being applied.
    private fun selectAllTracks(
        rendererTrackGroups: Array<TrackGroupArray?>
    ): Array<Definition?> {
        val rendererCount = rendererTrackGroups.size
        val definitions =
            arrayOfNulls<Definition>(rendererCount)

        val selectedTracks = selectTracks(
            rendererTrackGroups
        )

        selectedTracks?.forEach { selectedAudio ->
            definitions[selectedAudio.second] = selectedAudio.first
        }

        return definitions
    }

    private fun selectTracks(
        rendererTrackGroups: Array<TrackGroupArray?>
    ): List<Pair<Definition, Int>>? {
        val possibleSelections = ArrayList<List<AudioTrackInfo>>()
        for (rendererIndex in rendererTrackGroups.indices) {
            val groups = rendererTrackGroups[rendererIndex]
            for (groupIndex in 0 until groups!!.length) {
                val trackGroup = groups[groupIndex]
                val trackInfos = AudioTrackInfo.createForTrackGroup(
                    rendererIndex,
                    trackGroup
                )

                val usedTrackInSelection = BooleanArray(trackGroup.length)
                for (trackIndex in 0 until trackGroup.length) {
                    val trackInfo = trackInfos[trackIndex]
                    if (usedTrackInSelection[trackIndex])
                        continue

                    val selection = ArrayList<AudioTrackInfo>()
                    selection.add(trackInfo!!)
                    for (i in trackIndex + 1 until trackGroup.length) {
                        val otherTrackInfo = trackInfos[i]
                        selection.add(otherTrackInfo)
                        usedTrackInSelection[i] = true
                    }
                    possibleSelections.add(selection)
                }
            }
        }
        if (possibleSelections.isEmpty()) {
            return null
        }

        return possibleSelections.map { selection ->
            val trackIndices = IntArray(selection.size)
            for (i in selection.indices) {
                trackIndices[i] = selection[i].trackIndex
            }
            val firstTrackInfo = selection[0]

            Pair.create(
                Definition(firstTrackInfo.trackGroup, *trackIndices),
                firstTrackInfo.rendererIndex
            )
        }
    }

    private fun buildTracks(
        rendererTrackGroups: Array<TrackGroupArray?>,
        selections: Array<out TrackSelection?>
    ): Tracks {
        val listSelections: Array<List<TrackSelection>> = Array(selections.size) { emptyList() }
        for (i in selections.indices) {
            val selection = selections[i]
            listSelections[i] =
                if (selection != null) ImmutableList.of(selection) else ImmutableList.of()
        }
        return buildTracks(rendererTrackGroups, listSelections)
    }

    @SuppressLint("WrongConstant")
    private fun buildTracks(
        rendererTrackGroups: Array<TrackGroupArray?>,
        selections: Array<List<TrackSelection>>
    ): Tracks {
        val trackGroups = ImmutableList.Builder<Tracks.Group>()
        for (rendererIndex in rendererTrackGroups.indices) {
            val trackGroupArray = rendererTrackGroups[rendererIndex]
            val rendererTrackSelections = selections[rendererIndex]
            for (groupIndex in 0 until trackGroupArray!!.length) {
                val trackGroup = trackGroupArray[groupIndex]
                val adaptiveSupported = true
                val trackSupport = IntArray(trackGroup.length) { 4 }
                val selected = BooleanArray(trackGroup.length)
                for (trackIndex in 0 until trackGroup.length) {
                    var isTrackSelected = false
                    for (i in rendererTrackSelections.indices) {
                        val trackSelection = rendererTrackSelections[i]
                        if (trackSelection.trackGroup == trackGroup && trackSelection.indexOf(
                                trackIndex
                            ) != C.INDEX_UNSET
                        ) {
                            isTrackSelected = true
                            break
                        }
                    }
                    selected[trackIndex] = isTrackSelected
                }
                trackGroups.add(Tracks.Group(trackGroup, adaptiveSupported, trackSupport, selected))
            }
        }

        return Tracks(trackGroups.build())
    }

    class AudioTrackInfo(
        val rendererIndex: Int,
        val trackGroup: TrackGroup,
        val trackIndex: Int
    ) {
        companion object {
            fun createForTrackGroup(
                rendererIndex: Int,
                trackGroup: TrackGroup
            ): ImmutableList<AudioTrackInfo> {
                val listBuilder: ImmutableList.Builder<AudioTrackInfo> = ImmutableList.builder()
                for (i in 0 until trackGroup.length) {
                    listBuilder.add(
                        AudioTrackInfo(
                            rendererIndex,
                            trackGroup,  /* trackIndex= */
                            i
                        )
                    )
                }
                return listBuilder.build()
            }
        }
    }
}