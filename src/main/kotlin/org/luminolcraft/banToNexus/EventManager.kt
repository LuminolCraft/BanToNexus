package org.luminolcraft.banToNexus

import com.velocitypowered.api.event.Subscribe
import kotlinx.coroutines.launch
import org.luminolcraft.banToNexus.Main.Companion.scope
import space.arim.libertybans.api.Operator
import space.arim.libertybans.api.PlayerOperator
import space.arim.libertybans.api.PlayerVictim
import space.arim.libertybans.api.PunishmentType
import space.arim.libertybans.api.event.PostPardonEvent
import space.arim.libertybans.api.event.PostPunishEvent
import java.time.Instant

class EventManager {

    @Subscribe
    fun onPunish(event: PostPunishEvent) {
        val punishment = event.punishment
        run {
            val punishType = punishment.type
            if (punishType != PunishmentType.BAN) return
        }
        val playerName: String
        run {
            val playerNameRaw = event.target
            if (playerNameRaw.isEmpty) return
            playerName = playerNameRaw.get()
        }
        val operator: String
        run {
            if (punishment.operator.type != Operator.OperatorType.PLAYER) {
                operator = "CONSOLE"
                return@run
            }
            val player = Main.instance.server.getPlayer((punishment.operator as PlayerOperator).uuid)
            if (player.isEmpty) return
            operator = player.get().username
        }
        val envelope = Main.Ban(
            playerName,
            (punishment.victim as PlayerVictim).uuid.toString(),
            punishment.reason,
            operator,
            punishment.startDate.toEpochMilli(),
            if(punishment.endDate != Instant.MAX) punishment.endDate.toEpochMilli() else null
        )
        scope.launch {
            Main.postAddBan(envelope)
        }
    }

    @Subscribe
    fun onPardon(event: PostPardonEvent) {
        val punishment = event.punishment
        run {
            val punishType = punishment.type
            if (punishType != PunishmentType.BAN) return
        }
        val playerName: String
        run {
            val playerNameRaw = event.target
            if (playerNameRaw.isEmpty) return
            playerName = playerNameRaw.get()
        }
        val operator: String
        run {
            if (punishment.operator.type != Operator.OperatorType.PLAYER) {
                operator = "CONSOLE"
                return@run
            }
            val player = Main.instance.server.getPlayer((punishment.operator as PlayerOperator).uuid)
            if (player.isEmpty) return
            operator = player.get().username
        }
        val envelope = Main.Ban(
            playerName,
            (punishment.victim as PlayerVictim).uuid.toString(),
            punishment.reason,
            operator,
            punishment.startDate.toEpochMilli(),
            if(punishment.endDate != Instant.MAX) punishment.endDate.toEpochMilli() else null
        )
        scope.launch {
            Main.postRevokeBan(envelope)
        }
    }
}