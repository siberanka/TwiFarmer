package xyz.geik.farmer.integrations.bedrock;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.SimpleForm;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.configuration.LangFile;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.shades.inventorygui.GuiElement;
import xyz.geik.glib.shades.inventorygui.GuiElementGroup;
import xyz.geik.glib.shades.inventorygui.GuiPageElement;
import xyz.geik.glib.shades.inventorygui.InventoryGui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class CumulusBedrockMenuService implements BedrockMenuService {
    private final List<BedrockFormSender> senders;
    private final Map<UUID, FormSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastResponses = new ConcurrentHashMap<>();

    CumulusBedrockMenuService(List<BedrockFormSender> senders) {
        this.senders = new ArrayList<>(senders);
    }

    @Override
    public boolean isBedrockPlayer(Player player) {
        return findSender(player) != null;
    }

    @Override
    public boolean open(Player player, InventoryGui gui, Runnable reopen, BedrockMenuKind kind) {
        BedrockFormSender sender = findSender(player);
        if (sender == null)
            return false;
        showPage(player, sender, gui, reopen, kind, 0);
        return true;
    }

    @Override
    public void clear(Player player) {
        UUID playerId = player.getUniqueId();
        sessions.remove(playerId);
        lastResponses.remove(playerId);
    }

    @Override
    public void shutdown() {
        sessions.clear();
        lastResponses.clear();
    }

    private BedrockFormSender findSender(Player player) {
        for (BedrockFormSender sender : senders) {
            try {
                if (sender.isBedrockPlayer(player))
                    return sender;
            } catch (RuntimeException | LinkageError exception) {
                Main.getInstance().getLogger().fine("Bedrock provider lookup failed: " + exception.getMessage());
            }
        }
        return null;
    }

    private void showPage(Player player, BedrockFormSender sender, InventoryGui gui, Runnable reopen,
                          BedrockMenuKind kind, int requestedPage) {
        List<MenuEntry> entries = collectEntries(player, gui, kind);
        int pageSize = bounded(Main.getConfigFile().getBedrockForms().getPageSize(), 5, 50);
        int pageCount = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int from = Math.min(entries.size(), page * pageSize);
        int to = Math.min(entries.size(), from + pageSize);

        LangFile.BedrockForms lang = Main.getLangFile().getBedrockForms();
        String pageText = replace(lang.getPage(), "{page}", String.valueOf(page + 1),
                "{pages}", String.valueOf(pageCount));
        String content = entries.isEmpty() ? lang.getEmpty() : lang.getContent();
        if (pageCount > 1)
            content = content + "\n\n" + pageText;

        SimpleForm.Builder builder = SimpleForm.builder()
                .title(clean(gui.getTitle()))
                .content(clean(content));
        List<Runnable> responses = new ArrayList<>();

        for (int index = from; index < to; index++) {
            MenuEntry entry = entries.get(index);
            builder.button(entry.label);
            responses.add(() -> selectEntry(player, sender, entry, reopen, kind, page));
        }
        if (page > 0) {
            builder.button(clean(lang.getPrevious()));
            responses.add(() -> showPage(player, sender, gui, reopen, kind, page - 1));
        }
        if (page + 1 < pageCount) {
            builder.button(clean(lang.getNext()));
            responses.add(() -> showPage(player, sender, gui, reopen, kind, page + 1));
        }

        final int currentPage = page;
        sendForm(player, sender, builder, responses,
                () -> showPage(player, sender, gui, reopen, kind, currentPage));
    }

    private void selectEntry(Player player, BedrockFormSender sender, MenuEntry entry, Runnable reopen,
                             BedrockMenuKind kind, int page) {
        List<ClickOption> options = clickOptions(kind, entry);
        if (options.size() == 1) {
            dispatch(player, entry, options.get(0).clickType, reopen, shouldReopen(kind, entry));
            return;
        }

        LangFile.BedrockForms lang = Main.getLangFile().getBedrockForms();
        SimpleForm.Builder builder = SimpleForm.builder()
                .title(firstLine(entry.label))
                .content(clean(lang.getContent()));
        List<Runnable> responses = new ArrayList<>();
        for (ClickOption option : options) {
            builder.button(clean(option.label));
            responses.add(() -> dispatch(player, entry, option.clickType, reopen, true));
        }
        builder.button(clean(lang.getBack()));
        responses.add(() -> showPage(player, sender, entry.gui, reopen, kind, page));
        sendForm(player, sender, builder, responses,
                () -> selectEntry(player, sender, entry, reopen, kind, page));
    }

    private List<ClickOption> clickOptions(BedrockMenuKind kind, MenuEntry entry) {
        LangFile.BedrockForms lang = Main.getLangFile().getBedrockForms();
        List<ClickOption> result = new ArrayList<>();
        if (kind == BedrockMenuKind.STORAGE && entry.element.getSlotChar() == 'i') {
            result.add(new ClickOption(ClickType.LEFT, lang.getLeftClick()));
            result.add(new ClickOption(ClickType.RIGHT, lang.getRightClick()));
            result.add(new ClickOption(ClickType.SHIFT_RIGHT, lang.getShiftRightClick()));
            result.add(new ClickOption(ClickType.DROP, lang.getModuleDropClick()));
        } else if (kind == BedrockMenuKind.USERS && entry.element.getSlotChar() == 's') {
            result.add(new ClickOption(ClickType.LEFT, lang.getChangeRole()));
            result.add(new ClickOption(ClickType.SHIFT_RIGHT, lang.getRemove()));
        } else if (kind == BedrockMenuKind.MODULE) {
            result.add(new ClickOption(ClickType.LEFT, lang.getModuleLeftClick()));
            result.add(new ClickOption(ClickType.RIGHT, lang.getModuleRightClick()));
            result.add(new ClickOption(ClickType.SHIFT_RIGHT, lang.getModuleShiftRightClick()));
            result.add(new ClickOption(ClickType.DROP, lang.getModuleDropClick()));
        } else {
            result.add(new ClickOption(ClickType.LEFT, lang.getModuleLeftClick()));
        }
        return result;
    }

    private boolean shouldReopen(BedrockMenuKind kind, MenuEntry entry) {
        return !(kind == BedrockMenuKind.STANDARD && entry.element.getSlotChar() == 'b')
                && !(kind == BedrockMenuKind.USERS && entry.element.getSlotChar() == 'a');
    }

    private void dispatch(Player player, MenuEntry entry, ClickType clickType, Runnable reopen,
                          boolean autoReopen) {
        if (!player.isOnline() || !Main.getInstance().isEnabled())
            return;

        try {
            GuiElement.Action action = entry.element.getAction(player);
            if (action == null)
                return;

            InventoryClickEvent rawEvent = new InventoryClickEvent(
                    player.getOpenInventory(), InventoryType.SlotType.CONTAINER, entry.slot,
                    clickType, InventoryAction.NOTHING);
            rawEvent.setCancelled(true);
            GuiElement.Click click = new GuiElement.Click(entry.gui, entry.slot, clickType,
                    entry.item.clone(), entry.element, rawEvent);
            action.onClick(click);

            if (autoReopen && !sessions.containsKey(player.getUniqueId()) && player.isOnline()
                    && player.getOpenInventory().getType() == InventoryType.CRAFTING)
                reopen.run();
        } catch (RuntimeException | LinkageError exception) {
            Main.getInstance().getLogger().warning("Rejected Bedrock menu action for " + player.getName()
                    + ": " + exception.getMessage());
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getBedrockFormError());
        }
    }

    private void sendForm(Player player, BedrockFormSender sender, SimpleForm.Builder builder,
                          List<Runnable> responses, Runnable retry) {
        long timeout = bounded(Main.getConfigFile().getBedrockForms().getSessionTimeoutMs(), 1000L, 300000L);
        FormSession session = new FormSession(player.getUniqueId(), System.currentTimeMillis() + timeout, retry);
        builder.validResultHandler(response -> accept(player, session, response.clickedButtonId(), responses));
        builder.closedResultHandler(() -> consume(session));
        builder.invalidResultHandler(() -> invalid(player, session));

        sessions.put(player.getUniqueId(), session);
        try {
            if (!sender.send(player, builder.build())) {
                sessions.remove(player.getUniqueId(), session);
                ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getBedrockFormError());
                return;
            }
        } catch (RuntimeException | LinkageError exception) {
            sessions.remove(player.getUniqueId(), session);
            Main.getInstance().getLogger().warning("Could not send Bedrock form to " + player.getName()
                    + ": " + exception.getMessage());
            ChatUtils.sendMessage(player, Main.getLangFile().getMessages().getBedrockFormError());
            return;
        }

        long ticks = Math.max(1L, (timeout + 49L) / 50L);
        Main.getMorePaperLib().scheduling().entitySpecificScheduler(player).runDelayed(
                () -> sessions.remove(player.getUniqueId(), session), null, ticks);
    }

    private void accept(Player player, FormSession session, int responseId, List<Runnable> responses) {
        if (!consume(session))
            return;
        if (System.currentTimeMillis() > session.expiresAt) {
            notifyPlayer(player, Main.getLangFile().getMessages().getBedrockFormExpired());
            return;
        }
        if (responseId < 0 || responseId >= responses.size()) {
            notifyPlayer(player, Main.getLangFile().getMessages().getBedrockFormError());
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = bounded(Main.getConfigFile().getBedrockForms().getClickCooldownMs(), 0L, 5000L);
        Long previous = lastResponses.put(player.getUniqueId(), now);
        if (previous != null && now - previous < cooldown) {
            notifyPlayer(player, Main.getLangFile().getMessages().getBedrockFormCooldown());
            runForPlayer(player, session.retry, Math.max(1L, (cooldown + 49L) / 50L));
            return;
        }
        runForPlayer(player, responses.get(responseId), 0L);
    }

    private void invalid(Player player, FormSession session) {
        if (consume(session))
            notifyPlayer(player, Main.getLangFile().getMessages().getBedrockFormError());
    }

    private boolean consume(FormSession session) {
        return sessions.get(session.playerId) == session
                && session.consumed.compareAndSet(false, true)
                && sessions.remove(session.playerId, session);
    }

    private void notifyPlayer(Player player, String message) {
        runForPlayer(player, () -> ChatUtils.sendMessage(player, message), 0L);
    }

    private void runForPlayer(Player player, Runnable task, long delayTicks) {
        Runnable guardedTask = () -> {
            if (Main.getInstance().isEnabled() && player.isOnline())
                task.run();
        };
        if (delayTicks > 0L) {
            Main.getMorePaperLib().scheduling().entitySpecificScheduler(player)
                    .runDelayed(guardedTask, null, delayTicks);
        } else {
            Main.getMorePaperLib().scheduling().entitySpecificScheduler(player).run(guardedTask, null);
        }
    }

    private List<MenuEntry> collectEntries(Player player, InventoryGui gui, BedrockMenuKind kind) {
        List<MenuEntry> result = new ArrayList<>();
        Set<GuiElement> seen = Collections.newSetFromMap(new IdentityHashMap<GuiElement, Boolean>());
        for (GuiElement root : gui.getElements()) {
            if (root instanceof GuiPageElement)
                continue;
            int[] slots = root.getSlots();
            if (slots == null)
                continue;
            for (int slot : slots) {
                try {
                    GuiElement effective = root.getEffectiveElement(player, slot);
                    if (effective == null || effective instanceof GuiPageElement || !seen.add(effective))
                        continue;
                    if ((kind == BedrockMenuKind.STORAGE || kind == BedrockMenuKind.USERS)
                            && effective.getSlotChar() == 'h')
                        continue;
                    if (root instanceof GuiElementGroup
                            && effective == ((GuiElementGroup) root).getFiller())
                        continue;
                    if (effective == gui.getFiller())
                        continue;
                    ItemStack item = effective.getItem(player, slot);
                    if (item == null || item.getType() == Material.AIR)
                        continue;
                    result.add(new MenuEntry(gui, effective, slot, item.clone(), buttonLabel(item)));
                } catch (RuntimeException exception) {
                    Main.getInstance().getLogger().fine("Skipped invalid Bedrock menu element: "
                            + exception.getMessage());
                }
            }
        }
        return result;
    }

    private String buttonLabel(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
                ? clean(meta.getDisplayName())
                : humanize(item.getType().name());
        StringBuilder label = new StringBuilder(name);
        int maxLore = bounded(Main.getConfigFile().getBedrockForms().getMaxLoreLines(), 0, 10);
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            int used = 0;
            for (String line : meta.getLore()) {
                String cleaned = clean(line);
                if (cleaned.isEmpty())
                    continue;
                label.append('\n').append(cleaned);
                if (++used >= maxLore)
                    break;
            }
        }
        int maxLength = bounded(Main.getConfigFile().getBedrockForms().getMaxButtonLength(), 32, 512);
        return truncate(label.toString(), maxLength);
    }

    private String clean(String value) {
        if (value == null)
            return "";
        String stripped = ChatColor.stripColor(ChatUtils.color(value));
        return stripped == null ? "" : stripped.trim();
    }

    private String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private String humanize(String value) {
        String[] words = value.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty())
                continue;
            if (result.length() > 0)
                result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    private String replace(String value, String... replacements) {
        String result = value;
        for (int index = 0; index + 1 < replacements.length; index += 2)
            result = result.replace(replacements[index], replacements[index + 1]);
        return result;
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class MenuEntry {
        private final InventoryGui gui;
        private final GuiElement element;
        private final int slot;
        private final ItemStack item;
        private final String label;

        private MenuEntry(InventoryGui gui, GuiElement element, int slot, ItemStack item, String label) {
            this.gui = gui;
            this.element = element;
            this.slot = slot;
            this.item = item;
            this.label = label;
        }
    }

    private static final class ClickOption {
        private final ClickType clickType;
        private final String label;

        private ClickOption(ClickType clickType, String label) {
            this.clickType = clickType;
            this.label = label;
        }
    }

    private static final class FormSession {
        private final UUID playerId;
        private final long expiresAt;
        private final Runnable retry;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private FormSession(UUID playerId, long expiresAt, Runnable retry) {
            this.playerId = playerId;
            this.expiresAt = expiresAt;
            this.retry = retry;
        }
    }
}
