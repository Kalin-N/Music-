package com.example.musicapp

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val uri: String
)

data class Folder(val name: String, val songs: List<Song>)
data class Album(val name: String, val artist: String, val songs: List<Song>)
data class Artist(val name: String, val songs: List<Song>)

// --- ГЛАВЕН КЛАС (VIEWMODEL) ---
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("MusicAppPrefs", Context.MODE_PRIVATE)
    
    val currentUserEmail = mutableStateOf<String?>(prefs.getString("last_user_email", null))
    val currentUserName = mutableStateOf<String?>(prefs.getString("last_user_name", null))
    val isLoginVisible = mutableStateOf(currentUserEmail.value == null)
    
    val currentLibraryView = mutableStateOf("CATEGORIES")
    val currentFolderView = mutableStateOf("LIST")
    val currentSong = mutableStateOf<Song?>(null)
    val isPlaying = mutableStateOf(false)
    val playerPosition = mutableFloatStateOf(0f)
    val playerDuration = mutableFloatStateOf(0f)
    val isShuffle = mutableStateOf(false)

    // Данни
    val filteredSongs = mutableStateListOf<Song>()
    val folders = mutableStateListOf<Folder>()
    val artists = mutableStateListOf<Artist>()
    val albums = mutableStateListOf<Album>()
    val favoriteSongs = mutableStateListOf<Song>()

    // Настройки
    val currentLanguage = mutableStateOf(prefs.getString("lang", "English") ?: "English")
    val isLanguageDialogVisible = mutableStateOf(false)
    val sleepTimerValue = mutableFloatStateOf(0f)
    val isSleepTimerActive = mutableStateOf(false)
    val sleepTimerRemaining = mutableStateOf("")
    val autoScan = mutableStateOf(prefs.getBoolean("auto_scan", true))
    val darkMode = mutableStateOf(prefs.getBoolean("dark_mode", true))
    val shazamIntegration = mutableStateOf(false)

    val selectedFolder = mutableStateOf<Folder?>(null)
    val selectedAlbum = mutableStateOf<Album?>(null)
    val selectedArtist = mutableStateOf<Artist?>(null)

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var originalQueue: List<Song> = emptyList()
    private var currentQueue: List<Song> = emptyList()

    init {
        if (folders.isEmpty()) folders.add(Folder("My Music", emptyList()))
        loadUserFavorites()
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
            
            // Проверка за заето име
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

    // --- МУЗИКАЛНА ЛОГИКА ---
    fun scanForMusic() {
        viewModelScope.launch {
            val songs = withContext(Dispatchers.IO) {
                val result = mutableListOf<Song>()
                val resolver = getApplication<Application>().contentResolver
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA)
                resolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(5).orEmpty()
                        if (path.contains("Record", true) || path.contains("Call", true)) continue
                        val id = cursor.getLong(0)
                        val uri = ContentUris.withAppendedId(collection, id).toString()
                        result.add(Song(id, cursor.getString(1) ?: "Unknown", cursor.getString(2) ?: "Unknown", cursor.getString(3) ?: "Unknown", cursor.getLong(4), path, uri))
                    }
                }
                result
            }
            filteredSongs.clear(); filteredSongs.addAll(songs)
            folders.clear(); songs.groupBy { File(it.path).parentFile?.name ?: "Music" }.forEach { (n, s) -> folders.add(Folder(n, s)) }
            if (folders.isEmpty()) folders.add(Folder("My Music", emptyList()))
            artists.clear(); songs.groupBy { it.artist }.forEach { (n, s) -> artists.add(Artist(n, s)) }
            albums.clear(); songs.groupBy { it.album }.forEach { (n, s) -> albums.add(Album(n, s.firstOrNull()?.artist ?: "Unknown", s)) }
            loadUserFavorites()
        }
    }

    fun addSongManually(uri: Uri, context: Context) {
        viewModelScope.launch {
            val song = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: uri.lastPathSegment ?: "New Song"
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                    val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "My Music"
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                    retriever.release()
                     Song(System.currentTimeMillis(), title, artist, album, duration, "", uri.toString())
                } catch (e: Exception) {
                    Song(System.currentTimeMillis(), uri.lastPathSegment ?: "New Song", "Unknown", "My Music", 0L, "", uri.toString())
                }
            }
            filteredSongs.add(song)
            val idx = folders.indexOfFirst { it.name == "My Music" }
            if (idx != -1) folders[idx] = folders[idx].copy(songs = folders[idx].songs + song)
            else folders.add(Folder("My Music", listOf(song)))
        }
    }

    fun playSong(song: Song, sourceQueue: List<Song> = filteredSongs) {
        originalQueue = sourceQueue; updateQueue(); currentSong.value = song; playerPosition.floatValue = 0f; playerDuration.floatValue = song.duration.toFloat()
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(getApplication(), Uri.parse(song.uri))
            setOnPreparedListener { mp -> playerDuration.floatValue = mp.duration.toFloat(); mp.start(); this@MusicViewModel.isPlaying.value = true; startProgressUpdates() }
            setOnCompletionListener { nextSong() }
            prepareAsync()
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) { player.pause(); isPlaying.value = false } else { player.start(); isPlaying.value = true; startProgressUpdates() }
    }

    fun seekTo(pos: Float) { mediaPlayer?.seekTo(pos.toInt()); playerPosition.floatValue = pos }
    fun stopPlayback() { mediaPlayer?.pause(); mediaPlayer?.seekTo(0); isPlaying.value = false; playerPosition.floatValue = 0f }
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
            while (rem > 0) {
                sleepTimerRemaining.value = String.format("%02d:%02d", rem / 60000, (rem % 60000) / 1000)
                if (rem <= 5 * 60 * 1000) { val v = rem.toFloat() / (5 * 60 * 1000).toFloat(); mediaPlayer?.setVolume(v, v) }
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
}

