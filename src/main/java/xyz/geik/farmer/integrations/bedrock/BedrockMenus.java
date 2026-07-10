package xyz.geik.farmer.integrations.bedrock;

import org.bukkit.entity.Player;
import xyz.geik.farmer.Main;
import xyz.geik.glib.shades.inventorygui.InventoryGui;

import java.util.ArrayList;
import java.util.List;

public final class BedrockMenus {
    private static volatile BedrockMenuService service;

    private BedrockMenus() {
    }

    public static void initialize() {
        shutdown();
        if (!Main.getConfigFile().getBedrockForms().isEnabled())
            return;

        List<Object> senders = new ArrayList<>();
        addSender(senders, "org.geysermc.floodgate.api.FloodgateApi",
                "xyz.geik.farmer.integrations.bedrock.FloodgateFormSender");
        addSender(senders, "org.geysermc.geyser.api.GeyserApi",
                "xyz.geik.farmer.integrations.bedrock.GeyserFormSender");
        if (senders.isEmpty())
            return;

        try {
            Class<?> serviceClass = Class.forName(
                    "xyz.geik.farmer.integrations.bedrock.CumulusBedrockMenuService",
                    true, Main.class.getClassLoader());
            java.lang.reflect.Constructor<?> constructor = serviceClass.getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            service = (BedrockMenuService) constructor.newInstance(senders);
            Main.getInstance().getLogger().info("Native Bedrock forms enabled with " + senders.size() + " provider(s).");
        } catch (ReflectiveOperationException | LinkageError exception) {
            Main.getInstance().getLogger().warning("Bedrock forms are unavailable: " + exception.getMessage());
        }
    }

    private static void addSender(List<Object> senders, String apiClass, String senderClass) {
        try {
            ClassLoader classLoader = Main.class.getClassLoader();
            Class.forName(apiClass, false, classLoader);
            Object sender = Class.forName(senderClass, true, classLoader)
                    .getDeclaredConstructor().newInstance();
            senders.add(sender);
        } catch (ClassNotFoundException ignored) {
            // Optional provider is not installed.
        } catch (ReflectiveOperationException | LinkageError exception) {
            Main.getInstance().getLogger().warning("Could not initialize Bedrock provider " + apiClass + ": "
                    + exception.getMessage());
        }
    }

    public static boolean openOrJava(Player player, InventoryGui gui, Runnable reopen, BedrockMenuKind kind) {
        BedrockMenuService current = service;
        if (current != null && current.open(player, gui, reopen, kind))
            return true;
        gui.show(player);
        return false;
    }

    public static boolean isBedrockPlayer(Player player) {
        BedrockMenuService current = service;
        return current != null && current.isBedrockPlayer(player);
    }

    public static void clear(Player player) {
        BedrockMenuService current = service;
        if (current != null)
            current.clear(player);
    }

    public static void shutdown() {
        BedrockMenuService current = service;
        service = null;
        if (current != null)
            current.shutdown();
    }
}
