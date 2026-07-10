package xyz.geik.farmer.api;

import org.bukkit.entity.Player;
import xyz.geik.farmer.integrations.bedrock.BedrockMenuKind;
import xyz.geik.farmer.integrations.bedrock.BedrockMenus;
import xyz.geik.glib.shades.inventorygui.InventoryGui;

/**
 * Routes module-owned InventoryGui menus through Farmer's native Bedrock form layer.
 */
public final class FarmerBedrockAPI {
    private FarmerBedrockAPI() {
    }

    public static boolean isBedrockPlayer(Player player) {
        return BedrockMenus.isBedrockPlayer(player);
    }

    public static boolean openModuleMenu(Player player, InventoryGui gui, Runnable reopen) {
        return BedrockMenus.openOrJava(player, gui, reopen, BedrockMenuKind.MODULE);
    }
}
