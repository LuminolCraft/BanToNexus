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