package org.luminolcraft.banToNexus

import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.utils.io.CancellationException
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import kotlinx.serialization.json.*
import org.luminolcraft.banToNexus.Main.Companion.instance
import space.arim.libertybans.api.ConsoleOperator
import space.arim.libertybans.api.PlayerVictim
import space.arim.libertybans.api.PunishmentType
import space.arim.libertybans.api.punish.Punishment
import java.time.Duration
import java.time.Instant
import java.util.*

class SocketManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                try {
                    Main.httpClient.webSocket(
                        urlString = Main.configManager.wsUrl + "/api/v1/internal/ban/ws",
                        request = {
                            // 覆盖 ConfigManager 里全局的 HttpTimeout：那条是为普通请求设的，
                            // 会按 requestTimeoutSec 把已建立的 WS 会话一起掐掉
                            timeout {
                                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            }

                        }
                    ) {
                        session = this
                        instance.logger.info("Ban sync WS 已连接：${Main.configManager.wsUrl}")
                        runCatching {
                            session?.send(Frame.Text(buildJsonObject {
                                put("v", 1)
                                put("eventId", UUID.randomUUID().toString())
                                put("type", "sync.ping")
                                put("occurredAt", System.currentTimeMillis())
                                put("stateVersion", Main.stateVersion.get())
                            }.toString()))
                        }
                        val keepAlive = launch {
                            while (isActive) {
                                delay(Duration.ofSeconds(Main.configManager.wsPingIntervalSec))
                                send(Frame.Text("ping"))
                            }
                        }
                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        // 文本 "pong" 是服务端对心跳 "ping" 的自动回复，不是业务帧；
                                        // 这里必须 continue 而不能 break，否则每轮心跳都会拆掉连接
                                        if (text.equals("pong")) continue
                                        val obj = runCatching {
                                            Json.parseToJsonElement(text).jsonObject
                                        }.getOrNull() ?: continue
                                        val type = runCatching { obj["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                                            ?: continue
                                        val stateVersion =
                                            runCatching { obj["stateVersion"]?.jsonPrimitive?.longOrNull }.getOrNull()
                                                ?: continue
                                        when (type) {
                                            "sync.ack" -> {
                                                val resync =
                                                    obj["resync"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() }
                                                        ?: true
                                                if (resync) Main.getFullBans()
                                            }

                                            "ban.create", "ban.update", "ban.revoke" -> {
                                                // 帧内版本与本地游标不连续（丢帧或被重放）时，
                                                // 立即重拉一次全量快照，不再等周期检测
                                                if (stateVersion != Main.stateVersion.get() + 1L) {
                                                    Main.getFullBans()
                                                }
                                                when (type) {
                                                    "ban.create" -> {
                                                        val createdBan = runCatching {
                                                            val payload =
                                                                obj["payload"]?.jsonObject ?: continue
                                                            val createdBan = Main.Ban(
                                                                payload["playerName"]?.jsonPrimitive?.contentOrNull
                                                                    ?: continue,
                                                                payload["uuid"]?.jsonPrimitive?.contentOrNull
                                                                    ?: continue,
                                                                payload["reason"]?.jsonPrimitive?.contentOrNull
                                                                    ?: "",
                                                                "CONSOLE",
                                                                obj["occurredAt"]?.jsonPrimitive?.longOrNull
                                                                    ?: System.currentTimeMillis(),
                                                                payload["expiresAt"]?.jsonPrimitive?.longOrNull
                                                            )
                                                            val draftBanBuilder =
                                                                Main.apiLibertyBans.drafter.draftBuilder()
                                                                    .type(PunishmentType.BAN)
                                                                    .victim(
                                                                        PlayerVictim.of(
                                                                            UUID.fromString(
                                                                                createdBan.uuid
                                                                            )
                                                                        )
                                                                    )
                                                                    .reason(createdBan.reason)
                                                                    .operator(ConsoleOperator.INSTANCE)

                                                            if (createdBan.expiredAt != null && createdBan.expiredAt > System.currentTimeMillis()) {
                                                                draftBanBuilder.duration(
                                                                    Duration.ofMillis(
                                                                        createdBan.expiredAt - System.currentTimeMillis()
                                                                    )
                                                                )
                                                            }
                                                            draftBanBuilder.build().enactPunishment()
                                                        }
                                                    }

                                                    "ban.update" -> {
                                                        val payload = obj["payload"]?.jsonObject ?:continue
                                                        val updatedBan = Main.Ban(
                                                            payload["playerName"]?.jsonPrimitive?.contentOrNull
                                                                ?: continue,
                                                            payload["uuid"]?.jsonPrimitive?.contentOrNull
                                                                ?: continue,
                                                            payload["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                                                            "CONSOLE",
                                                            obj["occurredAt"]?.jsonPrimitive?.longOrNull
                                                                ?: System.currentTimeMillis(),
                                                            payload["expiresAt"]?.jsonPrimitive?.longOrNull
                                                        )
                                                        val selectedBan =
                                                            Main.apiLibertyBans.selector.selectionBuilder()
                                                                .victim(PlayerVictim.of(UUID.fromString(updatedBan.uuid)))
                                                                .type(PunishmentType.BAN)
                                                                .selectActiveOnly()
                                                                .build()
                                                                .allSpecificPunishments
                                                                .thenAccept {
                                                                    if (it.size != 1) {
                                                                        instance.logger.warn("Can't identify which punishment to update")
                                                                        return@thenAccept
                                                                    }
                                                                    it[0].modifyPunishment {
                                                                        it.setReason(updatedBan.reason)
                                                                        it.setEndDate(
                                                                            if (updatedBan.expiredAt != null) Instant.ofEpochMilli(
                                                                                updatedBan.expiredAt
                                                                            )
                                                                            else Punishment.PERMANENT_END_DATE
                                                                        )
                                                                    }.exceptionally {
                                                                        instance.logger.error("Fail to sync, trace:" + it.message)
                                                                        null
                                                                    }
                                                                }
                                                    }

                                                    "ban.revoke" -> {
                                                        val payload = obj["payload"]?.jsonObject ?: continue
                                                        val uuid = payload["uuid"]?.jsonPrimitive?.contentOrNull
                                                            ?: continue
                                                        Main.apiLibertyBans.revoker.revokeByTypeAndVictim(
                                                            PunishmentType.BAN,
                                                            PlayerVictim.of(UUID.fromString(uuid))
                                                        ).undoPunishment()
                                                    }
                                                }
                                                // 应用完成后才推进游标，且只前进不后退，
                                                // 避免乱序到达的旧帧把游标拉回去
                                                if (stateVersion > Main.stateVersion.get()) Main.stateVersion.set(
                                                    stateVersion
                                                )

                                            }
                                        }
                                    }

                                    is Frame.Close -> {
                                        break
                                    }

                                    else -> Unit
                                }
                            }
                        } finally {
                            keepAlive.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                } catch (e: Exception) {
                    // 401/403 是凭据或地址错误，属于配置问题，退避重连治不好；
                    // 其余异常（网络抖动、服务端重启）才值得重试
                    val fatal = run {
                        (e as? ClientRequestException)?.let { return@run it.response.status.value }
                        val m = Regex("\\b(401|403)\\b").find(e.message ?: "") ?: return@run null
                        return@run m.groupValues[1].toIntOrNull()
                    }
                    if (fatal != null) {
                        instance.logger.error("Ban sync 握手被拒（HTTP $fatal）。secret / ban-sync-ws-url 错，停止重连。")
                        return@launch
                    }
                    instance.logger.warn("Ban sync 连接断开: ${e.message}")
                    delay(Duration.ofSeconds(Main.configManager.reconnectDelaySec))
                }
            }
        }
    }

    // stop() 只结束当前连接循环，不取消 scope：配置热重载需要 stop() 后立刻 start()，
    // 若在这里 cancel() scope，而 scope 是构造时创建的 val，之后就再也起不来了。
    // 进程退出时才调用 stopScope()
    fun stop() {
        session?.let { s ->
            runBlocking { runCatching { s.close(CloseReason(CloseReason.Codes.NORMAL, "shutdown")) } }
        }
        loop?.cancel()
    }
    fun stopScope(){
        scope.cancel()
    }
}