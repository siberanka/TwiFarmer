package xyz.geik.farmer.listeners.backend;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.helpers.WorldHelper;
import xyz.geik.farmer.integrations.bedrock.BedrockMenus;
import xyz.geik.farmer.model.Farmer;

import java.util.UUID;

/**
 * Player quit event basically save farmer when player quits
 */
public class QuitEvent implements Listener {

    /**
     * Constructor of class
     */
    public QuitEvent() {}

    /**
     * Update database when a player disconnects in a
     * Farmer region. All farmers save on stop only.
     * (No need to be a user of a farmer)
     *
     * @param e quit event
     */
    @EventHandler
    public void onQuitEvent(@NotNull PlayerQuitEvent e) {
        UUID playerId = e.getPlayer().getUniqueId();
        ChatEvent.clearPlayer(playerId);
        BedrockMenus.clear(e.getPlayer());
        final Location loc = e.getPlayer().getLocation();
        if (Main.getIntegration() == null || !WorldHelper.isFarmerAllowed(loc.getWorld().getName()))
            return;

        try {
            String regionID = Main.getIntegration().getRegionID(loc);
            if (regionID == null)
                return;
            Farmer farmer = FarmerManager.getFarmers().get(regionID);
            if (farmer != null)
                farmer.saveFarmerAsync();
        } catch (Exception ignored) {
            // A region integration can be unavailable while the server is shutting down.
        }
    }
}
