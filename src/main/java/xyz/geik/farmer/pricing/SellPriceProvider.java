package xyz.geik.farmer.pricing;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.OptionalDouble;

/**
 * Supplies sell prices for Farmer and external modules.
 */
public interface SellPriceProvider {

    /**
     * Stable lowercase provider identifier used in config.yml.
     *
     * @return provider identifier
     */
    String getId();

    /**
     * @return whether the backing pricing service is ready
     */
    boolean isAvailable();

    /**
     * Resolves the sell value of one item for the supplied player.
     *
     * @param player player whose permissions and multipliers should be used
     * @param item one item to price
     * @return a finite, non-negative unit price or an empty result
     */
    OptionalDouble getUnitSellPrice(OfflinePlayer player, ItemStack item);

    /**
     * Resolves the total sell value for an exact number of matching items.
     * Providers with dynamic pricing should override this method so quantity,
     * player multipliers, and market rules are evaluated by the market itself.
     *
     * @param player player whose permissions and multipliers should be used
     * @param item one representative item to price
     * @param amount number of items to sell
     * @return a finite, positive total price or an empty result
     */
    default OptionalDouble getSellPrice(OfflinePlayer player, ItemStack item, int amount) {
        if (amount <= 0)
            return OptionalDouble.empty();
        OptionalDouble unitPrice = getUnitSellPrice(player, item);
        if (!unitPrice.isPresent())
            return OptionalDouble.empty();
        double total = unitPrice.getAsDouble() * amount;
        return Double.isFinite(total) && total > 0D ? OptionalDouble.of(total) : OptionalDouble.empty();
    }
}
