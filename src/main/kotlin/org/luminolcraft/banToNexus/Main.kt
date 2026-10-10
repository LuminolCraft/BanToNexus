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
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger
import space.arim.libertybans.api.*
import space.arim.omnibus.Omnibus
import space.arim.omnibus.OmnibusProvider
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.coroutines.cancellation.CancellationException

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
        val permissionNode = "bantonexus.usage"
        val gson = Gson()
        val retryPackageMap = mutableMapOf<String, ActionType>()
        var stateVersion = 0L
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val miniMessage = MiniMessage.miniMessage()
        lateinit var configManager: ConfigManager
        lateinit var httpClient: HttpClient
        lateinit var apiOmni: Omnibus
        lateinit var apiLibertyBans: LibertyBans
        private var timerJob: Job? = null

        fun isRequiredResync(responseBody: String): Boolean {
            val jsonObject = JsonParser.parseString(responseBody).asJsonObject
            val element = jsonObject.getAsJsonObject("data").get("resync")
            return element.isJsonNull || element.asBoolean
        }

        suspend fun getFullBans() {
            val response: HttpResponse
            try {
                response = httpClient.get(configManager.url + "/api/v1/internal/ban/sync")
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
            val jsonObject = JsonParser.parseString(response.bodyAsText()).asJsonObject
            if (jsonObject.get("stateVersion").isJsonNull) return
            stateVersion = jsonObject.get("stateVersion").asLong
            if (jsonObject.get("bans").isJsonNull) return
            val type = object : TypeToken<List<Ban>>() {}.type
            val bans: List<Ban> = gson.fromJson(jsonObject.get("bans"), type)
            val bansUuidRemote: List<String> = bans.map { it.uuid }

            apiLibertyBans.selector
                .selectionBuilder()
                .type(PunishmentType.BAN)
                .selectActiveOnly()
                .victimType(Victim.VictimType.PLAYER)
                .build()
                .allSpecificPunishments
                .thenAccept {
                    //Handle dupe.
                    for (punish in it) {
                        if (punish.victim.type != Victim.VictimType.PLAYER) continue
                        val localUuid = (punish.victim as PlayerVictim).uuid.toString()
                        if (bansUuidRemote.contains(localUuid)) {
                            val num = bansUuidRemote.indexOf(localUuid)
                            if (bans[num].expiredAt == punish.endDate.toEpochMilli()) {
                                if (bans[num].reason == punish.reason) continue
                            }
                            punish.modifyPunishment {
                                it.setEndDate(Instant.ofEpochMilli(bans[num].expiredAt))
                                it.setReason(bans[num].reason)
                            }.exceptionally {
                                instance.logger.error("Fail to sync, trace:" + it.message)
                                null
                            }
                            bans.drop(num)
                        }
                    }
                }
            for (ban in bans) {
                val draftBan = apiLibertyBans.drafter
                    .draftBuilder()
                    .type(PunishmentType.BAN)
                    .reason(ban.reason)
                    .victim(PlayerVictim.of(UUID.fromString(ban.uuid)))
                    .operator(ConsoleOperator.INSTANCE)
                    .duration(Duration.ofMillis(ban.expiredAt - System.currentTimeMillis()))
                    .build()
                draftBan.enactPunishment()
            }
        }

        suspend fun postAddBan(envelope: Ban) {
            val packagedEnvelope = gson.toJson(envelope)
            val response: HttpResponse
            try {
                response = httpClient.post(configManager.url + "/api/v1/internal/ban/add") {
                    contentType(ContentType.Application.Json)
                    setBody(packagedEnvelope)
                }
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                instance.logger.error("Error while posting ban add." + e.message)
                retryPackageMap[packagedEnvelope] = ActionType.ADD
                return
            }

            if (!response.status.isSuccess()) {
                instance.logger.warn("Failed to post ban add.")
                retryPackageMap[packagedEnvelope] = ActionType.ADD
            }
            isRequiredResync(response.bodyAsText())
            if (isRequiredResync(response.bodyAsText())) {
                scope.launch {
                    getFullBans()
                }
                retryPackageMap[packagedEnvelope] = ActionType.ADD
            }
        }

        suspend fun postRevokeBan(envelope: Ban) {
            val packagedEnvelope = gson.toJson(envelope)
            val response: HttpResponse
            try {
                response = httpClient.post(configManager.url + "/api/v1/internal/ban/revoke") {
                    contentType(ContentType.Application.Json)
                    header("X-Ban-State-Version", stateVersion)
                    setBody(packagedEnvelope)
                }
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                instance.logger.error("Error while posting ban revoke." + e.message)
                retryPackageMap[packagedEnvelope] = ActionType.REVOKE
                return
            }
            if (!response.status.isSuccess()) {
                instance.logger.warn("Failed to post ban revoke.")
                retryPackageMap[packagedEnvelope] = ActionType.REVOKE
                return
            }
            if (isRequiredResync(response.bodyAsText())) {
                scope.launch {
                    getFullBans()
                }
                retryPackageMap[packagedEnvelope] = ActionType.REVOKE
            }
        }
    }

    data class Ban(
        val playerName: String,
        val uuid: String,
        val reason: String,
        val operator: String,
        val createdAt: Long,
        val expiredAt: Long
    )

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        instance = this
        configManager = ConfigManager()
        configManager.initAndLoadConfig()
        configManager.initHttpClient()
        server.eventManager.register(this, EventManager())
        apiOmni = OmnibusProvider.getOmnibus()
        apiLibertyBans = apiOmni.registry.getProvider(LibertyBans::class.java).orElseThrow()
    }

    @Subscribe
    fun onProxyShutDown(event: ProxyShutdownEvent) {
        server.eventManager.unregisterListeners(this)
        httpClient.close()
    }

    fun startTimerJob() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                if (retryPackageMap.isEmpty()) {
                    delay(Duration.ofSeconds(configManager.retryIntervalSec))
                    return@launch
                }
                val addBatch = mutableListOf<String>()
                val revokeBatch = mutableListOf<String>()
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
                for (i in 1..addBatch.size step configManager.pendingFlushBatchSize) {
                    val sendBatch = addBatch.subList(i, i + 20)
                    val packagedBatchEnvelope = gson.toJson(sendBatch)
                    val response: HttpResponse
                    try {
                        response = httpClient.post(configManager.url + "/api/v1/internal/ban/revoke") {
                            contentType(ContentType.Application.Json)
                            header("X-Ban-State-Version", stateVersion)
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
                    if (isRequiredResync(response.bodyAsText())) {
                        scope.launch {
                            getFullBans()
                        }
                        sendBatch.forEach {
                            retryPackageMap[it] = ActionType.ADD
                        }
                        continue
                    }
                }
                for (envelope in revokeBatch) {
                    val response: HttpResponse
                    try {
                        response = httpClient.post(configManager.url + "/api/v1/internal/ban/revoke") {
                            contentType(ContentType.Application.Json)
                            header("X-Ban-State-Version", stateVersion)
                            setBody(envelope)
                        }
                    } catch (e: CancellationException) {
                        return@launch
                    } catch (e: Exception) {
                        instance.logger.error("Error while syncing ban revoke." + e.message)
                        retryPackageMap[envelope] = ActionType.REVOKE
                        continue
                    }
                    if (!response.status.isSuccess()) {
                        instance.logger.warn("Failed to syncing ban revoke.")
                        retryPackageMap[envelope] = ActionType.REVOKE
                        continue
                    }
                    if (isRequiredResync(response.bodyAsText())) {
                        scope.launch {
                            getFullBans()
                        }
                        retryPackageMap[envelope] = ActionType.REVOKE
                    }
                }
            }
        }
    }

    fun stopTimerJob() {
        timerJob?.cancel()
        timerJob = null
    }

    //TODO sync logic.
}
