package xyz.geik.farmer.integrations.bedrock;

import org.bukkit.entity.Player;
import xyz.geik.glib.shades.inventorygui.InventoryGui;

interface BedrockMenuService {
    boolean isBedrockPlayer(Player player);

    boolean open(Player player, InventoryGui gui, Runnable reopen, BedrockMenuKind kind);

    void clear(Player player);

    void shutdown();
}
