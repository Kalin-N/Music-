package com.example.musicapp

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID

// --- ЦВЕТОВЕ ---
val JungleGreen = Color(0xFF228B22)

// --- МОДЕЛИ ---
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val uri: String,
    val isVideo: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis()
)

data class Folder(val name: String, val songs: List<Song>)
data class Album(val name: String, val artist: String, val songs: List<Song>)
data class Artist(val name: String, val songs: List<Song>)
data class Playlist(val id: String = UUID.randomUUID().toString(), val name: String, val songs: List<Song>)

// --- ГЛАВЕН КЛАС (VIEWMODEL) ---
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("MusicAppPrefs", Context.MODE_PRIVATE)
    
    val currentUserEmail = mutableStateOf<String?>(prefs.getString("last_user_email", null))
    val currentUserName = mutableStateOf<String?>(prefs.getString("last_user_name", null))
    val isLoginVisible = mutableStateOf(currentUserEmail.value == null)
    
    val currentLibraryView = mutableStateOf("CATEGORIES")
    val currentFolderView = mutableStateOf("LIST")
    val isNowPlayingFull = mutableStateOf(false)
    val currentSong = mutableStateOf<Song?>(null)
    val isPlaying = mutableStateOf(false)
    val playerPosition = mutableFloatStateOf(0f)
    val playerDuration = mutableFloatStateOf(0f)
    val isShuffle = mutableStateOf(false)

    // Lyrics
    val currentLyrics = mutableStateOf<String?>(null)
    val isLyricsLoading = mutableStateOf(false)
    val isLyricsVisible = mutableStateOf(false)

    // Equalizer
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    val isEqualizerEnabled = mutableStateOf(prefs.getBoolean("eq_enabled", false))
    val bassBoostLevel = mutableIntStateOf(prefs.getInt("bass_boost", 0))
    val selectedPreset = mutableStateOf(prefs.getString("eq_preset", "Normal") ?: "Normal")
    val isEqDialogVisible = mutableStateOf(false)

    // Данни
    val filteredSongs = mutableStateListOf<Song>()
    val folders = mutableStateListOf<Folder>()
    val artists = mutableStateListOf<Artist>()
    val albums = mutableStateListOf<Album>()
    val favoriteSongs = mutableStateListOf<Song>()
    val playlists = mutableStateListOf<Playlist>()
    
    // Category Order
    val categoryOrder = mutableStateListOf<String>()

    // Настройки
    val currentLanguage = mutableStateOf(prefs.getString("lang", "English") ?: "English")
    val isLanguageDialogVisible = mutableStateOf(false)
    val sleepTimerValue = mutableFloatStateOf(0f)
    val isSleepTimerActive = mutableStateOf(false)
    val sleepTimerRemaining = mutableStateOf("")
    val autoScan = mutableStateOf(prefs.getBoolean("auto_scan", true))
    val darkMode = mutableStateOf(prefs.getBoolean("dark_mode", true))
    
    // Sorting
    val currentSortOrder = mutableStateOf(prefs.getString("sort_order", "Title") ?: "Title")
    val isSortDialogVisible = mutableStateOf(false)

    // Shazam Logic
    val isRecognizing = mutableStateOf(false)
    val recognitionResult = mutableStateOf<String?>(null)

    val selectedFolder = mutableStateOf<Folder?>(null)
    val selectedAlbum = mutableStateOf<Album?>(null)
    val selectedArtist = mutableStateOf<Artist?>(null)
    val selectedPlaylist = mutableStateOf<Playlist?>(null)

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var originalQueue: List<Song> = emptyList()
    private var currentQueue: List<Song> = emptyList()

    init {
        loadCategoryOrder()
        if (folders.isEmpty()) folders.add(Folder("My Music", emptyList()))
        loadUserFavorites()
        loadUserPlaylists()
    }

    private fun loadCategoryOrder() {
        val saved = prefs.getString("category_order", "All Songs,Artists,Albums,Favorites,Playlists") ?: "All Songs,Artists,Albums,Favorites,Playlists"
        categoryOrder.clear()
        categoryOrder.addAll(saved.split(","))
    }

    private fun saveCategoryOrder() {
        prefs.edit().putString("category_order", categoryOrder.joinToString(",")).apply()
    }

    fun moveCategoryUp(index: Int) {
        if (index > 0) {
            val cat = categoryOrder.removeAt(index)
            categoryOrder.add(index - 1, cat)
            saveCategoryOrder()
        }
    }

    fun moveCategoryDown(index: Int) {
        if (index < categoryOrder.size - 1) {
            val cat = categoryOrder.removeAt(index)
            categoryOrder.add(index + 1, cat)
            saveCategoryOrder()
        }
    }

    // --- АКАУНТИ И ЗАПАЗВАНЕ ---
    fun register(email: String, pass: String, name: String) {
        val e = email.trim().lowercase()
        val n = name.trim()
        if (e.isNotEmpty() && pass.isNotEmpty() && n.isNotEmpty()) {
            if (prefs.contains("user_pass_$e")) {
                Toast.makeText(getApplication(), "User already exists!", Toast.LENGTH_SHORT).show()
                return
            }
            val allPrefs = prefs.all
            for (key in allPrefs.keys) {
                if (key.startsWith("user_name_") && allPrefs[key] == n) {
                    Toast.makeText(getApplication(), "Name '$n' is already taken!", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            prefs.edit().putString("user_pass_$e", pass).apply()
            prefs.edit().putString("user_name_$e", n).apply()
            login(e, pass)
        }
    }

    fun login(email: String, pass: String) {
        val e = email.trim().lowercase()
        val storedPass = prefs.getString("user_pass_$e", null)
        if (storedPass == pass) {
            val name = prefs.getString("user_name_$e", "User") ?: "User"
            currentUserEmail.value = e
            currentUserName.value = name
            prefs.edit().putString("last_user_email", e).apply()
            prefs.edit().putString("last_user_name", name).apply()
            isLoginVisible.value = false
            loadUserFavorites()
            loadUserPlaylists()
            Toast.makeText(getApplication(), "Welcome, $name!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "Invalid email or password", Toast.LENGTH_SHORT).show()
        }
    }

    fun logout() {
        currentUserEmail.value = null
        currentUserName.value = null
        prefs.edit().remove("last_user_email").apply()
        prefs.edit().remove("last_user_name").apply()
        isLoginVisible.value = true
        favoriteSongs.clear()
        playlists.clear()
    }

    private fun loadUserFavorites() {
        val user = currentUserEmail.value ?: return
        val favIds = prefs.getStringSet("favs_$user", emptySet()) ?: emptySet()
        favoriteSongs.clear()
        favoriteSongs.addAll(filteredSongs.filter { favIds.contains(it.id.toString()) })
    }

    private fun saveUserFavorites() {
        val user = currentUserEmail.value ?: return
        val favIds = favoriteSongs.map { it.id.toString() }.toSet()
        prefs.edit().putStringSet("favs_$user", favIds).apply()
    }

    fun toggleFavorite(song: Song) {
        if (favoriteSongs.any { it.id == song.id }) {
            favoriteSongs.removeAll { it.id == song.id }
        } else {
            favoriteSongs.add(song)
        }
        saveUserFavorites()
    }

    fun isSongFavorite(song: Song) = favoriteSongs.any { it.id == song.id }

    private fun loadUserPlaylists() {
        val user = currentUserEmail.value ?: return
        val data = prefs.getStringSet("playlists_$user", emptySet()) ?: emptySet()
        playlists.clear()
        data.forEach { line ->
            val parts = line.split("::")
            if (parts.size >= 2) {
                val name = parts[0]
                val ids = parts[1].split(",").filter { it.isNotEmpty() }
                val songList = filteredSongs.filter { ids.contains(it.id.toString()) }
                playlists.add(Playlist(name = name, songs = songList))
            }
        }
    }

    private fun saveUserPlaylists() {
        val user = currentUserEmail.value ?: return
        val set = playlists.map { p -> "${p.name}::${p.songs.joinToString(",") { it.id.toString() }}" }.toSet()
        prefs.edit().putStringSet("playlists_$user", set).apply()
    }

    fun createPlaylist(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && playlists.none { it.name == n }) {
            playlists.add(Playlist(name = n, songs = emptyList()))
            saveUserPlaylists()
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        playlists.removeAll { it.id == playlist.id }
        saveUserPlaylists()
    }

    fun addSongToPlaylist(song: Song, playlist: Playlist) {
        val idx = playlists.indexOfFirst { it.id == playlist.id }
        if (idx != -1 && !playlists[idx].songs.any { it.id == song.id }) {
            playlists[idx] = playlists[idx].copy(songs = playlists[idx].songs + song)
            saveUserPlaylists()
            Toast.makeText(getApplication(), "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeSongFromPlaylist(song: Song, playlist: Playlist) {
        val idx = playlists.indexOfFirst { it.id == playlist.id }
        if (idx != -1) {
            playlists[idx] = playlists[idx].copy(songs = playlists[idx].songs.filter { it.id != song.id })
            saveUserPlaylists()
            if (selectedPlaylist.value?.id == playlist.id) selectedPlaylist.value = playlists[idx]
        }
    }

    // --- МУЗИКАЛНА ЛОГИКА ---
    fun scanForMusic() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val songs = mutableListOf<Song>()
                val resolver = getApplication<Application>().contentResolver
                
                // Scan Audio
                val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val audioProj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED)
                resolver.query(audioUri, audioProj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(5).orEmpty()
                        if (path.contains("Record", true) || path.contains("Call", true)) continue
                        val id = cursor.getLong(0)
                        songs.add(Song(id, cursor.getString(1) ?: "Unknown", cursor.getString(2) ?: "Unknown", cursor.getString(3) ?: "Unknown", cursor.getLong(4), path, ContentUris.withAppendedId(audioUri, id).toString(), false, cursor.getLong(6)))
                    }
                }
                
                // Scan Video
                val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val videoProj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.ARTIST, MediaStore.Video.Media.ALBUM, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_ADDED)
                resolver.query(videoUri, videoProj, null, null, "${MediaStore.Video.Media.TITLE} ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        songs.add(Song(id, cursor.getString(1) ?: "Video", cursor.getString(2) ?: "Unknown", "Videos", cursor.getLong(4), cursor.getString(5).orEmpty(), ContentUris.withAppendedId(videoUri, id).toString(), true, cursor.getLong(6)))
                    }
                }
                songs
            }
            applySort(result)
            folders.clear(); result.groupBy { File(it.path).parentFile?.name ?: "Music" }.forEach { (n, s) -> folders.add(Folder(n, s)) }
            if (folders.isEmpty()) folders.add(Folder("My Music", emptyList()))
            artists.clear(); result.groupBy { it.artist }.forEach { (n, s) -> artists.add(Artist(n, s)) }
            albums.clear(); result.groupBy { it.album }.forEach { (n, s) -> albums.add(Album(n, s.firstOrNull()?.artist ?: "Unknown", s)) }
            loadUserFavorites()
            loadUserPlaylists()
        }
    }

    private fun applySort(songs: List<Song>) {
        val sorted = when (currentSortOrder.value) {
            "Title" -> songs.sortedBy { it.title }
            "Artist" -> songs.sortedBy { it.artist }
            "Date Added" -> songs.sortedByDescending { it.dateAdded }
            "Duration" -> songs.sortedByDescending { it.duration }
            else -> songs.sortedBy { it.title }
        }
        filteredSongs.clear()
        filteredSongs.addAll(sorted)
    }

    fun setSortOrder(order: String) {
        currentSortOrder.value = order
        prefs.edit().putString("sort_order", order).apply()
        applySort(filteredSongs.toList())
    }

    fun updateSongMetadata(song: Song, newTitle: String, newArtist: String) {
        val idx = filteredSongs.indexOfFirst { it.id == song.id }
        if (idx != -1) {
            val updated = filteredSongs[idx].copy(title = newTitle, artist = newArtist)
            filteredSongs[idx] = updated
            // Update other collections
            if (currentSong.value?.id == song.id) currentSong.value = updated
            // Note: Real ID3 editing would require writing to the file, but here we just update local state for the session
            // To persist this properly, we'd need to store overrides in SharedPreferences
            val overrides = prefs.getStringSet("meta_overrides", emptySet())?.toMutableSet() ?: mutableSetOf()
            overrides.removeIf { it.startsWith("${song.id}::") }
            overrides.add("${song.id}::$newTitle::$newArtist")
            prefs.edit().putStringSet("meta_overrides", overrides).apply()
            
            // Re-group
            val result = filteredSongs.toList()
            folders.clear(); result.groupBy { File(it.path).parentFile?.name ?: "Music" }.forEach { (n, s) -> folders.add(Folder(n, s)) }
            artists.clear(); result.groupBy { it.artist }.forEach { (n, s) -> artists.add(Artist(n, s)) }
            albums.clear(); result.groupBy { it.album }.forEach { (n, s) -> albums.add(Album(n, s.firstOrNull()?.artist ?: "Unknown", s)) }
        }
    }

    fun addSongManually(uri: Uri, context: Context) {
        viewModelScope.launch {
            val song = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: uri.lastPathSegment ?: "New File"
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                    val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                    retriever.release()
                    Song(System.currentTimeMillis(), title, artist, if(isVideo) "Videos" else "My Music", duration, "", uri.toString(), isVideo, System.currentTimeMillis() / 1000)
                } catch (e: Exception) {
                    Song(System.currentTimeMillis(), uri.lastPathSegment ?: "New File", "Unknown", "My Music", 0L, "", uri.toString(), false, System.currentTimeMillis() / 1000)
                }
            }
            filteredSongs.add(song)
            applySort(filteredSongs.toList())
            val folderName = if (song.isVideo) "Videos" else "My Music"
            val idx = folders.indexOfFirst { it.name == folderName }
            if (idx != -1) folders[idx] = folders[idx].copy(songs = folders[idx].songs + song)
            else folders.add(Folder(folderName, listOf(song)))
        }
    }

    fun playSong(song: Song, sourceQueue: List<Song> = filteredSongs) {
        originalQueue = sourceQueue; updateQueue(); currentSong.value = song; playerPosition.floatValue = 0f; playerDuration.floatValue = song.duration.toFloat()
        currentLyrics.value = null; isLyricsVisible.value = false
        if (song.isVideo) {
            mediaPlayer?.pause()
            isPlaying.value = true
        } else {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(getApplication(), Uri.parse(song.uri))
                setOnPreparedListener { mp -> 
                    playerDuration.floatValue = mp.duration.toFloat()
                    mp.start()
                    this@MusicViewModel.isPlaying.value = true
                    mp.setVolume(1f, 1f)
                    setupAudioEffects(mp.audioSessionId)
                    startProgressUpdates() 
                }
                setOnCompletionListener { nextSong() }
                prepareAsync()
            }
        }
    }

    private fun setupAudioEffects(sessionId: Int) {
        try {
            equalizer?.release()
            bassBoost?.release()
            
            equalizer = Equalizer(0, sessionId).apply { 
                enabled = isEqualizerEnabled.value
                applyCurrentPreset()
            }
            bassBoost = BassBoost(0, sessionId).apply {
                enabled = isEqualizerEnabled.value
                setStrength(bassBoostLevel.intValue.toShort())
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun toggleEqualizer(enabled: Boolean) {
        isEqualizerEnabled.value = enabled
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
    }

    fun setBassBoost(level: Int) {
        bassBoostLevel.intValue = level
        bassBoost?.setStrength(level.toShort())
        prefs.edit().putInt("bass_boost", level).apply()
    }

    fun setEqPreset(preset: String) {
        selectedPreset.value = preset
        applyCurrentPreset()
        prefs.edit().putString("eq_preset", preset).apply()
    }

    private fun applyCurrentPreset() {
        val eq = equalizer ?: return
        try {
            val numPresets = eq.numberOfPresets
            for (i in 0 until numPresets) {
                if (eq.getPresetName(i.toShort()) == selectedPreset.value) {
                    eq.usePreset(i.toShort())
                    return
                }
            }
            // If preset name not found, try to map our custom names or just Normal
            if (selectedPreset.value == "Normal") eq.usePreset(0)
        } catch (e: Exception) {}
    }

    fun getEqPresets(): List<String> {
        val eq = equalizer ?: try { Equalizer(0, 0) } catch(e:Exception) { null }
        val list = mutableListOf<String>()
        eq?.let {
            for (i in 0 until it.numberOfPresets) {
                list.add(it.getPresetName(i.toShort()))
            }
        }
        if (list.isEmpty()) return listOf("Normal", "Rock", "Pop", "Jazz", "Classical")
        return list
    }

    fun togglePlayPause() {
        if (currentSong.value?.isVideo == true) {
            isPlaying.value = !isPlaying.value
            return
        }
        val player = mediaPlayer ?: return
        if (player.isPlaying) { player.pause(); isPlaying.value = false } else { player.start(); isPlaying.value = true; startProgressUpdates() }
    }

    fun seekTo(pos: Float) { 
        if (currentSong.value?.isVideo == false) mediaPlayer?.seekTo(pos.toInt())
        playerPosition.floatValue = pos 
    }
    
    fun stopPlayback() { mediaPlayer?.pause(); isPlaying.value = false; playerPosition.floatValue = 0f }
    fun nextSong() { val cur = currentSong.value ?: return; val idx = currentQueue.indexOfFirst { it.id == cur.id }; if (idx >= 0 && currentQueue.isNotEmpty()) playSong(currentQueue[(idx + 1) % currentQueue.size], originalQueue) }
    fun previousSong() { val cur = currentSong.value ?: return; val idx = currentQueue.indexOfFirst { it.id == cur.id }; if (idx >= 0 && currentQueue.isNotEmpty()) playSong(currentQueue[(idx - 1 + currentQueue.size) % currentQueue.size], originalQueue) }
    fun toggleShuffle() { isShuffle.value = !isShuffle.value; updateQueue() }
    private fun updateQueue() { currentQueue = if (isShuffle.value) originalQueue.shuffled() else originalQueue }
    fun deleteFolder(name: String) { folders.removeAll { it.name == name } }
    fun createNewFolder(name: String) { val n = name.trim(); if (n.isNotEmpty() && folders.none { it.name == n }) folders.add(Folder(n, emptyList())) }

    fun startSleepTimer() {
        sleepTimerJob?.cancel()
        if (sleepTimerValue.floatValue <= 0) { isSleepTimerActive.value = false; sleepTimerRemaining.value = ""; return }
        isSleepTimerActive.value = true
        sleepTimerJob = viewModelScope.launch {
            var rem = (sleepTimerValue.floatValue * 60 * 1000).toLong()
            val totalMillis = rem
            while (rem > 0) {
                sleepTimerRemaining.value = String.format("%02d:%02d", rem / 60000, (rem % 60000) / 1000)
                
                // Fade out logic: last 2 minutes (120,000 ms)
                if (rem <= 2 * 60 * 1000) {
                    val volume = rem.toFloat() / (2 * 60 * 1000).toFloat()
                    mediaPlayer?.setVolume(volume, volume)
                }
                
                delay(1000); rem -= 1000
                if (!isSleepTimerActive.value) return@launch
            }
            stopPlayback(); isSleepTimerActive.value = false; android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun cancelSleepTimer() { isSleepTimerActive.value = false; sleepTimerJob?.cancel(); sleepTimerRemaining.value = ""; mediaPlayer?.setVolume(1f, 1f) }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) { mediaPlayer?.let { if (it.isPlaying) playerPosition.floatValue = it.currentPosition.toFloat() }; delay(500) }
        }
    }

    fun setLanguage(lang: String) {
        currentLanguage.value = lang
        prefs.edit().putString("lang", lang).apply()
    }

    fun setDarkMode(enabled: Boolean) {
        darkMode.value = enabled
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    // Shazam Recognition
    fun identifyMusic() {
        viewModelScope.launch {
            isRecognizing.value = true
            recognitionResult.value = null
            delay(5000)
            isRecognizing.value = false
            recognitionResult.value = "Feature is ready! Please provide ACRCloud API credentials to enable real identification."
        }
    }

    // Lyrics Fetching
    fun fetchLyrics(song: Song) {
        viewModelScope.launch {
            isLyricsLoading.value = true
            isLyricsVisible.value = true
            currentLyrics.value = null
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "https://api.lyrics.ovh/v1/${Uri.encode(song.artist)}/${Uri.encode(song.title)}"
                    val json = URL(url).readText()
                    JSONObject(json).getString("lyrics")
                } catch (e: Exception) {
                    null
                }
            }
            
            currentLyrics.value = result ?: "Lyrics not found for this song."
            isLyricsLoading.value = false
        }
    }
}

