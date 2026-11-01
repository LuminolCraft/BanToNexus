package org.luminolcraft.banToNexus

import com.electronwill.nightconfig.core.CommentedConfig
import com.electronwill.nightconfig.core.ConfigFormat
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.electronwill.nightconfig.yaml.YamlFormat
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import org.luminolcraft.banToNexus.Main.Companion.configManager
import java.io.File
import java.net.URI
import kotlin.jvm.optionals.getOrElse

class ConfigManager {
    private val configFile = File(Main.dataDirectory.toFile(), "config.yml")
    // 显式传格式，不走 FormatDetector 的扩展名探测：探测内部是 Class.forName，
    // 解析用的是加载 night-config-core 的那个类加载器；Velocity 的 server.jar 自带 core 但只有 toml，
    // 导致 yml 永远注册不上。直接引用 YamlFormat 可绕开这条运行时反射路径。
    @Suppress("UNCHECKED_CAST")
    private val config: CommentedFileConfig = CommentedFileConfig.of(
        configFile,
        YamlFormat.defaultInstance() as ConfigFormat<CommentedConfig>
    )

    @Volatile
    var httpUrl = ""

    @Volatile
    var wsUrl = ""

    @Volatile
    var secret = ""

    @Volatile
    var connectionTimeoutSec = 0L

    @Volatile
    var requestTimeoutSec = 0L

    @Volatile
    var retryIntervalSec = 0L

    @Volatile
    var pendingFlushBatchSize = 0

    @Volatile
    var wsPingIntervalSec = 0L

    @Volatile
    var reconnectDelaySec = 0L

    fun initAndLoadConfig() {
        var shouldSave = false
        if (!configFile.exists()) {
            if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
            configFile.createNewFile()
        }
        // night-config 3.9.0 的 YamlParser 对空文档会直接 NPE：snakeyaml 返回 null 后仍调用 map.entrySet()。
        // 首次运行走到这里时 config.yml 刚被 createNewFile() 成 0 字节，或用户只留了注释。
        // 因此只有文件里真的存在键值时才解析，否则保持默认值，稍后由 config.save() 写成合法 YAML。
        val hasContent = configFile.length() > 0L &&
            configFile.useLines { lines -> lines.any { it.isNotBlank() && !it.trimStart().startsWith("#") } }
        if (hasContent) {
            config.load()
        }
        if (config.getOptional<String>("api-base-url").isEmpty) {
            config.set<String>("api-base-url", "http://localhost:8080")
            config.setComment("api-base-url", """Recommend using https for better security.""")
            shouldSave = true
        }
        // ws 与 http 分开配置：WebSocket 升级请求不能被反向代理（Netlify）转发，
        // 代理会丢掉 Upgrade/Connection 头导致握手永远建不起来，必须直连源站域
        if (config.getOptional<String>("ws-url").isEmpty) {
            config.set<String>("ws-url", "ws://localhost:8080")
            config.setComment("ws-url", """Recommend using wss for better security.""")
            shouldSave = true
        }
        if (config.getOptional<String>("secret").isEmpty) {
            config.set<String>("secret", "some-fucking-secret")
            shouldSave = true
        }
        if (config.getOptional<Long>("connection-timeout-sec").isEmpty) {
            config.set<Long>("connection-timeout-sec", 5L)
            shouldSave = true
        }
        if (config.getOptional<Long>("request-timeout-sec").isEmpty) {
            config.set<Long>("request-timeout-sec", 10L)
            shouldSave = true
        }
        if (config.getOptional<Long>("retry-interval-sec").isEmpty) {
            config.set<Long>("retry-interval-sec", 30L)
            shouldSave = true
        }
        if (config.getOptional<Int>("pending-flush-batch-size").isEmpty) {
            config.set<Int>("pending-flush-batch-size", 20)
            shouldSave = true
        }
        if (config.getOptional<Long>("ws-ping-interval-sec").isEmpty) {
            config.set<Long>("ws-ping-interval-sec", 30L)
            shouldSave = true
        }
        if (config.getOptional<Long>("reconnect-delay-sec").isEmpty) {
            config.set<Long>("reconnect-delay-sec", 60L)
            shouldSave = true
        }
        if (shouldSave) {
            config.save()
        }

        //Load
        // 用 run + return@run 模拟提前返回：scheme 合法就采用配置值，
        // 否则落到下面的默认地址
        run {
            val apiBaseUrlRaw = config.getOptional<String>("api-base-url")
            if (apiBaseUrlRaw.isPresent) {
                val scheme = URI.create(apiBaseUrlRaw.get()).scheme
                if (scheme == "http" || scheme == "https") {
                    httpUrl = apiBaseUrlRaw.get().trimEnd('/')
                    return@run null
                }
            }
            httpUrl = "http://localhost:8080"
        }
        run {
            val wsUrlRaw = config.getOptional<String>("ws-url")
            if (wsUrlRaw.isPresent) {
                val scheme = URI.create(wsUrlRaw.get()).scheme
                if (scheme == "ws" || scheme == "wss") {
                    wsUrl = wsUrlRaw.get().trimEnd('/')
                    return@run null
                }
                wsUrl = "ws://localhost:8080"
            }
        }
        secret = config.getOptional<String>("secret").getOrElse { "some-fucking-secret" }
        connectionTimeoutSec = config.getOptional<Long>("connection-timeout-sec").getOrElse { 5L }
        if (connectionTimeoutSec <= 0) connectionTimeoutSec = 5L
        requestTimeoutSec = config.getOptional<Long>("request-timeout-sec").getOrElse { 10L }
        if (requestTimeoutSec <= 0) requestTimeoutSec = 10L
        retryIntervalSec = config.getOptional<Long>("retry-interval-sec").getOrElse { 30L }
        if (retryIntervalSec <= 0) retryIntervalSec = 30L
        pendingFlushBatchSize = config.getOptional<Int>("pending-flush-batch-size").getOrElse { 20 }
        if (pendingFlushBatchSize <= 0) pendingFlushBatchSize = 20
        wsPingIntervalSec = config.getOptional<Long>("ws-ping-interval-sec").getOrElse { 30L }
        if (wsPingIntervalSec <= 0) wsPingIntervalSec = 30L
        reconnectDelaySec = config.getOptional<Long>("reconnect-delay-sec").getOrElse { 60L }
        if (reconnectDelaySec <= 0) reconnectDelaySec = 60L
    }

    // 重载会重建 HttpClient，因此必须先把定时器和 socket 停掉：
    // 否则新客户端建好之前，旧协程仍会拿着已被 close 的客户端发请求
    fun reloadConfig() {
        Main.stopTimerJob()
        Main.socketManager.stop()
        initAndLoadConfig()
        Main.httpClient.close()
        initHttpClient()
        Main.startTimerJob()
        Main.socketManager.start()
    }

    fun initHttpClient() {
        Main.httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
            install(WebSockets) {
                pingIntervalMillis = configManager.wsPingIntervalSec * 1000L
                maxFrameSize = 256 * 1024
            }
            followRedirects = true
            install(HttpRedirect) {
                checkHttpMethod = false
            }
            install(HttpTimeout) {
                requestTimeoutMillis = configManager.requestTimeoutSec * 1000L
                connectTimeoutMillis = configManager.connectionTimeoutSec * 1000L
            }
            defaultRequest {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Webhook-Secret", configManager.secret)
            }
        }
    }
}