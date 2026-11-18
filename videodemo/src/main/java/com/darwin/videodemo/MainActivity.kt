package com.darwin.videodemo

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.darwin.videocache.api.PreloadPriority
import com.darwin.videocache.api.PreloadState
import com.darwin.videocache.api.VideoCacheListener
import com.darwin.videocache.videoCacheManager
import com.darwin.videodemo.ui.theme.VideoDemoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TEST_HLS_URL =
    "https://fastup-cdn-prod.601book.com/transcoded/0/112006/977005/1/index.m3u8" +
        "?auth_key=1794993404-0-0-0d5f69dc41905b42502be37f703e5f7a"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VideoCacheDemoScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoCacheDemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var proxyUrl by remember { mutableStateOf("") }
    var preloadState by remember { mutableStateOf("idle") }
    var preloadResult by remember { mutableStateOf("尚未触发预加载") }
    var cachedBytes by remember { mutableLongStateOf(0L) }
    var usedBytes by remember { mutableLongStateOf(0L) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var playbackStateText by remember { mutableStateOf("IDLE") }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPrepared by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = object : VideoCacheListener {
            override fun onPreloadState(
                cacheKey: String,
                url: String,
                state: PreloadState,
                error: Throwable?,
            ) {
                if (url != TEST_HLS_URL) return
                preloadState = state.name
                preloadResult = when (state) {
                    PreloadState.QUEUED -> "回调: 已入队 cacheKey=${cacheKey.take(8)}…"
                    PreloadState.RUNNING -> "回调: 正在拉取分片/清单…"
                    PreloadState.SUCCESS ->
                        "回调成功: SUCCESS, 本次累计≈${cachedBytes}B, 总缓存=${videoCacheManager.getUsedBytes()}B"
                    PreloadState.FAILED ->
                        "回调失败: ${error?.javaClass?.simpleName}: ${error?.message ?: "unknown"}"
                    PreloadState.CANCELLED -> "回调: 已取消"
                }
                if (state == PreloadState.SUCCESS || state == PreloadState.FAILED) {
                    usedBytes = videoCacheManager.getUsedBytes()
                }
            }

            override fun onPreloadProgress(
                cacheKey: String,
                url: String,
                cachedBytesValue: Long,
                totalBytes: Long,
            ) {
                if (url != TEST_HLS_URL) return
                cachedBytes = cachedBytesValue
                preloadResult = if (totalBytes > 0) {
                    "回调进度: $cachedBytesValue / $totalBytes bytes"
                } else {
                    "回调进度: 已缓存 $cachedBytesValue bytes"
                }
            }
        }
        videoCacheManager.addListener(listener)
        onDispose { videoCacheManager.removeListener(listener) }
    }

    LaunchedEffect(TEST_HLS_URL) {
        while (isActive) {
            usedBytes = videoCacheManager.getUsedBytes()
            delay(1_000)
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackStateText = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = error.message ?: error.errorCodeName
                isPlaying = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    fun ensureProxyAndPrepare() {
        val url = videoCacheManager.getProxyUrl(TEST_HLS_URL)
        proxyUrl = url
        if (!mediaPrepared || exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() != url) {
            playerError = null
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            mediaPrepared = true
        }
    }

    fun onPreloadClick() {
        preloadState = PreloadState.QUEUED.name
        preloadResult = "已点击预加载，等待回调…"
        cachedBytes = 0L
        videoCacheManager.preload(TEST_HLS_URL, PreloadPriority.HIGH)
        proxyUrl = videoCacheManager.getProxyUrl(TEST_HLS_URL)
    }

    fun onPlayClick() {
        ensureProxyAndPrepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    fun onPauseClick() {
        exoPlayer.pause()
        exoPlayer.playWhenReady = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "VideoCache HLS Demo",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = ::onPreloadClick,
                modifier = Modifier.weight(1f),
            ) {
                Text("预加载")
            }
            Button(
                onClick = ::onPlayClick,
                enabled = !isPlaying,
                modifier = Modifier.weight(1f),
            ) {
                Text("播放")
            }
            OutlinedButton(
                onClick = ::onPauseClick,
                enabled = isPlaying,
                modifier = Modifier.weight(1f),
            ) {
                Text("暂停")
            }
        }
        Spacer(Modifier.height(16.dp))
        StatusLine("Origin", TEST_HLS_URL)
        StatusLine("Proxy", proxyUrl.ifBlank { "未获取（点预加载或播放）" })
        StatusLine("Preload", preloadState)
        StatusLine("回调结果", preloadResult)
        StatusLine("Cached", "$cachedBytes bytes")
        StatusLine("Used", "$usedBytes bytes")
        StatusLine("Playback", "$playbackStateText / ${if (isPlaying) "playing" else "paused"}")
        if (playerError != null) {
            StatusLine("Error", playerError.orEmpty())
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
