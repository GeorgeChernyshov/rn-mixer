package com.example.polandandroidarms

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.polandandroidarms.ui.theme.PolandAndroidArmsTheme
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch

data class AudioTrack(
    val fileName: String,
    val player: ExoPlayer,
    var volume: Float = 1.0f, // Default volume
    var pan: Float = 0.0f     // Default pan (0 is centered)
)

class MainActivity : ComponentActivity() {

    private val audioFileURLsList: List<URL> = listOf(
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/236/original/Way_Maker__0_-_E_-_Original_--_11-Lead_Vox.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/227/original/Way_Maker__0_-_E_-_Original_--_3-Drums.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/229/original/Way_Maker__0_-_E_-_Original_--_4-Percussion.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/232/original/Way_Maker__0_-_E_-_Original_--_5-Bass.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/230/original/Way_Maker__0_-_E_-_Original_--_6-Acoustic.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/228/original/Way_Maker__0_-_E_-_Original_--_2-Guide.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/234/original/Way_Maker__0_-_E_-_Original_--_7-Electric_1.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/235/original/Way_Maker__0_-_E_-_Original_--_8-Electric_2.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/237/original/Way_Maker__0_-_E_-_Original_--_9-Main_Keys.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/231/original/Way_Maker__0_-_E_-_Original_--_10-Aux_Keys.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/238/original/Way_Maker__0_-_E_-_Original_--_12-Soprano_Vox.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/239/original/Way_Maker__0_-_E_-_Original_--_13-Tenor_Vox.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/233/original/Way_Maker__0_-_E_-_Original_--_14-Choir.m4a"),
    )

    // Remove before release build
    private val audioClicksURLsList: List<URL> = listOf(
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a"),
        URL("https://cdn.worshiponline.com/estp-public/song_audio_mixer_tracks/audios/000/034/226/original/Way_Maker__0_-_E_-_Original_--_1-Click.m4a")
    )

    private var isMixPaused = false
    private var pausedTime = 0
    private var playerDeviceCurrTime = 0L

