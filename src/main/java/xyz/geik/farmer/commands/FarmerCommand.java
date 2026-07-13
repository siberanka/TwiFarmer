package xyz.geik.farmer.commands;

import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.FarmerAPI;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.guis.BuyGui;
import xyz.geik.farmer.guis.MainGui;
import xyz.geik.farmer.helpers.CacheLoader;
import xyz.geik.farmer.helpers.WorldHelper;
import xyz.geik.farmer.integrations.bedrock.BedrockMenus;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.model.FarmerLevel;
import xyz.geik.farmer.modules.FarmerModule;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.chat.Placeholder;
import xyz.geik.glib.module.ModuleManager;
import xyz.geik.glib.shades.triumphteam.cmd.bukkit.annotation.Permission;
import xyz.geik.glib.shades.triumphteam.cmd.core.BaseCommand;
import xyz.geik.glib.shades.triumphteam.cmd.core.annotation.Command;
import xyz.geik.glib.shades.triumphteam.cmd.core.annotation.Default;
import xyz.geik.glib.shades.triumphteam.cmd.core.annotation.SubCommand;
import xyz.geik.glib.shades.xseries.messages.Titles;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Farmer command class
 * for farmer commands
 *
 * @author geik, amownyy
 * @since v6-b100
 */
@RequiredArgsConstructor
@Command(value = "farmer", alias = {"farm", "çiftçi", "fm", "ciftci"})
public class FarmerCommand extends BaseCommand {
    private static final AtomicBoolean RELOADING = new AtomicBoolean();

