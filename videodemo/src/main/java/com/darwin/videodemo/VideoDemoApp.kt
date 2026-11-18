package com.darwin.videodemo

import android.app.Application
import com.darwin.videocache.api.HlsSegmentPreloadStrategy
import com.darwin.videocache.api.VideoCacheConfig
import com.darwin.videocache.videoCacheManager

class VideoDemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        videoCacheManager.init(
            context = this,
            config = VideoCacheConfig(
                preloadStrategy = HlsSegmentPreloadStrategy(segmentCount = 3),
            ),
        )
    }
}