// --- MAIN UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MusicViewModel = viewModel(); val ctx = LocalContext.current
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            val videoPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            val micPerm = Manifest.permission.RECORD_AUDIO
            
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { p -> p }) vm.scanForMusic() }
            val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.identifyMusic() }
            
            LaunchedEffect(Unit) { 
                if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) vm.scanForMusic() 
                else launcher.launch(arrayOf(perm, videoPerm)) 
            }

            val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION); vm.addSongManually(it, ctx) }
            }

            var selectedTab by remember { mutableIntStateOf(0) }

            BackHandler {
                if (vm.isNowPlayingFull.value) {
                    if (vm.isLyricsVisible.value) vm.isLyricsVisible.value = false
                    else vm.isNowPlayingFull.value = false
                }
                else if (selectedTab == 3) selectedTab = 0
                else if (selectedTab == 1 && vm.currentFolderView.value == "DETAIL") vm.currentFolderView.value = "LIST"
                else if (selectedTab == 0 && vm.currentLibraryView.value != "CATEGORIES") {
                    if (vm.currentLibraryView.value == "PLAYLIST SONGS") vm.currentLibraryView.value = "PLAYLISTS"
                    else vm.currentLibraryView.value = "CATEGORIES"
                }
                else finish()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                            TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent, contentColor = JungleGreen, modifier = Modifier.weight(1f),
                                indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = JungleGreen) }, divider = {}) {
                                listOf("Library", "Folders", "Widgets", "Settings").forEachIndexed { i, t ->
                                    Tab(selected = selectedTab == i, onClick = { selectedTab = i }, 
                                        text = { Text(t, color = if (selectedTab == i) JungleGreen else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                                        icon = { Icon(when(i){0->Icons.Default.LibraryMusic; 1->Icons.Default.Folder; 2->Icons.Default.Widgets; else->Icons.Default.Settings}, null, tint = if (selectedTab == i) JungleGreen else Color.Gray, modifier = Modifier.size(20.dp)) })
                                }
                            }
                        }
                    },
                    bottomBar = { Box(modifier = Modifier.navigationBarsPadding()) { PlayerBottomBar(vm) } },
                    containerColor = if (vm.darkMode.value) Color.Black else Color.White
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when(selectedTab) {
                            0 -> LibraryView(vm, musicPicker)
                            1 -> FolderView(vm, musicPicker)
                            2 -> WidgetsPreview()
                            3 -> SettingsView(vm)
                        }
                    }
                }

                // Overlays
                if (vm.isLoginVisible.value) LoginOverlay(vm)
                
                AnimatedVisibility(
                    visible = vm.isNowPlayingFull.value,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    NowPlayingFullView(vm)
                }

                // Shazam Recognition UI
                if (vm.isRecognizing.value) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable {  }, contentAlignment = Alignment.Center) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val pulseScale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.3f, animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(150.dp).scale(pulseScale).clip(CircleShape).background(Brush.linearGradient(listOf(JungleGreen, Color.Blue))), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(60.dp))
                            }
                            Spacer(Modifier.height(32.dp))
                            Text("Listening...", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Identifying the song around you", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }

                if (vm.recognitionResult.value != null) {
                    AlertDialog(onDismissRequest = { vm.recognitionResult.value = null }, containerColor = Color(0xFF1E1E1E),
                        title = { Text("Result", color = Color.White) },
                        text = { Text(vm.recognitionResult.value!!, color = Color.Gray) },
                        confirmButton = { TextButton(onClick = { vm.recognitionResult.value = null }) { Text("OK", color = JungleGreen) } }
                    )
                }
                
                if (vm.isEqDialogVisible.value) EqualizerDialog(vm)
                if (vm.isSortDialogVisible.value) SortDialog(vm)
            }
        }
    }
}

