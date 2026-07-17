package xyz.geik.farmer.guis;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.handlers.FarmerGuiItemClickEvent;
import xyz.geik.farmer.api.handlers.FarmerItemSellEvent;
import xyz.geik.farmer.api.handlers.FarmerMainGuiOpenEvent;
import xyz.geik.farmer.helpers.PlaceholderHelper;
import xyz.geik.farmer.helpers.gui.GroupItems;
import xyz.geik.farmer.helpers.gui.GuiHelper;
import xyz.geik.farmer.integrations.bedrock.BedrockMenuKind;
import xyz.geik.farmer.integrations.bedrock.BedrockMenus;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.model.inventory.FarmerItem;
import xyz.geik.farmer.model.user.FarmerPerm;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.shades.inventorygui.DynamicGuiElement;
import xyz.geik.glib.shades.inventorygui.GuiElementGroup;
import xyz.geik.glib.shades.inventorygui.InventoryGui;
import xyz.geik.glib.shades.inventorygui.StaticGuiElement;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.Arrays;
import java.util.Map;

/**
 * Main gui of farmer
 * Player can sell, take and can open
 * manage gui in this gui if they have
 * permission to do.
 */
public class MainGui {

    /**
     * Constructor of class
     */
    public MainGui() {}

    /**
     * Gui main command
     *
     * @param player to show gui
     * @param farmer of region
     */
    public static void showGui(Player player, Farmer farmer) {
        // Array of gui interface
        String[] guiSetup = Main.getConfigFile().getGui().getFarmerLayout().toArray(new String[0]);
        // Gui object
        InventoryGui gui = new InventoryGui(Main.getInstance(), null, PlaceholderHelper.parsePlaceholders(player, ChatUtils.color(Main.getLangFile().getGui().getFarmerGui().getGuiName())), guiSetup);
        // Fills empty spaces on  gui
        gui.setFiller(GuiHelper.getFiller(player));
        // Manage Icon element
        gui.addElement(new StaticGuiElement('m',
                // Manage item
                GuiHelper.getManageItemOnMain(farmer, player),
                1,
                // Event
                click -> {
                    // If player has admin perm or owner of farmer
                    if (player.hasPermission("farmer.admin")
                            || farmer.getOwnerUUID().equals(player.getUniqueId()))
                        ManageGui.showGui(player, farmer);
                    return true;
                })
        );
        // Help item
        gui.addElement(GuiHelper.createGuiElement(GuiHelper.getHelpItemForMain(player), 'h'));
        // Sell All Item
        gui.addElement(new StaticGuiElement('t',
                GuiHelper.getSellAll(player),
                1,
                click -> {
                    player.performCommand("farmer sell all");
                    return true;
                })
        );

        // Item group which farmer collects
        GuiElementGroup group = new GuiElementGroup('g');
        // Foreach item list
        for (FarmerItem item : farmer.getInv().getItems()) {
            // Element of group there can x amount of i
            group.addElement(new DynamicGuiElement('i', (viewer) -> new StaticGuiElement('i',
                    GroupItems.getGroupItem(farmer, item, player),
                    1,
                    click -> handleItemClick(player, farmer, item, click.getType(), gui))));
        }
        // Adding everything to gui and opening
        gui.addElement(group);
        gui.addElement(GuiHelper.createNextPage(player));
        gui.addElement(GuiHelper.createPreviousPage(player));
        FarmerMainGuiOpenEvent guiOpenEvent = new FarmerMainGuiOpenEvent(player, farmer, gui);
        Bukkit.getPluginManager().callEvent(guiOpenEvent);
        if (!guiOpenEvent.isCancelled())
            BedrockMenus.openOrJava(player, gui, () -> showGui(player, farmer), BedrockMenuKind.STORAGE);
    }

    private static boolean handleItemClick(Player player, Farmer farmer, FarmerItem item,
                                           ClickType clickType, InventoryGui gui) {
        boolean canManageStock = player.hasPermission("farmer.admin")
                || farmer.getUsers().stream().anyMatch(user -> !user.getPerm().equals(FarmerPerm.COOP)
                && user.getName().equalsIgnoreCase(player.getName()));
        if (!canManageStock)
            return true;

        XMaterial material = item.getMaterial();
        FarmerItem slotItem = farmer.getInv().getStockedItem(material);
        if (slotItem == null)
            return true;

        if (clickType.equals(ClickType.SHIFT_RIGHT)) {
            Bukkit.getPluginManager().callEvent(new FarmerItemSellEvent(farmer, slotItem, player));
        } else if (!clickType.equals(ClickType.RIGHT) && !clickType.equals(ClickType.LEFT)) {
            Bukkit.getPluginManager().callEvent(
                    new FarmerGuiItemClickEvent(farmer, slotItem, player, clickType, gui));
            return true;
        } else {
            if (invFull(player)) {
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getInventoryFull());
                return true;
            }

            ItemStack returnItem = material.parseItem();
            if (returnItem == null)
                return true;
            int maxStackSize = returnItem.getMaxStackSize();
            long count;
            if (clickType.equals(ClickType.LEFT)) {
                count = Math.min(slotItem.getAmount(), maxStackSize);
            } else {
                long playerSpace = (long) getEmptySlots(player) * maxStackSize;
                count = Math.min(slotItem.getAmount(), playerSpace);
            }
            if (count <= 0)
                return true;

            long remaining = count;
            long added = 0;
            while (remaining > 0 && !invFull(player)) {
                int stackAmount = (int) Math.min(remaining, maxStackSize);
                ItemStack stack = returnItem.clone();
                stack.setAmount(stackAmount);
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                int rejected = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                int accepted = stackAmount - rejected;
                if (accepted <= 0)
                    break;
                added += accepted;
                remaining -= accepted;
            }
            if (added > 0)
                slotItem.negateAmount(added);
        }
        gui.draw();
        return true;
    }

    /**
     * Checks if player has slot in inventory
     *
     * @param player to be checked
     * @return boolean status of inventory can take item
     */
    private static boolean invFull(@NotNull Player player) {
        return player.getInventory().firstEmpty() == -1;
    }

    /**
     * Gets all the empty slots
     *
     * @param player to be checked
     * @return int of empty slots
     */
    private static int getEmptySlots(@NotNull Player player) {
        int count = 0;
        for (int i = 0; i <= 35; i++) {
            if (player.getInventory().getItem(i) == null
                    || player.getInventory().getItem(i).getType().equals(Material.AIR)) {
                count++;
            } else
                continue;
        }
        return count;
    }
}
