package xyz.geik.farmer.guis;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.helpers.gui.GroupItems;
import xyz.geik.farmer.helpers.gui.GuiHelper;
import xyz.geik.farmer.listeners.backend.ChatEvent;
import xyz.geik.farmer.integrations.bedrock.BedrockMenuKind;
import xyz.geik.farmer.integrations.bedrock.BedrockMenus;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.model.user.FarmerPerm;
import xyz.geik.farmer.model.user.User;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.chat.Placeholder;
import xyz.geik.glib.shades.inventorygui.DynamicGuiElement;
import xyz.geik.glib.shades.inventorygui.GuiElementGroup;
import xyz.geik.glib.shades.inventorygui.InventoryGui;
import xyz.geik.glib.shades.inventorygui.StaticGuiElement;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gui of user list
 * Users can be listed in this gui
 * Also promote demote user here
 * and add new user here
 */
public class UsersGui {

    /**
     * Constructor of class
     */
    public UsersGui() {
    }

    /**
     * Opens gui of users
     *
     * @param player to show gui
     * @param farmer of region
     */
    public static void showGui(Player player, @NotNull Farmer farmer) {
        // Gui interface array
        String[] userGui = Main.getConfigFile().getGui().getUsersLayout().toArray(new String[0]);

        // Inventory object (optimize placeholder parsing)
        InventoryGui gui = new InventoryGui(Main.getInstance(), null,
                ChatUtils.color(Main.getLangFile().getGui().getUsersGui().getGuiName()),
                userGui);

        gui.setFiller(GuiHelper.getFiller(player));
        gui.addElement(GuiHelper.createGuiElement(GuiHelper.getHelpItemForUsers(player), 'h'));
        gui.addElement(GuiHelper.createNextPage(player));
        gui.addElement(GuiHelper.createPreviousPage(player));

        // Add user icon
        gui.addElement(new StaticGuiElement('a',
                GuiHelper.getAddUserItem(player),
                1,
                click -> {
                    UUID playerId = player.getUniqueId();
                    if (User.getUserAmount(player) <= farmer.getUsers().size()) {
                        ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getReachedMaxUser());
                        return true;
                    }

                    if (!farmer.getOwnerUUID().equals(player.getUniqueId())
                            && !player.hasPermission("farmer.admin")) {
                        ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getNoPerm());
                        ChatEvent.clearPlayer(playerId);
                        return true;
                    }
                    // Optimize player adding to chat event
                    String regionId = farmer.getRegionID();
                    ChatEvent.getPlayers().put(playerId, regionId);

                    ChatUtils.sendMessage(player,
                            Main.getLangFile().getMessages().getWaitingInput(),
                            new Placeholder("{cancel}", Main.getLangFile().getVarious().getInputCancelWord()));
                    // Removes player from cache of ChatEvent catcher after 6 seconds
                    Main.getMorePaperLib().scheduling().entitySpecificScheduler(player).runDelayed(() -> {
                        if (ChatEvent.getPlayers().remove(playerId, regionId)) {
                            ChatEvent.clearPlayer(playerId);
                            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getInputCancel());
                        }
                    }, null, 120L);
                    Main.getMorePaperLib().scheduling().entitySpecificScheduler(player).run(() -> gui.close(), null);
                    return true;
                })
        );

        // Optimize user list processing
        List<User> sortedUsers = farmer.getUsers().stream()
                .sorted((o1, o2) -> FarmerPerm.getRoleId(o2.getPerm()) - FarmerPerm.getRoleId(o1.getPerm()))
                .collect(Collectors.toList());

        GuiElementGroup group = new GuiElementGroup('u');

        // Precompute user items to reduce dynamic computation
        for (User user : sortedUsers) {
            DynamicGuiElement  userElement = new DynamicGuiElement('s', (viewer) -> new StaticGuiElement('s',
                    GroupItems.getUserItem(user),
                    1,
                    click -> {
                        boolean response = false;
                        if (click.getType().equals(ClickType.LEFT) || click.getType().equals(ClickType.RIGHT))
                            response = User.updateUserRole(user, farmer);
                        else if (click.getType().equals(ClickType.SHIFT_RIGHT)) {
                            response = farmer.removeUser(user);
                            if (response) {
                                gui.destroy();
                                return true;
                            }
                        }
                        if (response)
                            gui.draw();
                        return true;
                    }
            ));
            group.addElement(userElement);
        }

        gui.addElement(group);
        BedrockMenus.openOrJava(player, gui, () -> showGui(player, farmer), BedrockMenuKind.USERS);
    }
}