@Composable
fun NowPlayingFullView(vm: MusicViewModel) {
    val song = vm.currentSong.value ?: return
    val context = LocalContext.current
    var coverBitmap by remember(song.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(song.id) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(song.uri))
                val art = retriever.embeddedPicture
                if (art != null) coverBitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.isNowPlayingFull.value = false }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                Text("Now Playing", color = Color.Gray, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 14.sp)
                IconButton(onClick = { shareMusic(context, song) }) { Icon(Icons.Default.Share, null, tint = JungleGreen) }
            }
            
            Spacer(Modifier.height(40.dp))
            
            // Cover Art / Video
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E1E))) {
                if (song.isVideo) {
                    AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoURI(Uri.parse(song.uri)); start() } }, modifier = Modifier.fillMaxSize())
                } else {
                    if (coverBitmap != null) {
                        Image(bitmap = coverBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = JungleGreen, modifier = Modifier.size(120.dp))
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(40.dp))
            
            Text(song.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
            Text(song.artist, color = JungleGreen, fontSize = 18.sp, maxLines = 1, textAlign = TextAlign.Center)
            
            Spacer(Modifier.weight(1f))
            
            // Progress
            val dur = vm.playerDuration.floatValue.coerceAtLeast(1f)
            Slider(value = vm.playerPosition.floatValue.coerceIn(0f, dur), onValueChange = { vm.seekTo(it) }, valueRange = 0f..dur, colors = SliderDefaults.colors(thumbColor = JungleGreen, activeTrackColor = JungleGreen))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(vm.playerPosition.floatValue.toLong()), color = Color.Gray, fontSize = 12.sp)
                Text(formatTime(dur.toLong()), color = Color.Gray, fontSize = 12.sp)
            }
            
            Spacer(Modifier.height(24.dp))
            
            // Controls
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleShuffle() }) { Icon(Icons.Default.Shuffle, null, tint = if (vm.isShuffle.value) JungleGreen else Color.Gray, modifier = Modifier.size(28.dp)) }
                IconButton(onClick = { vm.previousSong() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                IconButton(onClick = { vm.togglePlayPause() }) { Icon(if (vm.isPlaying.value) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled, null, tint = JungleGreen, modifier = Modifier.size(80.dp)) }
                IconButton(onClick = { vm.nextSong() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                IconButton(onClick = { vm.fetchLyrics(song) }) { Icon(Icons.Default.Article, null, tint = if (vm.isLyricsVisible.value) JungleGreen else Color.Gray, modifier = Modifier.size(28.dp)) }
            }
            Spacer(Modifier.height(40.dp))
        }

        // Lyrics Overlay
        AnimatedVisibility(
            visible = vm.isLyricsVisible.value,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).padding(24.dp)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Lyrics", color = JungleGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.isLyricsVisible.value = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                    }
                    Spacer(Modifier.height(24.dp))
                    if (vm.isLyricsLoading.value) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = JungleGreen) }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Text(vm.currentLyrics.value ?: "", color = Color.White, fontSize = 18.sp, lineHeight = 28.sp)
                            Spacer(Modifier.height(100.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EqualizerDialog(vm: MusicViewModel) {
    AlertDialog(onDismissRequest = { vm.isEqDialogVisible.value = false }, containerColor = Color(0xFF1E1E1E),
        title = { Text("Equalizer & Effects", color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Equalizer", color = Color.White)
                    Switch(checked = vm.isEqualizerEnabled.value, onCheckedChange = { vm.toggleEqualizer(it) }, colors = SwitchDefaults.colors(checkedThumbColor = JungleGreen))
                }
                Spacer(Modifier.height(16.dp))
                Text("Bass Boost", color = JungleGreen, fontWeight = FontWeight.Bold)
                Slider(value = vm.bassBoostLevel.intValue.toFloat(), onValueChange = { vm.setBassBoost(it.toInt()) }, valueRange = 0f..1000f, enabled = vm.isEqualizerEnabled.value, colors = SliderDefaults.colors(thumbColor = JungleGreen, activeTrackColor = JungleGreen))
                Spacer(Modifier.height(16.dp))
                Text("Presets", color = JungleGreen, fontWeight = FontWeight.Bold)
                vm.getEqPresets().forEach { preset ->
                    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = vm.isEqualizerEnabled.value) { vm.setEqPreset(preset) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.selectedPreset.value == preset, onClick = { vm.setEqPreset(preset) }, enabled = vm.isEqualizerEnabled.value, colors = RadioButtonDefaults.colors(selectedColor = JungleGreen))
                        Text(preset, color = if (vm.isEqualizerEnabled.value) Color.White else Color.Gray, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.isEqDialogVisible.value = false }) { Text("Done", color = JungleGreen) } }
    )
}

@Composable
fun SortDialog(vm: MusicViewModel) {
    AlertDialog(onDismissRequest = { vm.isSortDialogVisible.value = false }, containerColor = Color(0xFF1E1E1E),
        title = { Text("Sort By", color = Color.White) },
        text = {
            Column {
                listOf("Title", "Artist", "Date Added", "Duration").forEach { order ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { vm.setSortOrder(order); vm.isSortDialogVisible.value = false }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.currentSortOrder.value == order, onClick = { vm.setSortOrder(order); vm.isSortDialogVisible.value = false }, colors = RadioButtonDefaults.colors(selectedColor = JungleGreen))
                        Text(order, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.isSortDialogVisible.value = false }) { Text("Cancel", color = JungleGreen) } }
    )
}

fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}