    private val pickAudioFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // Handle the selected audio file here
        }
    }

    private var audioTracks = mutableStateListOf<AudioTrack>()
    private var downloadProgress by mutableDoubleStateOf(0.0)
    private var isMixBtnClicked by mutableStateOf(false)
    private var isMasterControlShowing by mutableStateOf(false)
    private var playbackProgress by mutableFloatStateOf(0f)
    private var maxPlaybackDuration by mutableIntStateOf(0)

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> handleAudioFocusLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handleAudioFocusLossTransient()
            AudioManager.AUDIOFOCUS_GAIN -> handleAudioFocusGain()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun handleAudioFocusLoss() {
        // Pause or stop playback
        audioTracks.forEach { it.player.pause() }
    }

    private fun handleAudioFocusLossTransient() {
        // Pause playback
        audioTracks.forEach { it.player.pause() }
    }

    private fun handleAudioFocusGain() {
        // Resume playback
        audioTracks.forEach { it.player.play() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PolandAndroidArmsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun handlePlayMix() {
        if (requestAudioFocus()) {
            // Move to a foreground service if needed
            startService(Intent(this, AudioPlaybackService::class.java))

            startProgressUpdateTimer()
            isMixBtnClicked = false

            val latch = CountDownLatch(audioTracks.size)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                audioTracks.forEach { track ->
                    launch {
                        try {
                            withContext(Dispatchers.Main) {
                                track.player.setMediaItem(MediaItem.fromUri(Uri.parse(track.fileName)))
                                track.player.prepare()
                                latch.countDown()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                latch.await()

                withContext(Dispatchers.Main) {
                    val startTime = SystemClock.uptimeMillis() + 1000

                    audioTracks.forEach { track ->
                        track.player.seekTo(0)
                    }

                    val actualStartDelay = startTime - SystemClock.uptimeMillis()
                    if (actualStartDelay > 0) {
                        delay(actualStartDelay)
                    }

                    audioTracks.forEachIndexed { index, track ->
                        track.player.play()
                        Log.i(TAG, "Track #${index} started playing")
                        startAmplitudeUpdate(track.fileName)
                    }

                    logTrackTime(audioTracks)

                    maxPlaybackDuration = audioTracks.maxOfOrNull { it.player.duration }?.toInt() ?: 0
                    startPlaybackProgressUpdater()
                }
            }

            isMasterControlShowing = true
        } else {
            Toast.makeText(this, "Failed to gain audio focus", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDownloadTracks(trackURLs: List<URL>) {
        resetApp()
        val urls = trackURLs
        val totalFiles = urls.size
        var downloadedFiles = 0

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val deferreds = urls.map { url ->
                async {
                    downloadFile(url)?.let { file ->
                        withContext(Dispatchers.Main) {
                            val exoPlayer = ExoPlayer.Builder(this@MainActivity).build().apply {
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                prepare()
                            }
                            audioTracks.add(AudioTrack(file.absolutePath, exoPlayer))
                            getAudioProperties(url)
                            downloadedFiles += 1
                            downloadProgress = downloadedFiles.toDouble() / totalFiles
                        }
                    }
                }
            }
            deferreds.awaitAll()
            withContext(Dispatchers.Main) {
                isMixBtnClicked = true
                downloadProgress = 1.0
            }
        }
    }

    private fun handleResumePauseMix() {
        // Handle resumePauseMix action
        isMixPaused = !isMixPaused

        if (isMixPaused) {
            audioTracks.forEachIndexed { index, track ->
                track.player.pause()
                Log.i(TAG, "Track #${index} paused")
                pausedTime = track.player.currentPosition.toInt()
                playerDeviceCurrTime = SystemClock.uptimeMillis()
            }
            Log.i(TAG, "All players paused successfully at $pausedTime ms with device time $playerDeviceCurrTime ms")
        } else {
            val startDelay: Long = 1000 // Delay to ensure all players are ready
            val startTime = SystemClock.uptimeMillis()

            audioTracks.forEach { track ->
                track.player.seekTo(pausedTime.toLong())
            }

            val actualStartDelay = startTime + startDelay - SystemClock.uptimeMillis()
            if (actualStartDelay > 0) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(actualStartDelay)
                    audioTracks.forEachIndexed { index, track ->
                        track.player.play()
                        Log.i(TAG, "Track #${index} resumed")
                        startAmplitudeUpdate(track.fileName)
                    }

                    logTrackTime(audioTracks)
                }
            } else {
                audioTracks.forEachIndexed { index, track ->
                    track.player.play()
                    Log.i(TAG, "Track #${index} resumed")
                    startAmplitudeUpdate(track.fileName)
                }

                logTrackTime(audioTracks)
            }
        }
    }

    private fun handlePickAudioMix() {
        pickAudioFileLauncher.launch("audio/*")
    }

    private fun resetApp() {
        // Reset application state
        // Release and clear all media players
        audioTracks.forEach { track ->
            track.player.release()
        }
        audioTracks.clear()

        // Reset download progress
        downloadProgress = 0.0

        // Reset boolean flags
        isMixBtnClicked = false
        isMasterControlShowing = false
        isMixPaused = false

        // Reset paused time and current player time
        pausedTime = 0
        playerDeviceCurrTime = 0L

        abandonAudioFocus()
    }

    private fun getAudioProperties(url: URL) {
        // Implement logic to get audio properties
    }

    private fun downloadFile(url: URL): File? {
        return try {
            val fileName = url.path.substring(url.path.lastIndexOf('/') + 1)
            val file = File(cacheDir, fileName)
            if (file.exists() && file.totalSpace > 0)
                return file

            url.openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startAmplitudeUpdate(fileName: String) {
        // Implement logic to start updating amplitude
    }

    private fun startProgressUpdateTimer() {
        // Implement logic to start updating progress
    }

    private fun startPlaybackProgressUpdater() {
        CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val currentPosition = audioTracks.firstOrNull()?.player?.currentPosition ?: 0
                playbackProgress = currentPosition.toFloat() / maxPlaybackDuration
                delay(100) // Update every 100ms
            }
        }
    }

    private fun handleSeekToProgress(progress: Float) {
        val newPosition = (progress * maxPlaybackDuration).toInt()
        audioTracks.forEach { track ->
            track.player.seekTo(newPosition.toLong())
        }
        pausedTime = newPosition
        playbackProgress = progress
    }

    private fun logTrackTime(tracks: List<AudioTrack>) {
        var firstTrackPosition: Long? = null
        var firstTrackLogTime: Long? = null

        audioTracks.forEachIndexed { index, track ->
            val position = track.player.currentPosition
            val currentTime = System.currentTimeMillis()
            var logMessage = "Track #$index is at $position. System time is $currentTime."

            if (firstTrackLogTime != null && firstTrackPosition != null) {
                val positionDesync = position - firstTrackPosition!!
                val timeDesync = currentTime - firstTrackLogTime!!
                val totalDesync = positionDesync - timeDesync
                val totalDesyncString = if (totalDesync > 0)
                    "+$totalDesync"
                else totalDesync.toString()

                logMessage += " Desync is at $totalDesyncString."
            } else {
                firstTrackPosition = position
                firstTrackLogTime = currentTime
            }

            Log.i(TAG, logMessage)
        }
    }

    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Greeting(name = "Android")
            PlaybackSlider()
            Button(onClick = { handlePlayMix() }) {
                Text(text = "Play Mix")
            }
            Button(onClick = { handleDownloadTracks(audioFileURLsList) }) {
                Text(text = "Download Tracks")
            }
            Button(onClick = { handleDownloadTracks(audioClicksURLsList) }) {
                Text(text = "Download Clicks 10 times (good for hearing desync)")
            }
            Button(onClick = { handleResumePauseMix() }) {
                Text(text = "Resume/Pause Mix")
            }
            Button(onClick = { handlePickAudioMix() }) {
                Text(text = "Pick Audio Mix")
            }
            Text(text = "Download Progress: ${(downloadProgress * 100).toInt()}%")

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(audioTracks) { index, track ->
                    var volume by remember { mutableFloatStateOf(track.volume) }
                    var pan by remember { mutableFloatStateOf(track.pan) }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "${index + 1}. ${track.fileName.substringAfterLast("/")}")
                        Slider(
                            value = volume,
                            onValueChange = { newVolume ->
                                volume = newVolume
                                track.volume = newVolume
                                track.player.volume = newVolume
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(text = "Volume: ${(volume * 100).toInt()}%")
                        Slider(
                            value = pan,
                            onValueChange = { newPan ->
                                pan = newPan
                                track.pan = newPan
                                // Adjust pan (left-right balance) here
                                val leftVolume = if (newPan < 0) 1f else 1 - newPan
                                val rightVolume = if (newPan > 0) 1f else 1 + newPan

                                // TODO("Add proper pan support")
                                track.player.setVolume(leftVolume * volume)
                            },
                            valueRange = -1f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(text = "Pan: $pan")
                    }
                }
            }
        }
    }

    @Composable
    fun PlaybackSlider() {
        val context = LocalContext.current
        var isNowProgress by remember(playbackProgress) { mutableFloatStateOf(playbackProgress) }
        var isTracking by remember { mutableStateOf(false) }

        Text(text = "Mix Playback", color = Color(0xFF191970))
        Slider(
            value = isNowProgress,
            onValueChange = { newVal ->
                playbackProgress = newVal
                isNowProgress = newVal
                if (!isTracking) {
                    Toast.makeText(context, "Started tracking touch ${(newVal * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                    isTracking = true
                    handleResumePauseMix()
                }

                handleSeekToProgress(newVal)
            },
            onValueChangeFinished = {
                isTracking = false
                handleResumePauseMix()
                Toast.makeText(context, "Stopped tracking touch", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(8.dp),
            enabled = true
        )
    }

    @Composable
    fun Greeting(name: String, modifier: Modifier = Modifier) {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        PolandAndroidArmsTheme {
            Greeting("Android")
        }
    }

    companion object {
        val TAG = "player"
    }
}

