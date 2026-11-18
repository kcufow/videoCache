package com.darwin.videocache.proxy

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class ProxyServer(
    private val preferredPort: Int,
    private val router: ProxyRouter,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "video-cache-proxy").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @Synchronized
    fun start() {
        if (running.get()) return
        val socket = ServerSocket(preferredPort, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running.set(true)
        executor.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    executor.execute {
                        HttpExchange(client).use { exchange ->
                            runCatching { router.handle(exchange) }
                        }
                    }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