@Composable
fun LoginOverlay(vm: MusicViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val textFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = JungleGreen,
        focusedLabelColor = JungleGreen,
        unfocusedLabelColor = Color.Gray,
        focusedIndicatorColor = JungleGreen,
        unfocusedIndicatorColor = Color.Gray
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).padding(32.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AccountCircle, null, tint = JungleGreen, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(if (isRegister) "Create Account" else "Welcome to Muzika", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (isRegister) "Fill details to register" else "Login to your account", color = Color.Gray, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                
                TextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, colors = textFieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                
                TextField(
                    value = password, 
                    onValueChange = { password = it }, 
                    label = { Text("Password") }, 
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(icon, null, tint = JungleGreen)
                        }
                    },
                    singleLine = true, 
                    colors = textFieldColors, 
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (isRegister) {
                    Spacer(Modifier.height(12.dp))
                    TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = textFieldColors, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(24.dp))
                Button(onClick = { if (isRegister) vm.register(email, password, name) else vm.login(email, password) }, 
                    enabled = email.isNotBlank() && password.isNotBlank() && (!isRegister || name.isNotBlank()), 
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = JungleGreen)) { 
                    Text(if (isRegister) "Register" else "Login", color = Color.White) 
                }
                
                TextButton(onClick = { isRegister = !isRegister }) {
                    Text(if (isRegister) "Already have an account? Login" else "Don't have an account? Register", color = JungleGreen)
                }
            }
        }
    }
}

