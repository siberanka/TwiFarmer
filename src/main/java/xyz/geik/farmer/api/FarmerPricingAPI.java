package xyz.geik.farmer.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.model.inventory.FarmerItem;
import xyz.geik.farmer.pricing.SellPriceProvider;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Public pricing extension point for Farmer modules and integrations.
 */
public final class FarmerPricingAPI {

    private FarmerPricingAPI() {}

    public static boolean registerProvider(SellPriceProvider provider) {
        return Main.getPricingManager().registerProvider(provider);
    }

    public static boolean unregisterProvider(SellPriceProvider provider) {
        return Main.getPricingManager().unregisterProvider(provider);
    }

    public static OptionalDouble getUnitSellPrice(OfflinePlayer player, FarmerItem item) {
        return Main.getPricingManager().getUnitSellPrice(player, item);
    }

    public static OptionalDouble getUnitSellPrice(OfflinePlayer player, ItemStack item, double manualPrice) {
        return Main.getPricingManager().getUnitSellPrice(player, item, manualPrice);
    }

    /**
     * Resolves the total sell price using the market's quantity-aware pricing
     * method when available.
     */
    public static OptionalDouble getSellPrice(OfflinePlayer player, FarmerItem item, long amount) {
        return Main.getPricingManager().getSellPrice(player, item, amount);
    }

    public static OptionalDouble getSellPrice(OfflinePlayer player, ItemStack item, long amount, double manualPrice) {
        return Main.getPricingManager().getSellPrice(player, item, amount, manualPrice);
    }

    public static String getActiveProviderId() {
        return Main.getPricingManager().getActiveProviderId();
    }

    public static List<String> getAvailableProviderIds() {
        return Main.getPricingManager().getAvailableProviderIds();
    }
}
