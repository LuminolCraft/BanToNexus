package org.luminolcraft.banToNexus

import com.electronwill.nightconfig.core.file.CommentedFileConfig
import java.io.File
import java.net.URI
import kotlin.jvm.optionals.getOrElse

class ConfigManager {
    private val configFile = File(Main.dataDirectory.toFile(), "config.yml")
    private val config = CommentedFileConfig.of(configFile)

    @Volatile
    var url = ""

    @Volatile
    var secret = ""

    @Volatile
    var connectionTimeoutSec = 0L

    @Volatile
    var requestTimeoutSec = 0L

    @Volatile
    var pendingFlushBatchSize = 0L

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
        config.load()
        if (config.getOptional<String>("api-base-url").isEmpty) {
            config.set<String>("api-base-url", "http://localhost:8080")
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
        if (config.getOptional<Long>("pending-flush-batch-size").isEmpty) {
            config.set<Long>("pending-flush-batch-size", 20L)
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
        run {
            val apiBaseUrlRaw = config.getOptional<String>("api-base-url")
            if (apiBaseUrlRaw.isPresent) {
                val scheme = URI.create(apiBaseUrlRaw.get()).scheme
                if (scheme == "http" || scheme == "https") {
                    url = apiBaseUrlRaw.get()
                    return@run null
                }
            }
            url = "http://localhost:8080"
        }
        secret = config.getOptional<String>("secret").getOrElse { "some-fucking-secret" }
        connectionTimeoutSec = config.getOptional<Long>("connection-timeout-sec").getOrElse { 5L }
        if (connectionTimeoutSec <= 0) connectionTimeoutSec = 5L
        requestTimeoutSec = config.getOptional<Long>("request-timeout-sec").getOrElse { 10L }
        if (requestTimeoutSec <= 0) requestTimeoutSec = 10L
        pendingFlushBatchSize = config.getOptional<Long>("pending-flush-batch-size").getOrElse { 20L }
        if (pendingFlushBatchSize <= 0) pendingFlushBatchSize = 20L
        wsPingIntervalSec = config.getOptional<Long>("ws-ping-interval-sec").getOrElse { 30L }
        if (wsPingIntervalSec <= 0) wsPingIntervalSec = 30L
        reconnectDelaySec = config.getOptional<Long>("reconnect-delay-sec").getOrElse { 60L }
        if (reconnectDelaySec <= 0) reconnectDelaySec = 60L
    }
    fun reloadConfig(){
        initAndLoadConfig()
    }
}