@Composable
fun PlayerBottomBar(vm: MusicViewModel) {
    val song = vm.currentSong.value; val dur = vm.playerDuration.floatValue.coerceAtLeast(1f)
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).clickable { if (song != null) vm.isNowPlayingFull.value = true }.padding(bottom = 8.dp)) {
        Slider(value = vm.playerPosition.floatValue.coerceIn(0f, dur), onValueChange = { vm.seekTo(it) }, valueRange = 0f..dur, colors = SliderDefaults.colors(thumbColor = JungleGreen, activeTrackColor = JungleGreen))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(song?.title ?: "No track", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(song?.artist ?: "Unknown artist", color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { vm.toggleShuffle() }) { Icon(Icons.Default.Shuffle, null, tint = if (vm.isShuffle.value) JungleGreen else Color.White) }
            IconButton(onClick = { vm.previousSong() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
            IconButton(onClick = { vm.togglePlayPause() }, enabled = song != null) { Icon(if (vm.isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = JungleGreen, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = { vm.nextSong() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryView(vm: MusicViewModel, picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    var searchQuery by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    var showEditDialog by remember { mutableStateOf<Song?>(null) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (vm.currentLibraryView.value == "CATEGORIES") {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search songs, artists...", color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = JungleGreen) },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = { 
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.identifyMusic()
                        else Toast.makeText(ctx, "Microphone permission required", Toast.LENGTH_SHORT).show()
                    }) {
                        Box(modifier = Modifier.size(45.dp).clip(CircleShape).background(Brush.linearGradient(listOf(JungleGreen, Color.Blue))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Mic, null, tint = Color.White)
                        }
                    }
                }
            }
            
            when (vm.currentLibraryView.value) {
                "CATEGORIES" -> {
                    val filtered = if (searchQuery.isEmpty()) vm.filteredSongs else vm.filteredSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
                    if (searchQuery.isNotEmpty()) {
                        SongListView(vm, filtered, "Search Results", onEdit = { showEditDialog = it }) { searchQuery = "" }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().background(bgColor).padding(16.dp)) {
                            itemsIndexed(vm.categoryOrder) { index, cat ->
                                CategoryRow(cat, when(cat){"All Songs"->Icons.Default.MusicNote; "Artists"->Icons.Default.Person; "Albums"->Icons.Default.Album; "Favorites"->Icons.Default.Favorite; else->Icons.Default.PlaylistPlay}, vm, 
                                    onMoveUp = { vm.moveCategoryUp(index) }, 
                                    onMoveDown = { vm.moveCategoryDown(index) }) { 
                                    vm.currentLibraryView.value = cat.uppercase() 
                                }
                            }
                        }
                    }
                }
                "ALL SONGS" -> SongListView(vm, vm.filteredSongs, "All Songs", hasSort = true, onEdit = { showEditDialog = it }) { vm.currentLibraryView.value = "CATEGORIES" }
                "FAVORITES" -> SongListView(vm, vm.favoriteSongs, "Favorites", onEdit = { showEditDialog = it }) { vm.currentLibraryView.value = "CATEGORIES" }
                "ARTISTS" -> ArtistListView(vm)
                "ALBUMS" -> AlbumListView(vm)
                "PLAYLISTS" -> PlaylistListView(vm)
                "ARTIST SONGS" -> SongListView(vm, vm.selectedArtist.value?.songs.orEmpty(), vm.selectedArtist.value?.name ?: "Artist", onEdit = { showEditDialog = it }) { vm.currentLibraryView.value = "ARTISTS" }
                "ALBUM SONGS" -> SongListView(vm, vm.selectedAlbum.value?.songs.orEmpty(), vm.selectedAlbum.value?.name ?: "Album", onEdit = { showEditDialog = it }) { vm.currentLibraryView.value = "ALBUMS" }
                "PLAYLIST SONGS" -> SongListView(vm, vm.selectedPlaylist.value?.songs.orEmpty(), vm.selectedPlaylist.value?.name ?: "Playlist", isPlaylist = true, onEdit = { showEditDialog = it }) { vm.currentLibraryView.value = "PLAYLISTS" }
            }
        }
        FloatingActionButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, containerColor = JungleGreen, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = 80.dp)) { Icon(Icons.Default.Add, null, tint = Color.White) }
        
        if (showEditDialog != null) {
            var title by remember { mutableStateOf(showEditDialog!!.title) }
            var artist by remember { mutableStateOf(showEditDialog!!.artist) }
            AlertDialog(onDismissRequest = { showEditDialog = null }, containerColor = Color(0xFF1E1E1E),
                title = { Text("Edit Song Info", color = Color.White) },
                text = {
                    Column {
                        TextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                        Spacer(Modifier.height(8.dp))
                        TextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    }
                },
                confirmButton = { TextButton(onClick = { vm.updateSongMetadata(showEditDialog!!, title, artist); showEditDialog = null }) { Text("Save", color = JungleGreen) } },
                dismissButton = { TextButton(onClick = { showEditDialog = null }) { Text("Cancel", color = Color.Gray) } }
            )
        }
    }
}

@Composable
fun PlaylistListView(vm: MusicViewModel) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    var showCreate by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Header("Playlists", vm) { vm.currentLibraryView.value = "CATEGORIES" }
        Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.buttonColors(containerColor = JungleGreen)) {
            Icon(Icons.Default.Add, null); Text(" Create New Playlist")
        }
        LazyColumn {
            items(vm.playlists) { p ->
                Row(modifier = Modifier.fillMaxWidth().clickable { vm.selectedPlaylist.value = p; vm.currentLibraryView.value = "PLAYLIST SONGS" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlaylistPlay, null, tint = JungleGreen)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, color = textColor, fontSize = 16.sp)
                        Text("${p.songs.size} songs", color = Color.Gray, fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.deletePlaylist(p) }) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
                }
            }
        }
    }
    
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showCreate = false }, containerColor = Color(0xFF1E1E1E),
            title = { Text("New Playlist", color = Color.White) },
            text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)) },
            confirmButton = { TextButton(onClick = { vm.createPlaylist(name); showCreate = false }) { Text("Create", color = JungleGreen) } }
        )
    }
}