    /**
     * Default command of farmer
     *
     * @param sender the command executor
     */
    @Default
    public void defaultCommand(@NotNull CommandSender sender) {
        if (!(sender instanceof Player)) {
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getUnknownCommand());
            return;
        }
        Player player = (Player) sender;
        if (!WorldHelper.isFarmerAllowed(player.getWorld().getName())) {
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getWrongWorld());
            return;
        }
        String regionID = getRegionID(player);
        if (regionID == null)
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoRegion());
        else if (!FarmerManager.getFarmers().containsKey(regionID)) {
            // Using this uuid for owner check
            UUID owner = Main.getIntegration().getOwnerUUID(regionID);
            // Owner check for buy
            if (owner.equals(player.getUniqueId())) {
                if (Main.getConfigFile().getSettings().isBuyFarmer())
                    BuyGui.showGui(player);
                else {
                    Titles.sendTitle(player, Main.getLangFile().getBuyDisabled().getTitle(),
                            Main.getLangFile().getBuyDisabled().getSubtitle());
                }
            }
            else
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getMustBeOwner());
        } else {
            // Perm && user check
            if (player.hasPermission("farmer.admin") ||
                    FarmerManager.getFarmers().get(regionID).getUsers().stream()
                            .anyMatch(usr -> (usr.getUuid().equals(player.getUniqueId()))))
                MainGui.showGui(player, FarmerManager.getFarmers().get(regionID));
            else
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoPerm());
        }
    }

    /**
     * Remove command of farmer
     *
     * @param sender command sender
     */
    @Permission("farmer.admin")
    @SubCommand(value = "remove", alias = {"sil", "r"})
    public void removeCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getUnknownCommand());
            return;
        }
        Player player = (Player) sender;
        String regionID = getRegionID(player);
        if (regionID == null) {
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoRegion());
            return;
        }

        UUID ownerUUID = Main.getIntegration().getOwnerUUID(regionID);
        // Custom perm check for remove command
        if (player.hasPermission("farmer.remove") && ownerUUID.equals(player.getUniqueId()) || player.hasPermission("farmer.admin")) {
            // Removing by #FarmerAPI and sending message by result
            boolean result = FarmerAPI.getFarmerManager().removeFarmer(regionID, ownerUUID);
            if (result)
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getRemovedFarmer());
        } else
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoPerm());
    }

    /**
     * About command of farmer
     *
     * @param player command sender
     */
    @SubCommand(value = "about", alias = {"hakkında", "pl", "ver", "version", "bilgi"})
    public void aboutCommand(@NotNull CommandSender player) {
        if (!(player.hasPermission("farmer.admin")) &&
                ((player instanceof Player)
                        || (player.getName().equals("Geyik")
                        || player.getName().equals("Amownyy")))) {
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoPerm());
            return;
        }
        sendLines(player, Main.getLangFile().getCommands().getAbout(),
                new Placeholder("{version}", Main.getInstance().getDescription().getVersion()),
                new Placeholder("{authors}", String.join(", ", Main.getInstance().getDescription().getAuthors())),
                new Placeholder("{api}", Main.getIntegration().getClass().getSimpleName()),
                new Placeholder("{economy}", Main.getEconomy().getClass().getSimpleName()),
                new Placeholder("{farmer_count}", String.valueOf(FarmerManager.getFarmers().size())),
                new Placeholder("{language}", Main.getConfigFile().getSettings().getLang()),
                new Placeholder("{modules}", ModuleManager.getModules().values().stream()
                        .map(module -> module.getName()).collect(Collectors.joining(", "))));
    }

    /**
     * Info command of farmer
     *
     * @param sender command sender
     */
    @Permission("farmer.admin")
    @SubCommand(value = "info", alias = {"bilgi", "inf"})
    public void infoCommand(@NotNull CommandSender sender) {
        if (!(sender instanceof Player)) {
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getUnknownCommand());
            return;
        }
        Player player = (Player) sender;
        String regionID = getRegionID(player);
        if (regionID == null)
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoRegion());
        else if (!FarmerManager.getFarmers().containsKey(regionID))
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoFarmer());
        else {
            Farmer farmer = FarmerManager.getFarmers().get(regionID);
            String ownerName = Bukkit.getOfflinePlayer(farmer.getOwnerUUID()).getName();
            sendLines(player, Main.getLangFile().getCommands().getInfoHeader(),
                    new Placeholder("{region}", regionID),
                    new Placeholder("{id}", String.valueOf(farmer.getId())),
                    new Placeholder("{owner}", ownerName == null ? farmer.getOwnerUUID().toString() : ownerName),
                    new Placeholder("{level}", String.valueOf(FarmerLevel.getAllLevels().indexOf(farmer.getLevel()))));
            farmer.getUsers().forEach(user -> ChatUtils.sendMessage(player,
                    Main.getLangFile().getCommands().getInfoUser(),
                    new Placeholder("{player}", user.getName()),
                    new Placeholder("{role}", user.getPerm().name())));
            ChatUtils.sendMessage(player, Main.getLangFile().getCommands().getInfoSeparator());
            farmer.getInv().getItems().forEach(item -> ChatUtils.sendMessage(player,
                    Main.getLangFile().getCommands().getInfoItem(),
                    new Placeholder("{material}", item.getMaterial().name()),
                    new Placeholder("{amount}", String.valueOf(item.getAmount()))));
            ChatUtils.sendMessage(player, Main.getLangFile().getCommands().getInfoSeparator());
            farmer.getModuleAttributes().forEach((key, value) -> ChatUtils.sendMessage(player,
                    Main.getLangFile().getCommands().getInfoModule(),
                    new Placeholder("{key}", key),
                    new Placeholder("{value}", String.valueOf(value))));
        }
    }

    /**
     * Reload command of farmer
     *
     * @param sender command sender
     */
    @Permission("farmer.admin")
    @SubCommand(value = "reload", alias = {"rl", "yenile"})
    public void reloadCommand(@NotNull CommandSender sender) {
        if (!RELOADING.compareAndSet(false, true)) {
            sendReloadMessage(sender, Main.getLangFile().getMessages().getReloadInProgress());
            return;
        }

        long startedAt = System.currentTimeMillis();
        Main.getMorePaperLib().scheduling().asyncScheduler().run(() -> {
            try {
                Main.getSql().updateAllFarmers();
                Main.getInstance().reloadConfigurationFiles();
                Main.getMorePaperLib().scheduling().globalRegionalScheduler().run(() -> {
                    try {
                        Main.getInstance().restartUpdateChecker();
                        BedrockMenus.initialize();
                        CacheLoader.loadAllItems();
                        CacheLoader.loadAllLevels();
                        Main.getInstance().getModuleManager().reloadModules();
                        FarmerModule.calculateModulesUseGui();
                        WorldHelper.loadAllowedWorlds();

                        Main.getSql().loadAllFarmersAsync(
                                () -> notifyReloadSuccess(sender, startedAt),
                                () -> notifyReloadFailure(sender, Main.getLangFile().getMessages().getReloadDatabaseFailed()));
                    } catch (Exception exception) {
                        Main.getInstance().getLogger().severe("Farmer reload failed: " + exception.getMessage());
                        notifyReloadFailure(sender, Main.getLangFile().getMessages().getReloadFailed());
                    }
                });
            } catch (Exception exception) {
                Main.getInstance().getLogger().severe("Farmer reload failed while saving data: " + exception.getMessage());
                notifyReloadFailure(sender, Main.getLangFile().getMessages().getReloadFailed());
            }
        });
    }

    private void notifyReloadSuccess(CommandSender sender, long startedAt) {
        RELOADING.set(false);
        sendReloadMessage(sender, Main.getLangFile().getMessages().getReloadSuccess(),
                new Placeholder("%ms%", System.currentTimeMillis() - startedAt + "ms"));
    }

    private void notifyReloadFailure(CommandSender sender, String message) {
        RELOADING.set(false);
        sendReloadMessage(sender, message);
    }

    private void sendReloadMessage(CommandSender sender, String message, Placeholder... placeholders) {
        Runnable notification = () -> {
            ChatUtils.sendMessage(sender, message, placeholders);
        };
        if (sender instanceof Player) {
            Main.getMorePaperLib().scheduling().entitySpecificScheduler((Player) sender).run(notification, null);
        } else {
            Main.getMorePaperLib().scheduling().globalRegionalScheduler().run(notification);
        }
    }

    private void sendLines(CommandSender sender, Iterable<String> lines, Placeholder... placeholders) {
        for (String line : lines)
            ChatUtils.sendMessage(sender, line, placeholders);
    }

    /**
     * Open command of farmer
     *
     * @param sender command sender
     * @param target target player
     */
    @Permission("farmer.admin")
    @SubCommand(value = "open", alias = {"aç"})
    public void openCommand(@NotNull CommandSender sender, String target) {
        Player player = Bukkit.getPlayerExact(target);
        if (player == null) {
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getTargetPlayerNotAvailable());
            return;
        }
        if (!player.isOnline()) {
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getPlayerNotOnline());
            return;
        }
        // Check world is suitable for farmer
        if (!WorldHelper.isFarmerAllowed(player.getWorld().getName())) {
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getWrongWorld());
            return;
        }

        String regionID = getRegionID(player);
        if (regionID == null)
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getNoRegion());

        if (!FarmerManager.getFarmers().containsKey(regionID))
            ChatUtils.sendMessage(sender, Main.getLangFile().getMessages().getNoFarmer());
        else {
            MainGui.showGui(player, FarmerManager.getFarmers().get(regionID));
        }
    }

    /**
     * Gets region id with #Integration
     * if there has a region.
     *
     * @param player the command executor
     * @return String of region
     */
    private String getRegionID(Player player) {
        String regionID;
        // Simple try catch method for
        // compatibility with all plugins
        try {
            regionID = Main.getIntegration().getRegionID(player.getLocation());
        }
        catch (Exception e) {
            regionID = null;
        }
        return regionID;
    }
}