// --- MAIN UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MusicViewModel = viewModel(); val ctx = LocalContext.current
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.scanForMusic() }
            LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) vm.scanForMusic() else launcher.launch(perm) }

            val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION); vm.addSongManually(it, ctx) }
            }

            var selectedTab by remember { mutableIntStateOf(0) }

            BackHandler {
                if (selectedTab == 3) selectedTab = 0
                else if (selectedTab == 1 && vm.currentFolderView.value == "DETAIL") vm.currentFolderView.value = "LIST"
                else if (selectedTab == 0 && vm.currentLibraryView.value != "CATEGORIES") vm.currentLibraryView.value = "CATEGORIES"
                else finish()
            }

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
                    if (vm.isLoginVisible.value) LoginOverlay(vm)
                }
            }
        }
    }
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
                Text(if (isRegister) "Create Account" else "Welcome back", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(bottom = 8.dp)) {
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

@Composable
fun LibraryView(vm: MusicViewModel, picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    Box(modifier = Modifier.fillMaxSize()) {
        when (vm.currentLibraryView.value) {
            "CATEGORIES" -> LazyColumn(modifier = Modifier.fillMaxSize().background(bgColor).padding(16.dp)) {
                item { CategoryRow("All Songs", Icons.Default.MusicNote, vm) { vm.currentLibraryView.value = "ALL SONGS" } }
                item { CategoryRow("Artists", Icons.Default.Person, vm) { vm.currentLibraryView.value = "ARTISTS" } }
                item { CategoryRow("Albums", Icons.Default.Album, vm) { vm.currentLibraryView.value = "ALBUMS" } }
                item { CategoryRow("Favorites", Icons.Default.Favorite, vm) { vm.currentLibraryView.value = "FAVORITES" } }
            }
            "ALL SONGS" -> SongListView(vm, vm.filteredSongs, "All Songs") { vm.currentLibraryView.value = "CATEGORIES" }
            "FAVORITES" -> SongListView(vm, vm.favoriteSongs, "Favorites") { vm.currentLibraryView.value = "CATEGORIES" }
            "ARTISTS" -> ArtistListView(vm)
            "ALBUMS" -> AlbumListView(vm)
            "ARTIST SONGS" -> SongListView(vm, vm.selectedArtist.value?.songs.orEmpty(), vm.selectedArtist.value?.name ?: "Artist") { vm.currentLibraryView.value = "ARTISTS" }
            "ALBUM SONGS" -> SongListView(vm, vm.selectedAlbum.value?.songs.orEmpty(), vm.selectedAlbum.value?.name ?: "Album") { vm.currentLibraryView.value = "ALBUMS" }
        }
        FloatingActionButton(onClick = { picker.launch(arrayOf("audio/*")) }, containerColor = JungleGreen, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = 80.dp)) { Icon(Icons.Default.Add, null, tint = Color.White) }
    }
}