@Composable
fun CategoryRow(title: String, icon: ImageVector, vm: MusicViewModel, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = JungleGreen, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(16.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onMoveUp) { Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.Gray) }
            IconButton(onClick = onMoveDown) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.Gray) }
        }
    }
}

@Composable
fun ArtistListView(vm: MusicViewModel) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Header("Artists", vm) { vm.currentLibraryView.value = "CATEGORIES" }
        LazyColumn { items(vm.artists) { a -> Row(modifier = Modifier.fillMaxWidth().clickable { vm.selectedArtist.value = a; vm.currentLibraryView.value = "ARTIST SONGS" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, tint = JungleGreen); Spacer(Modifier.width(12.dp)); Column { Text(a.name, color = textColor, fontSize = 16.sp); Text("${a.songs.size} songs", color = Color.Gray, fontSize = 12.sp) } } } }
    }
}

@Composable
fun AlbumListView(vm: MusicViewModel) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Header("Albums", vm) { vm.currentLibraryView.value = "CATEGORIES" }
        LazyColumn { items(vm.albums) { a -> Row(modifier = Modifier.fillMaxWidth().clickable { vm.selectedAlbum.value = a; vm.currentLibraryView.value = "ALBUM SONGS" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Album, null, tint = JungleGreen); Spacer(Modifier.width(12.dp)); Column { Text(a.name, color = textColor, fontSize = 16.sp); Text("${a.artist} - ${a.songs.size} songs", color = Color.Gray, fontSize = 12.sp) } } } }
    }
}

