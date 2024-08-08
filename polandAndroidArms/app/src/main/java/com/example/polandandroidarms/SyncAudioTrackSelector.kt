package com.example.polandandroidarms

import android.annotation.SuppressLint
import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.C.FormatSupport
import androidx.media3.common.C.TrackType
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection.Definition
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelectionUtil
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import java.util.Collections
import kotlin.math.max

@UnstableApi
class SyncAudioTrackSelector : TrackSelector() {

    private val trackSelectionFactory = AdaptiveTrackSelection.Factory()
    private var currentMappedTrackInfo: MappedTrackInfo? = null

    override fun selectTracks(
        rendererCapabilities: Array<out RendererCapabilities>,
        trackGroups: TrackGroupArray,
        periodId: MediaPeriodId,
        timeline: Timeline
    ): TrackSelectorResult {
        // do I need this?
        val rendererTrackGroupCounts = IntArray(rendererCapabilities.size + 1)
        val rendererTrackGroups = Array<MutableList<TrackGroup>?>(
            rendererCapabilities.size + 1
        ) { ArrayList() }

        // Associate each track group to a preferred renderer, and evaluate the support that the
        // renderer provides for each track in the group.
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]

            // For us this is just "first free one"
            val rendererIndex = findRenderer(
                rendererCapabilities,
                group,
                rendererTrackGroupCounts
            )

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
//        // Create a track group array for each renderer, and trim each rendererFormatSupports entry.
//        TrackGroupArray[] rendererTrackGroupArrays = new TrackGroupArray[rendererCapabilities.length];
//        String[] rendererNames = new String[rendererCapabilities.length];
//        int[] rendererTrackTypes = new int[rendererCapabilities.length];
//        for (int i = 0; i < rendererCapabilities.length; i++) {
//            int rendererTrackGroupCount = rendererTrackGroupCounts[i];
//            rendererTrackGroupArrays[i] =
//                new TrackGroupArray(
//                        Util.nullSafeArrayCopy(rendererTrackGroups[i], rendererTrackGroupCount));
//            rendererFormatSupports[i] =
//                Util.nullSafeArrayCopy(rendererFormatSupports[i], rendererTrackGroupCount);
//            rendererNames[i] = rendererCapabilities[i].getName();
//            rendererTrackTypes[i] = rendererCapabilities[i].getTrackType();
//        }

        // Package up the track information and selections.
        // Not sure about this
        val mappedTrackInfo = MappedTrackInfo(
            rendererTrackTypes,
            rendererTrackGroupArrays
        )

        // Not sure about this as well
        val result: Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>> =
            selectTracks(
                mappedTrackInfo,
                periodId,
                timeline
            )

        val tracks = buildTracks(mappedTrackInfo, result.second)

        return TrackSelectorResult(result.first, result.second, tracks, mappedTrackInfo)

