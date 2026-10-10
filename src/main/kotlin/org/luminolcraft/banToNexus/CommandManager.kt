package org.luminolcraft.banToNexus

import com.velocitypowered.api.command.SimpleCommand
import kotlinx.coroutines.launch

class CommandManager : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation?) {
        if (invocation == null) return
        val source = invocation.source()
        val args = invocation.arguments()
        if (args.isEmpty() || !source.hasPermission(Main.permissionNode) || args.size != 1) {
            source.sendMessage(Main.miniMessage.deserialize("<red><bold>No Permission or wrong usage."))
            return
        }
        when (args[0]){
            "sync" ->{
                Main.scope.launch {
                    Main.getFullBans()
                    source.sendMessage(Main.miniMessage.deserialize("<green>Sync successfully."))
                }
            }
            "reload" ->{
                Main.scope.launch {
                    Main.configManager.reloadConfig()
                    source.sendMessage(Main.miniMessage.deserialize("<green>Config reloaded successfully."))
                }
            }
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation?): Boolean {
        if (invocation == null) return false
        return invocation.source().hasPermission(Main.permissionNode)
    }

    override fun suggest(invocation: SimpleCommand.Invocation?): List<String?>? {
        if (invocation == null) return null
        if (invocation.source().hasPermission(Main.permissionNode)) {
            return listOf("sync", "reload")
        }
        return null
    }
}