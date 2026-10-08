package org.luminolcraft.banToNexus;

import com.google.gson.Gson
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.nio.file.Path

@Plugin(
    id = "bantonexus", name = "BanToNexus", version = "0.1", authors = ["NatJerry"]
)
class Main @Inject constructor(val logger: Logger, val server: ProxyServer, @DataDirectory val dataDirectory: Path) {
    companion object {
        lateinit var instance: Main
        val dataDirectory: Path
            get() = instance.dataDirectory
        val gson = Gson()
        lateinit var configManager: ConfigManager
    }

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        instance = this
        configManager = ConfigManager()
        configManager.initAndLoadConfig()
    }
}