@Composable
fun FolderView(vm: MusicViewModel, picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    var showCreate by remember { mutableStateOf(false) }
    if (vm.currentFolderView.value == "LIST") {
        Scaffold(floatingActionButton = { 
            Column {
                FloatingActionButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, containerColor = JungleGreen, modifier = Modifier.padding(bottom = 16.dp)) { Icon(Icons.Default.Add, null, tint = Color.White) }
                FloatingActionButton(onClick = { showCreate = true }, containerColor = JungleGreen) { Icon(Icons.Default.CreateNewFolder, null, tint = Color.White) }
            }
        }, containerColor = bgColor) { padding ->
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(vm.folders) { f -> Row(modifier = Modifier.fillMaxWidth().clickable { vm.selectedFolder.value = f; vm.currentFolderView.value = "DETAIL" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFFC107), modifier = Modifier.size(48.dp)); Spacer(Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) { Text(f.name, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("${f.songs.size} songs", color = Color.Gray, fontSize = 14.sp) }
                    IconButton(onClick = { vm.deleteFolder(f.name) }) { Icon(Icons.Default.Delete, null, tint = Color.Gray) } } }
            }
        }
    } else {
        SongListView(vm, vm.selectedFolder.value?.songs.orEmpty(), vm.selectedFolder.value?.name ?: "Folder") { vm.currentFolderView.value = "LIST" }
    }
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showCreate = false }, containerColor = Color(0xFF1E1E1E), title = { Text("New Folder", color = Color.White) }, text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)) },
            confirmButton = { TextButton(onClick = { vm.createNewFolder(name); showCreate = false }) { Text("Create", color = JungleGreen) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListView(vm: MusicViewModel, songs: List<Song>, title: String, isPlaylist: Boolean = false, hasSort: Boolean = false, onEdit: ((Song) -> Unit)? = null, onBack: () -> Unit) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    var showPlaylistDialog by remember { mutableStateOf<Song?>(null) }
    
    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Header(title, vm, onBack)
            Spacer(Modifier.weight(1f))
            if (hasSort) {
                IconButton(onClick = { vm.isSortDialogVisible.value = true }) { Icon(Icons.Default.Sort, null, tint = JungleGreen) }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(songs) { s ->
                val isCur = s.id == vm.currentSong.value?.id
                val ctx = LocalContext.current
                Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { vm.playSong(s, songs) }, onLongClick = { onEdit?.invoke(s) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (s.isVideo) Icons.Default.PlayCircle else Icons.Default.MusicNote, null, tint = if (isCur) JungleGreen else Color.Gray); Spacer(Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text(s.title, color = if (isCur) JungleGreen else textColor, fontSize = 16.sp, maxLines = 1); Text(s.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1) }
                    IconButton(onClick = { shareMusic(ctx, s) }) { Icon(Icons.Default.Share, null, tint = JungleGreen) }
                    IconButton(onClick = { vm.toggleFavorite(s) }) { Icon(if (vm.isSongFavorite(s)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (vm.isSongFavorite(s)) JungleGreen else Color.Gray) }
                    IconButton(onClick = { if (isPlaylist) vm.removeSongFromPlaylist(s, vm.selectedPlaylist.value!!) else showPlaylistDialog = s }) {
                        Icon(if (isPlaylist) Icons.Default.RemoveCircleOutline else Icons.Default.PlaylistAdd, null, tint = if (isPlaylist) Color.Red else JungleGreen)
                    }
                }
            }
        }
    }
    
    if (showPlaylistDialog != null) {
        AlertDialog(onDismissRequest = { showPlaylistDialog = null }, containerColor = Color(0xFF1E1E1E),
            title = { Text("Add to Playlist", color = Color.White) },
            text = {
                Column {
                    if (vm.playlists.isEmpty()) Text("No playlists created", color = Color.Gray)
                    vm.playlists.forEach { p ->
                        Text(p.name, color = Color.White, modifier = Modifier.fillMaxWidth().clickable { vm.addSongToPlaylist(showPlaylistDialog!!, p); showPlaylistDialog = null }.padding(16.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPlaylistDialog = null }) { Text("Cancel", color = JungleGreen) } }
        )
    }
}

@Composable
fun Header(title: String, vm: MusicViewModel, onBack: () -> Unit) {
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textColor) }; Text(title, color = JungleGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun SettingsView(vm: MusicViewModel) {
    val ctx = LocalContext.current; val infinite = rememberInfiniteTransition(label = "shazam")
    val scale by infinite.animateFloat(initialValue = 1f, targetValue = if (vm.isRecognizing.value) 1.2f else 1f, animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "s")

    Column(modifier = Modifier.fillMaxSize().background(if (vm.darkMode.value) Color.Black else Color.White)) {
        Header("Settings", vm) { /* Handled by tab selection */ }
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("User Account", color = JungleGreen, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Logged in as", color = Color.Gray, fontSize = 12.sp); Text(vm.currentUserName.value ?: "User", color = if (vm.darkMode.value) Color.White else Color.Black, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { vm.logout() }) { Text("Switch User", color = Color.Red) }
            }
            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

            Text("General", color = JungleGreen, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { vm.isLanguageDialogVisible.value = true }, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Language", color = if (vm.darkMode.value) Color.White else Color.Black); Text(vm.currentLanguage.value, color = JungleGreen)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Dark Mode", color = if (vm.darkMode.value) Color.White else Color.Black); Switch(checked = vm.darkMode.value, onCheckedChange = { vm.setDarkMode(it) }, colors = SwitchDefaults.colors(checkedThumbColor = JungleGreen))
            }
            
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Sleep Timer", color = if (vm.darkMode.value) Color.White else Color.Black); Text(if (vm.isSleepTimerActive.value) vm.sleepTimerRemaining.value else "${vm.sleepTimerValue.floatValue.toInt()} min", color = JungleGreen)
                }
                Slider(value = vm.sleepTimerValue.floatValue, onValueChange = { vm.sleepTimerValue.floatValue = it }, valueRange = 0f..120f, colors = SliderDefaults.colors(thumbColor = JungleGreen, activeTrackColor = JungleGreen))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (vm.isSleepTimerActive.value) TextButton(onClick = { vm.cancelSleepTimer() }) { Text("Cancel", color = Color.Red) }
                    else TextButton(onClick = { vm.startSleepTimer() }, enabled = vm.sleepTimerValue.floatValue > 0) { Text("Start", color = JungleGreen) }
                }
            }

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp)); Text("Integrations", color = JungleGreen, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.padding(vertical = 16.dp).clickable { 
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.identifyMusic()
                else Toast.makeText(ctx, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(50.dp).scale(scale).clip(CircleShape).background(Brush.linearGradient(listOf(JungleGreen, Color.Blue))), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Color.White) }
                Spacer(Modifier.width(16.dp)); Column { Text("Song Identification (Shazam Style)", color = if (vm.darkMode.value) Color.White else Color.Black, fontWeight = FontWeight.Bold); Text(if (vm.isRecognizing.value) "Listening..." else "Tap to identify music around you", color = Color.Gray, fontSize = 12.sp) }
            }
            Button(onClick = { openLink(ctx, "spotify:home", "https://open.spotify.com") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Icon(Icons.Default.Link, null, tint = JungleGreen); Text(" Open Spotify", color = if (vm.darkMode.value) Color.White else Color.Black) }
            Button(onClick = { openLink(ctx, "https://www.youtube.com", "https://www.youtube.com") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Icon(Icons.Default.PlayCircle, null, tint = JungleGreen); Text(" Open YouTube", color = if (vm.darkMode.value) Color.White else Color.Black) }

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp)); Text("Audio & Library", color = JungleGreen, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-Scan for Music", color = if (vm.darkMode.value) Color.White else Color.Black); Switch(checked = vm.autoScan.value, onCheckedChange = { vm.autoScan.value = it; if (it) vm.scanForMusic() }, colors = SwitchDefaults.colors(checkedThumbColor = JungleGreen))
            }
            Button(onClick = { vm.isEqDialogVisible.value = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))) { Text("Equalizer & Effects", color = JungleGreen) }
            Button(onClick = { vm.scanForMusic(); Toast.makeText(ctx, "Scanning...", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))) { Text("Scan for Music", color = JungleGreen) }
            Button(onClick = { vm.favoriteSongs.clear(); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))) { Text("Clear Favorites", color = Color.White) }
            
            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp)); Text("About", color = JungleGreen, fontWeight = FontWeight.Bold)
            Text("This app was created by me with the help of AI to provide a simple and elegant music listening experience.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
    }

    if (vm.isLanguageDialogVisible.value) {
        AlertDialog(onDismissRequest = { vm.isLanguageDialogVisible.value = false }, containerColor = Color(0xFF1E1E1E), title = { Text("Select Language", color = Color.White) }, text = {
            Column { listOf("Български", "Русский", "English", "Deutsch", "日本語").forEach { lang -> 
                Text(text = lang, color = if (vm.currentLanguage.value == lang) JungleGreen else Color.White, modifier = Modifier.fillMaxWidth().clickable { 
                    vm.setLanguage(lang)
                    vm.isLanguageDialogVisible.value = false 
                }.padding(16.dp)) 
            } }
        }, confirmButton = { TextButton(onClick = { vm.isLanguageDialogVisible.value = false }) { Text("Close", color = JungleGreen) } })
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: String, vm: MusicViewModel, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E), title = { Text(title, color = Color.White) }, text = { Column { options.forEach { opt -> Text(text = opt, color = if (selected == opt) JungleGreen else Color.White, modifier = Modifier.fillMaxWidth().clickable { onSelect(opt) }.padding(16.dp)) } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = JungleGreen) } })
}

@Composable
fun WidgetsPreview() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Widget Preview", color = JungleGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        Card(modifier = Modifier.size(250.dp, 120.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Muzika", color = JungleGreen, fontWeight = FontWeight.Bold)
                Text("Now Playing...", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Icon(Icons.Default.PlayArrow, null, tint = JungleGreen, modifier = Modifier.size(48.dp))
                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        Text("You can find this widget in your phone's widget menu", color = Color.Gray, modifier = Modifier.padding(top = 16.dp), fontSize = 12.sp)
    }
}

fun shareMusic(ctx: Context, song: Song) {
    val shareText = "Listen to '${song.title}' by '${song.artist}' via Muzika!\n\nSearch on:\nSpotify: https://open.spotify.com/search/${Uri.encode(song.title)}\nYouTube: https://www.youtube.com/results?search_query=${Uri.encode(song.title + " " + song.artist)}\nGoogle: https://www.google.com/search?q=${Uri.encode(song.title + " " + song.artist)}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Sharing Music")
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share via"))
}

fun openLink(ctx: android.content.Context, uri: String, backup: String) {
    try { val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; ctx.startActivity(intent) }
    catch (e: Exception) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(backup))) }
}
