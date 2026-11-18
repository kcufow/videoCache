# VideoCache

Android 视频边下边播 / 预加载缓存库，通过本地代理服务缓存 MP4 / HLS 等资源。

## 通过 JitPack 引入

1. 在根目录 `settings.gradle.kts`（或项目级 repositories）中添加：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

2. 在 module 的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.kcufow:videoCache:1.0.0")
}
```

版本号对应 GitHub Tag（当前为 `1.0.0`）。构建状态见：https://jitpack.io/#kcufow/videoCache

## 本地发布

```bash
./gradlew :videoCache:publishToMavenLocal -x test
```

本地坐标：`com.darwin.videocache:videoCache:1.0.0`  
JitPack 坐标：`com.github.kcufow:videoCache:1.0.0`

## 模块说明

| 模块 | 说明 |
|------|------|
| `videoCache` | 可发布的 Android Library（AAR） |
| `videodemo` | 示例 App，不参与发布 |
