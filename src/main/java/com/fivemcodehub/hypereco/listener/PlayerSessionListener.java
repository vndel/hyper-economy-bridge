package com.fivemcodehub.hypereco.listener;

import com.fivemcodehub.hypereco.service.EconomyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerSessionListener implements Listener {

    private final EconomyService economy;

    public PlayerSessionListener(EconomyService economy) {
        this.economy = economy;
    }

    /**
     * Pre-login already runs off the main thread, so the account warms up
     * before the player spawns and the first /balance is a cache hit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        economy.loadAsync(event.getUniqueId(), event.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        economy.unload(event.getPlayer().getUniqueId());
    }
}