@Composable
fun CategoryRow(title: String, icon: ImageVector, vm: MusicViewModel, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = JungleGreen, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(20.dp)); Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                FloatingActionButton(onClick = { picker.launch(arrayOf("audio/*")) }, containerColor = JungleGreen, modifier = Modifier.padding(bottom = 16.dp)) { Icon(Icons.Default.Add, null, tint = Color.White) }
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
        AlertDialog(onDismissRequest = { showCreate = false }, containerColor = Color(0xFF1E1E1E), title = { Text("New Folder", color = Color.White) }, text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.createNewFolder(name); showCreate = false }) { Text("Create", color = JungleGreen) } })
    }
}

@Composable
fun SongListView(vm: MusicViewModel, songs: List<Song>, title: String, onBack: () -> Unit) {
    val bgColor = if (vm.darkMode.value) Color.Black else Color.White
    val textColor = if (vm.darkMode.value) Color.White else Color.Black
    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Header(title, vm, onBack)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(songs) { s ->
                val isCur = s.id == vm.currentSong.value?.id
                val ctx = LocalContext.current
                Row(modifier = Modifier.fillMaxWidth().clickable { vm.playSong(s, songs) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = if (isCur) JungleGreen else Color.Gray); Spacer(Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text(s.title, color = if (isCur) JungleGreen else textColor, fontSize = 16.sp, maxLines = 1); Text(s.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1) }
                    IconButton(onClick = { shareMusic(ctx, s) }) { Icon(Icons.Default.Share, null, tint = JungleGreen) }
                    IconButton(onClick = { vm.toggleFavorite(s) }) { Icon(if (vm.isSongFavorite(s)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (vm.isSongFavorite(s)) JungleGreen else Color.Gray) }
                }
            }
        }
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
    val scale by infinite.animateFloat(initialValue = 1f, targetValue = if (vm.shazamIntegration.value) 1.2f else 1f, animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "s")

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
            Row(modifier = Modifier.padding(vertical = 16.dp).clickable { openLink(ctx, "shazam://", "https://www.shazam.com") }, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(50.dp).scale(scale).clip(CircleShape).background(Brush.linearGradient(listOf(JungleGreen, Color.Blue))), contentAlignment = Alignment.Center) { Icon(Icons.Default.Search, null, tint = Color.White) }
                Spacer(Modifier.width(16.dp)); Column { Text("Shazam Integration", color = if (vm.darkMode.value) Color.White else Color.Black, fontWeight = FontWeight.Bold); Text("Tap to open Shazam", color = Color.Gray, fontSize = 12.sp) }
            }
            Button(onClick = { openLink(ctx, "spotify:home", "https://open.spotify.com") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Icon(Icons.Default.Link, null, tint = JungleGreen); Text(" Open Spotify", color = if (vm.darkMode.value) Color.White else Color.Black) }
            Button(onClick = { openLink(ctx, "https://www.youtube.com", "https://www.youtube.com") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Icon(Icons.Default.PlayCircle, null, tint = JungleGreen); Text(" Open YouTube", color = if (vm.darkMode.value) Color.White else Color.Black) }

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp)); Text("Audio & Library", color = JungleGreen, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-Scan for Music", color = if (vm.darkMode.value) Color.White else Color.Black); Switch(checked = vm.autoScan.value, onCheckedChange = { vm.autoScan.value = it; if (it) vm.scanForMusic() }, colors = SwitchDefaults.colors(checkedThumbColor = JungleGreen))
            }
            Button(onClick = { vm.scanForMusic(); Toast.makeText(ctx, "Scanning...", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))) { Text("Scan for Music", color = JungleGreen) }
            Button(onClick = { vm.favoriteSongs.clear(); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))) { Text("Clear Favorites", color = Color.White) }
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
