package xyz.geik.farmer.listeners.backend;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.guis.UsersGui;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.chat.Placeholder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatEvent implements Listener {
    @Getter
    private static final Map<UUID, String> players = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_TIME = 500; // 500ms cooldown

    @EventHandler
    public void chatEvent(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!players.containsKey(playerId)) {
            return;
        }

        event.setCancelled(true);
        String input = event.getMessage();
        Main.getMorePaperLib().scheduling().entitySpecificScheduler(player).run(
                () -> processInput(player, playerId, input), null);
    }

    private void processInput(Player player, UUID playerId, String input) {
        String storedRegionID = players.get(playerId);
        if (storedRegionID == null)
            return;

        long currentTime = System.currentTimeMillis();
        Long lastInput = cooldowns.get(playerId);
        if (lastInput != null && currentTime - lastInput < COOLDOWN_TIME) {
            return;
        }
        cooldowns.put(playerId, currentTime);

        if (input.equalsIgnoreCase(Main.getLangFile().getVarious().getInputCancelWord())) {
            clearPlayer(playerId);
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getInputCancel());
            return;
        }
        String targetName = input.trim();
        UUID targetUUID = FastPlayerLookup.lookupPlayer(targetName);
        if (targetUUID == null) {
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getUserCouldntFound());
            clearPlayer(playerId);
            return;
        }

        try {
            Farmer farmer = FarmerManager.getFarmers().get(storedRegionID);
            if (farmer == null) {
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getFarmerDataUnavailable());
                return;
            }
            if (!farmer.getOwnerUUID().equals(player.getUniqueId())
                    && !player.hasPermission("farmer.admin")) {
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoPerm());
                return;
            }
            if (farmer.getUsers().stream().anyMatch(user -> user.getUuid().equals(targetUUID))) {
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getUserAlreadyExist(),
                        new Placeholder("{player}", targetName));
            } else {
                farmer.addUser(targetUUID, targetName);
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getUserAdded(),
                        new Placeholder("{player}", targetName));
            }

            UsersGui.showGui(player, farmer);
        } catch (Exception ex) {
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getUserCouldntFound());
        } finally {
            clearPlayer(playerId);
        }
    }

    public static void clearPlayer(UUID playerId) {
        players.remove(playerId);
        cooldowns.remove(playerId);
    }

    public static void clearPendingPlayers() {
        players.clear();
        cooldowns.clear();
    }
}
