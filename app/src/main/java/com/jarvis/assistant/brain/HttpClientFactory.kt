package com.jarvis.assistant.brain

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Tuned, shared OkHttp client factory for Jarvis.
 *
 * Uses a bounded connection pool (5 idle sockets, 3-minute keep-alive) and
 * throttled dispatcher concurrency so HTTP/2 + TLS sessions are reused cleanly
 * without excessive mobile radio wakeups or memory overhead.
 */
object HttpClientFactory {

    private val sharedConnectionPool = ConnectionPool(5, 3, TimeUnit.MINUTES)

    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 24
        maxRequestsPerHost = 6
    }

    val shared: OkHttpClient by lazy {
        create()
    }

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .dispatcher(sharedDispatcher)
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Evicts idle HTTP/TLS sockets to reclaim native buffers and keep radio power low.
     */
    fun evictIdleConnections() {
        runCatching { sharedConnectionPool.evictAll() }
    }
}
