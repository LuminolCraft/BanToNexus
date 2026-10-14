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


        // 解析失败一律按“需要重拉快照”处理（fail-safe）：后端响应异常时，
        // 宁可贵一点多拉一次快照，也不要因为少判一次而漏掉封禁变更。
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
                    // 按配置的批量上限分片；上界必须取 min，否则最后一片会越过列表末尾
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
                        // 一次上报请求只会让后端的 stateVersion +1，成功即视为已送达；
                        // 不在这里移除的话队列永远非空，每一轮都会把同一批重新发一遍
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
                        // 404 是终态而不是临时故障：后端按 createdAt 定位封禁，
                        // 后台创建的封禁、已撤销的封禁都会命中这里，重试永远不会成功
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
            // 先落游标再应用：版本号与快照出自同一次事务读，所以先记下来；
            // 若后面的 apply 失败，该条目要等下一次快照才会重试
            stateVersion.set(jsonObject.get("stateVersion").asLong)
            if (jsonObject.get("bans").isJsonNull) return
            val type = object : TypeToken<MutableList<Ban>>() {}.type
            val bans: MutableList<Ban> = gson.fromJson(jsonObject.get("bans"), type)
            // nextBans 必须是副本：它是“还需要本地落地”的集合，下面会不断删元素；
            // bans 本身保持冻结，这样 bansUuidRemote 的下标才始终指向同一条记录
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
                            // 两个维度：bans[num].expiredAt 是后端到期时间（null = 永久），
                            // punish.endDate 是本地到期时间（Instant.MAX = 永久）。
                            // checkReason=true 表示“只需再比一次理由，相同就跳过改动”；
                            // isPermanent=true 表示后端是永久，要把本地改回永久。
                            // 注意：有限期对有限期的组合走最后的 else，并不比较到期时间是否相等。
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
                            // remove 只有在内层 stage 被 join 之后才能反映真实结果：
                            // exceptionally 是异步回调，若不 join，它还没执行到这里，
                            // remove 恒为 true，失败条目也会被移出 nextBans 而不再补落地
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
                    // draft 接收的是“从现在起多久”的相对时长而不是绝对时间戳；
                    // 为 null 表示永久处罚，此时不能调用 duration()
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
