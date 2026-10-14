package org.luminolcraft.banToNexus

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.collections.*
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger
import space.arim.libertybans.api.*
import space.arim.libertybans.api.punish.Punishment
import space.arim.omnibus.Omnibus
import space.arim.omnibus.OmnibusProvider
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

@Plugin(
    id = "bantonexus", name = "BanToNexus", version = "0.1", authors = ["NatJerry"]
)
open class Main @Inject constructor(
    val logger: Logger,
    val server: ProxyServer,
    @DataDirectory val dataDirectory: Path
) {
    companion object {
        lateinit var instance: Main
        val dataDirectory: Path
            get() = instance.dataDirectory
        const val PERMISSION_NODE = "bantonexus.usage"
        val gson = Gson()
        val retryPackageMap = ConcurrentMap<Ban, ActionType>()
        val stateVersion = AtomicLong(0L)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val miniMessage = MiniMessage.miniMessage()
        val socketManager = SocketManager()
        lateinit var configManager: ConfigManager
        lateinit var httpClient: HttpClient
        lateinit var apiOmni: Omnibus
        lateinit var apiLibertyBans: LibertyBans
        private var timerJob: Job? = null


        fun isRequiredResync(responseBody: String): Boolean {
            runCatching {
                val jsonObject = runCatching {
                    JsonParser.parseString(responseBody).asJsonObject
                }.getOrElse {
                    instance.logger.error("Ban sync 响应不是合法 JSON：${it.message}")
                    return true
                }
                val element = jsonObject.getAsJsonObject("data").get("resync")
                return element.isJsonNull || element.asBoolean
            }.getOrElse {
                return true
            }
        }

        fun isRequiredIncrease(responseBody: String, stateVersion: Long): Boolean {
            runCatching {
                val jsonObject = JsonParser.parseString(responseBody).asJsonObject
                val element = jsonObject.getAsJsonObject("data").get("stateVersion").asLong
                return (element > stateVersion)
            }.getOrElse {
                return true
            }
        }

        fun startTimerJob() {
            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive) {
                    if (retryPackageMap.isEmpty()) {
                        delay(Duration.ofSeconds(configManager.retryIntervalSec))
                        continue
                    }
                    val addBatch = mutableListOf<Ban>()
                    val revokeBatch = mutableListOf<Ban>()
                    synchronized(retryPackageMap) {
                        for ([ban, type] in retryPackageMap) {
                            when (type) {
                                ActionType.ADD -> {
                                    addBatch.add(ban)
                                }

                                ActionType.REVOKE -> {
                                    revokeBatch.add(ban)
                                }
                            }
                        }
                    }
                    for (i in 0..addBatch.size step configManager.pendingFlushBatchSize) {
                        if (i >= addBatch.size) break
                        val sendBatch = addBatch.subList(i, min(i + configManager.pendingFlushBatchSize, addBatch.size))
                        val packagedBatchEnvelope = gson.toJson(sendBatch)
                        val response: HttpResponse
                        try {
                            response = httpClient.post(configManager.httpUrl + "/api/v1/internal/ban/sync") {
                                contentType(ContentType.Application.Json)
                                header("X-Ban-State-Version", stateVersion.get())
                                setBody(packagedBatchEnvelope)
                            }
                        } catch (e: CancellationException) {
                            return@launch
                        } catch (e: Exception) {
                            instance.logger.error("Error while syncing ban." + e.message)
                            sendBatch.forEach {
                                retryPackageMap[it] = ActionType.ADD
                            }
                            continue
                        }
                        if (!response.status.isSuccess()) {
                            instance.logger.error("Error while syncing ban.")
                            sendBatch.forEach {
                                retryPackageMap[it] = ActionType.ADD
                            }
                            continue
                        }
                        val text = response.bodyAsText()
                        if (isRequiredResync(text)) {
                            scope.launch {
                                getFullBans()
                            }
                            sendBatch.forEach {
                                retryPackageMap[it] = ActionType.ADD
                            }
                            continue
                        }
                        for (ban in sendBatch) {
                            retryPackageMap.remove(ban)
                        }
                        if (isRequiredIncrease(text, stateVersion.get())) stateVersion.incrementAndGet()
                    }
                    for (envelope in revokeBatch) {
                        val response: HttpResponse
                        try {
                            response = httpClient.post(configManager.httpUrl + "/api/v1/internal/ban/revoke") {
                                contentType(ContentType.Application.Json)
                                header("X-Ban-State-Version", stateVersion.get())
                                setBody(envelope)
                            }
                        } catch (e: CancellationException) {
                            return@launch
                        } catch (e: Exception) {
                            instance.logger.error("Error while syncing ban revoke." + e.message)
                            retryPackageMap[envelope] = ActionType.REVOKE
                            continue
                        }
                        if (response.status.value == 404) {
                            retryPackageMap.remove(envelope)
                            continue
                        }
                        if (!response.status.isSuccess()) {
                            instance.logger.warn("Failed to syncing ban revoke.")
                            retryPackageMap[envelope] = ActionType.REVOKE
                            continue
                        }
                        val text = response.bodyAsText()
                        if (isRequiredResync(text)) {
                            scope.launch {
                                getFullBans()
                            }
                            retryPackageMap[envelope] = ActionType.REVOKE
                            continue
                        }
                        retryPackageMap.remove(envelope)
                        if (isRequiredIncrease(text, stateVersion.get())) stateVersion.incrementAndGet()
                    }
                }
            }
        }

        fun stopTimerJob() {
            timerJob?.cancel()
            timerJob = null
        }

        suspend fun getFullBans() {
            val response: HttpResponse
            try {
                response = httpClient.get(configManager.httpUrl + "/api/v1/internal/ban/sync")
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                instance.logger.error("Error while syncing ban from Nexus." + e.message)
                return
            }
            if (!response.status.isSuccess()) {
                instance.logger.error("Error while syncing ban from Nexus. Code ${response.status}")
                return
            }
            val jsonObject = runCatching { JsonParser.parseString(response.bodyAsText()).asJsonObject }.getOrElse {
                instance.logger.error("Ban sync 响应不是合法 JSON：${it.message}")
                return
            }
            if (jsonObject.get("stateVersion").isJsonNull) return
            stateVersion.set(jsonObject.get("stateVersion").asLong)
            if (jsonObject.get("bans").isJsonNull) return
            val type = object : TypeToken<MutableList<Ban>>() {}.type
            val bans: MutableList<Ban> = gson.fromJson(jsonObject.get("bans"), type)
            val nextBans = bans.toMutableList()
            val bansUuidRemote: List<String> = bans.map { it.uuid }
            apiLibertyBans.selector
                .selectionBuilder()
                .type(PunishmentType.BAN)
                .selectActiveOnly()
                .victimType(Victim.VictimType.PLAYER)
                .build()
                .allSpecificPunishments
                .thenAcceptSync {
                    //Handle dupe.
                    for (punish in it) {
                        if (punish.victim.type != Victim.VictimType.PLAYER) continue
                        val localUuid = (punish.victim as PlayerVictim).uuid.toString()
                        if (bansUuidRemote.contains(localUuid)) {
                            val num = bansUuidRemote.indexOf(localUuid)
                            var checkReason = false
                            var isPermanent = false
                            if (bans[num].expiredAt == null && punish.endDate == Instant.MAX) {
                                checkReason = true
                            } else if (punish.endDate != Instant.MAX && bans[num].expiredAt == null) {
                                isPermanent = true
                            } else if (bans[num].expiredAt != null && punish.endDate == Instant.MAX) {
                                1
                            } else {
                                checkReason = true
                            }
                            if (checkReason) {
                                if (bans[num].reason == punish.reason) {
                                    nextBans.remove(bans[num])
                                    continue
                                }
                            }
                            var remove = true
                            punish.modifyPunishment {

//                                if(!isPermanent)bans[num].expiredAt?.let { epochMilli -> it.setEndDate(Instant.ofEpochMilli(epochMilli))
//                                else {it.setEndDate(Punishment.PERMANENT_END_DATE)}}
                                it.setEndDate(if (isPermanent || bans[num].expiredAt == null) Punishment.PERMANENT_END_DATE else bans[num].expiredAt?.let {
                                    Instant.ofEpochMilli(
                                        it
                                    )
                                })
                                it.setReason(bans[num].reason)
                            }.exceptionally {
                                instance.logger.error("Fail to sync, trace:" + it.message)
                                remove = false
                                null
                            }.toCompletableFuture().let {
                                runCatching { it.join() }.onFailure {
                                }
                            }
                            if (remove) {
                                nextBans.remove(bans[num])
                            }
                        }
                    }
                }.toCompletableFuture().let {
                    runCatching { it.join() }.onFailure {
                        instance.logger.error("Ban sync 对账失败：${it.message}")
                    }
                }

            for ((_, uuid, reason, _, _, expiredAt) in nextBans) {
                val draftBanBuilder = apiLibertyBans.drafter
                    .draftBuilder()
                    .type(PunishmentType.BAN)
                    .reason(reason)
                    .victim(PlayerVictim.of(UUID.fromString(uuid)))
                    .operator(ConsoleOperator.INSTANCE)
                if (expiredAt != null) {
                    draftBanBuilder.duration(Duration.ofMillis(expiredAt - System.currentTimeMillis()))
                }
                draftBanBuilder.build().enactPunishment().toCompletableFuture().let {
                    runCatching { it.join() }.onFailure {
                        instance.logger.error("Ban sync 对账失败：${it.message}")
                    }
                }
            }
        }

        suspend fun postAddBan(envelope: Ban) {
            val response: HttpResponse
            try {
                response = httpClient.post(configManager.httpUrl + "/api/v1/internal/ban/add") {
                    contentType(ContentType.Application.Json)
                    header("X-Ban-State-Version", stateVersion.get())
                    setBody(gson.toJson(envelope))
                }
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                instance.logger.error("Error while posting ban add." + e.message)
                retryPackageMap[envelope] = ActionType.ADD
                return
            }

            if (!response.status.isSuccess()) {
                instance.logger.warn("Failed to post ban add.")
                retryPackageMap[envelope] = ActionType.ADD
                return
            }
            val text = response.bodyAsText()
            if (isRequiredResync(text)) {
                scope.launch {
                    getFullBans()
                }
                retryPackageMap[envelope] = ActionType.ADD
            }
            if (isRequiredIncrease(text, stateVersion.get())) stateVersion.incrementAndGet()
        }

        suspend fun postRevokeBan(envelope: Ban) {
            val response: HttpResponse
            try {
                response = httpClient.post(configManager.httpUrl + "/api/v1/internal/ban/revoke") {
                    contentType(ContentType.Application.Json)
                    header("X-Ban-State-Version", stateVersion.get())
                    setBody(gson.toJson(envelope))
                }
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                instance.logger.error("Error while posting ban revoke." + e.message)
                retryPackageMap[envelope] = ActionType.REVOKE
                return
            }
            if (!response.status.isSuccess()) {
                instance.logger.warn("Failed to post ban revoke.")
                retryPackageMap[envelope] = ActionType.REVOKE
                return
            }
            val text = response.bodyAsText()
            if (isRequiredResync(text)) {
                scope.launch {
                    getFullBans()
                }
                retryPackageMap[envelope] = ActionType.REVOKE
            }

            if (isRequiredIncrease(text, stateVersion.get())) stateVersion.incrementAndGet()
        }
    }

    data class Ban(
        val playerName: String,
        val uuid: String,
        val reason: String,
        val operator: String,
        val createdAt: Long,
        val expiredAt: Long?
    )

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        instance = this
        configManager = ConfigManager()
        configManager.initAndLoadConfig()
        configManager.initHttpClient()
        apiOmni = OmnibusProvider.getOmnibus()
        apiLibertyBans = apiOmni.registry.getProvider(LibertyBans::class.java).orElseThrow()
        server.eventManager.register(this, EventManager())
        scope.launch {
            getFullBans()
        }
        startTimerJob()
        socketManager.start()
    }

    @Subscribe
    fun onProxyShutDown(event: ProxyShutdownEvent) {
        stopTimerJob()
        scope.cancel()
        socketManager.stop()
        socketManager.stopScope()
        server.eventManager.unregisterListeners(this)
        httpClient.close()
    }


}