        //
//        Tracks tracks = TrackSelectionUtil.buildTracks(mappedTrackInfo, result.second);
//
//        return new TrackSelectorResult(result.first, result.second, tracks, mappedTrackInfo);
    }

    // TrackSelector implementation.
    override fun onSelectionActivated(info: Any?) {
        currentMappedTrackInfo = info as MappedTrackInfo?
    }

    // MappingTrackSelector implementation.
    private fun selectTracks(
        mappedTrackInfo: MappedTrackInfo,
        mediaPeriodId: MediaPeriodId,
        timeline: Timeline
    ): Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>> {
        val rendererCount = mappedTrackInfo.rendererCount
        val definitions = selectAllTracks(mappedTrackInfo)

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

    private fun createTrackSelections(
        definitions: Array<Definition?>,
        bandwidthMeter: BandwidthMeter,
        mediaPeriodId: MediaPeriodId,
        timeline: Timeline
    ): Array<ExoTrackSelection?> {
        val nonNullSelections = trackSelectionFactory.createTrackSelections(
            definitions.filterNotNull().toTypedArray(),
            bandwidthMeter,
            mediaPeriodId,
            timeline
        )

        val result = arrayOfNulls<ExoTrackSelection>(definitions.size)
        for (selectionIdx in nonNullSelections.indices)
            result[selectionIdx] = nonNullSelections[selectionIdx]

        return result
    }

    // Track selection prior to overrides and disabled flags being applied.
    private fun selectAllTracks(
        mappedTrackInfo: MappedTrackInfo
    ): Array<Definition?> {
        val rendererCount = mappedTrackInfo.rendererCount
        val definitions =
            arrayOfNulls<Definition>(rendererCount)

        val selectedAudio = selectAudioTrack(
            mappedTrackInfo
        )

        if (selectedAudio != null) {
            definitions[selectedAudio.second] = selectedAudio.first
        }

        return definitions
    }

    // Audio track selection implementation.
    @Throws(ExoPlaybackException::class)
    private fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo
    ): Pair<Definition, Int>? {
        return selectTracksForType(
            mappedTrackInfo,
            object : AudioTrackInfo.Factory {
                override fun create(
                    rendererIndex: Int,
                    trackGroup: TrackGroup,
                    formatSupports: IntArray?
                ): List<AudioTrackInfo> {
                    return AudioTrackInfo.createForTrackGroup(
                        rendererIndex,
                        trackGroup
                    )
                }
            }
        ) { infos1, infos2 ->
            AudioTrackInfo.compareSelections(
                infos1,
                infos2
            )
        }
    }

    private fun selectTracksForType(
        mappedTrackInfo: MappedTrackInfo,
        trackInfoFactory: AudioTrackInfo.Factory,
        selectionComparator: Comparator<List<AudioTrackInfo>>
    ): Pair<Definition, Int>? {
        val possibleSelections = ArrayList<List<AudioTrackInfo>>()
        val rendererCount = mappedTrackInfo.rendererCount
        for (rendererIndex in 0 until rendererCount) {
            val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val trackGroup = groups[groupIndex]
                val trackInfos = trackInfoFactory.create(rendererIndex, trackGroup, IntArray(0))
                val usedTrackInSelection = BooleanArray(trackGroup.length)
                for (trackIndex in 0 until trackGroup.length) {
                    val trackInfo = trackInfos?.get(trackIndex)
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
        val bestSelection = Collections.max(possibleSelections, selectionComparator)
        val trackIndices = IntArray(bestSelection.size)
        for (i in bestSelection.indices) {
            trackIndices[i] = bestSelection[i].trackIndex
        }
        val firstTrackInfo = bestSelection[0]
        return Pair.create(
            Definition(firstTrackInfo.trackGroup, *trackIndices),
            firstTrackInfo.rendererIndex
        )
    }

    private fun findRenderer(
        rendererCapabilities: Array<out RendererCapabilities>,
        group: TrackGroup,
        rendererTrackGroupCounts: IntArray
    ): Int {
        var bestRendererIndex = rendererCapabilities.size
        var bestFormatSupportLevel: @FormatSupport Int = C.FORMAT_UNSUPPORTED_TYPE
        var bestRendererIsUnassociated = true
        for (rendererIndex in rendererCapabilities.indices) {
            val rendererCapability = rendererCapabilities[rendererIndex]
            var formatSupportLevel: @FormatSupport Int = C.FORMAT_UNSUPPORTED_TYPE
            for (trackIndex in 0 until group.length) {
                val trackFormatSupportLevel: @FormatSupport Int =
                    RendererCapabilities.getFormatSupport(
                        rendererCapability.supportsFormat(group.getFormat(trackIndex))
                    )
                formatSupportLevel =
                    max(formatSupportLevel.toDouble(), trackFormatSupportLevel.toDouble()).toInt()
            }
            val rendererIsUnassociated = rendererTrackGroupCounts[rendererIndex] == 0
            if (formatSupportLevel > bestFormatSupportLevel
                || (formatSupportLevel == bestFormatSupportLevel
                        && !bestRendererIsUnassociated
                        && rendererIsUnassociated)
            ) {
                bestRendererIndex = rendererIndex
                bestFormatSupportLevel = formatSupportLevel
                bestRendererIsUnassociated = rendererIsUnassociated
            }
        }
        return bestRendererIndex
    }

    private fun buildTracks(
        mappedTrackInfo: MappedTrackInfo?,
        selections: Array<out TrackSelection?>
    ): Tracks {
        val listSelections: Array<List<TrackSelection>> = Array(selections.size) { emptyList() }
        for (i in selections.indices) {
            val selection = selections[i]
            listSelections[i] =
                if (selection != null) ImmutableList.of(selection) else ImmutableList.of()
        }
        return buildTracks(mappedTrackInfo!!, listSelections)
    }

    @SuppressLint("WrongConstant")
    private fun buildTracks(
        mappedTrackInfo: MappedTrackInfo,
        selections: Array<List<TrackSelection>>
    ): Tracks {
        val trackGroups = ImmutableList.Builder<Tracks.Group>()
        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
            val trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex)
            val rendererTrackSelections = selections[rendererIndex]
            for (groupIndex in 0 until trackGroupArray.length) {
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

    /** Provides mapped track information for each renderer.  */
    class MappedTrackInfo(
        private val rendererTrackTypes: IntArray,
        private val rendererTrackGroups: Array<TrackGroupArray?>
    ) {
        /** Returns the number of renderers.  */
        val rendererCount: Int = rendererTrackTypes.size

        /**
         * Returns the [TrackGroup]s mapped to the renderer at the specified index.
         *
         * @param rendererIndex The renderer index.
         * @return The corresponding [TrackGroup]s.
         */
        fun getTrackGroups(rendererIndex: Int): TrackGroupArray {
            return rendererTrackGroups[rendererIndex]!!
        }
    }

    class AudioTrackInfo(
        val rendererIndex: Int,
        val trackGroup: TrackGroup,
        val trackIndex: Int
    ) : Comparable<AudioTrackInfo?> {
        private val isWithinConstraints: Boolean
        private val isWithinRendererCapabilities: Boolean
        private val hasMainOrNoRoleFlag: Boolean
        private val isDefaultSelectionFlag: Boolean
        private val channelCount: Int
        private val sampleRate: Int
        private val bitrate: Int
        private val preferredMimeTypeMatchIndex: Int

        interface Factory {
            fun create(
                rendererIndex: Int,
                trackGroup: TrackGroup,
                formatSupports: IntArray?
            ): List<AudioTrackInfo>?
        }

        val format: Format = trackGroup.getFormat(trackIndex)

        init {
            @SuppressLint("WrongConstant") val requiredAdaptiveSupport =
                RendererCapabilities.ADAPTIVE_SEAMLESS

            isWithinRendererCapabilities = true
            hasMainOrNoRoleFlag =
                format.roleFlags == 0 || (format.roleFlags and C.ROLE_FLAG_MAIN) != 0
            isDefaultSelectionFlag = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
            channelCount = format.channelCount
            sampleRate = format.sampleRate
            bitrate = format.bitrate
            isWithinConstraints = true

            val bestMimeTypeMatchIndex = Int.MAX_VALUE

            preferredMimeTypeMatchIndex = bestMimeTypeMatchIndex
        }

        override fun compareTo(other: AudioTrackInfo?): Int {
            return 0
        }

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

            fun compareSelections(
                infos1: List<AudioTrackInfo>,
                infos2: List<AudioTrackInfo>
            ): Int {
                // Compare best tracks of each selection with each other.
                return Collections.max(infos1).compareTo(Collections.max(infos2))
            }
        }
    }
}
