package xyz.geik.farmer.listeners.backend;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.handlers.FarmerItemSellEvent;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.model.inventory.FarmerItem;
import xyz.geik.farmer.pricing.PricingManager;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.chat.Placeholder;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ItemSellEvent listener class
 *
 * @author poyraz
 * @since 1.0.0
 */
public class ItemSellEvent implements Listener {

    private static final AtomicBoolean INVALID_TAX_LOGGED = new AtomicBoolean();

    /**
     * Constructor of class
     */
    public ItemSellEvent() {}

    /**
     * Sell item event
     *
     * @param event of event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void sellItemEvent(@NotNull FarmerItemSellEvent event) {
        FarmerItem slotItem = event.getFarmerItem();
        Farmer farmer = event.getFarmer();
        double taxRate = farmer.getLevel().getTax();
        if (!Double.isFinite(taxRate) || taxRate < 0D || taxRate > 100D) {
            if (INVALID_TAX_LOGGED.compareAndSet(false, true))
                Main.getInstance().getLogger().severe(
                        "Farmer sale rejected because a configured level tax is outside 0-100.");
            sendIfOnline(event, Main.getLangFile().getMessages().getSellPaymentFailed());
            return;
        }
        long soldAmount;
        double sellPrice = 0D;
        boolean priceUnavailable = false;
        synchronized (slotItem) {
            soldAmount = slotItem.getAmount();
            if (soldAmount <= 0)
                return;
            OptionalDouble totalPrice = Main.getPricingManager()
                    .getSellPrice(event.getOfflinePlayer(), slotItem, soldAmount);
            if (!totalPrice.isPresent() || totalPrice.getAsDouble() > PricingManager.MAX_SELL_PRICE) {
                priceUnavailable = true;
            } else {
                sellPrice = totalPrice.getAsDouble();
                // Claim the stock before touching the economy so concurrent triggers cannot double-sell it.
                slotItem.setAmount(0);
            }
        }
        if (priceUnavailable) {
            sendIfOnline(event, Main.getLangFile().getMessages().getSellPriceUnavailable(),
                    new Placeholder("{source}", Main.getPricingManager().getActiveProviderId()));
            return;
        }

        // Calculating tax and final profit.
        double profit = (taxRate > 0)
                ? sellPrice-(sellPrice*taxRate/100)
                : sellPrice;
        double tax = (sellPrice == profit) ? 0 : sellPrice*taxRate/100;
        try {
            Main.getEconomy().depositPlayer(event.getOfflinePlayer(), profit);
        } catch (RuntimeException | LinkageError exception) {
            synchronized (slotItem) {
                slotItem.sumAmount(soldAmount);
            }
            Main.getInstance().getLogger().severe("Farmer sale payout failed: "
                    + exception.getClass().getSimpleName());
            sendIfOnline(event, Main.getLangFile().getMessages().getSellPaymentFailed());
            return;
        }

        if (Main.getConfigFile().getTax().isDeposit() && tax > 0D) {
            try {
                Main.getEconomy().depositPlayer(
                        Bukkit.getOfflinePlayer(Main.getConfigFile().getTax().getDepositUser()), tax);
            } catch (RuntimeException | LinkageError exception) {
                // The seller is already paid. Restoring stock here would duplicate value.
                Main.getInstance().getLogger().severe("Farmer tax deposit failed after seller payout: "
                        + exception.getClass().getSimpleName());
            }
        }
        sendIfOnline(event, Main.getLangFile().getMessages().getSellComplate(),
                new Placeholder("{money}", roundDouble(profit)),
                new Placeholder("{tax}", roundDouble(tax)));
    }

    private static void sendIfOnline(FarmerItemSellEvent event, String message, Placeholder... placeholders) {
        if (event.getOfflinePlayer().isOnline() && event.getOfflinePlayer().getPlayer() != null)
            ChatUtils.sendMessage(event.getOfflinePlayer().getPlayer(), message, placeholders);
    }

    /**
     * Rounds double for display good.
     * This method makes doubles round like #.##
     * instead of #.######.
     *
     * @param value of double
     * @return rounded string double
     */
    private static @NotNull String roundDouble(double value) {
        long factor = (long) Math.pow(10, 2);
        value = value * factor;
        long tmp = Math.round(value);
        double result = (double) tmp / factor;
        return String.valueOf( result );
    }
}
