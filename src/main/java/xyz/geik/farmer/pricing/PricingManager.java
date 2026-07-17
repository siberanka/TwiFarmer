package xyz.geik.farmer.pricing;

import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.configuration.ConfigFile;
import xyz.geik.farmer.model.inventory.FarmerItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Selects and safely invokes the configured market pricing provider.
 */
public final class PricingManager {

    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    /** Upper bound shared by price resolution and the economy settlement path. */
    public static final double MAX_SELL_PRICE = 1.0E15D;

    private final JavaPlugin plugin;
    private final List<SellPriceProvider> providers = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> failureLogs = ConcurrentHashMap.newKeySet();
    private volatile Selection selection = new Selection("manual", null);

    public PricingManager(JavaPlugin plugin) {
        this.plugin = plugin;
        providers.addAll(BuiltInPriceProviders.create(plugin));
        reload();
    }

    /**
     * Re-evaluates provider availability and the configured source.
     */
    public synchronized void reload() {
        failureLogs.clear();
        ConfigFile.Pricing pricing = Main.getConfigFile().getPricing();
        String source = normalizeId(pricing.getSource());
        if (source.isEmpty())
            source = "auto";

        Map<String, SellPriceProvider> available = availableProviders();
        if ("manual".equals(source)) {
            publish(new Selection("manual", null));
            return;
        }

        if ("auto".equals(source)) {
            for (String configuredId : pricing.getAutoPriority()) {
                SellPriceProvider provider = available.get(normalizeId(configuredId));
                if (provider != null) {
                    publish(new Selection(provider.getId(), provider));
                    return;
                }
            }
            if (!available.isEmpty()) {
                SellPriceProvider provider = available.values().iterator().next();
                publish(new Selection(provider.getId(), provider));
                return;
            }
            publish(new Selection("manual", null));
            plugin.getLogger().info("No supported market plugin was found; pricing source auto selected manual items.yml prices.");
            return;
        }

        SellPriceProvider provider = available.get(source);
        if (provider == null) {
            selection = new Selection(source, null);
            plugin.getLogger().warning("Configured pricing provider '" + source
                    + "' is unavailable. Selling is disabled until the provider is available or pricing.source is changed.");
            return;
        }
        publish(new Selection(provider.getId(), provider));
    }

    /**
     * Adds a provider supplied by a Farmer module or another plugin.
     *
     * @param provider provider to register
     * @return false if its id is invalid or already registered
     */
    public synchronized boolean registerProvider(SellPriceProvider provider) {
        if (provider == null)
            return false;
        String id = normalizeId(provider.getId());
        if (!PROVIDER_ID.matcher(id).matches() || providers.stream()
                .anyMatch(current -> normalizeId(current.getId()).equals(id)))
            return false;
        providers.add(provider);
        reload();
        return true;
    }

    /**
     * Removes a custom provider. Built-in providers cannot be removed.
     *
     * @param provider provider instance
     * @return whether it was removed
     */
    public synchronized boolean unregisterProvider(SellPriceProvider provider) {
        if (provider == null || BuiltInPriceProviders.isBuiltIn(provider))
            return false;
        boolean removed = providers.remove(provider);
        if (removed)
            reload();
        return removed;
    }

    public OptionalDouble getUnitSellPrice(OfflinePlayer player, FarmerItem item) {
        return getSellPrice(player, item, 1L);
    }

    public OptionalDouble getUnitSellPrice(OfflinePlayer player, ItemStack item, double manualPrice) {
        return getSellPrice(player, item, 1L, manualPrice);
    }

    /**
     * Resolves the total sale price for a stocked Farmer item.
     * External providers receive the actual requested quantity, not a unit
     * price multiplied by Farmer afterwards.
     */
    public OptionalDouble getSellPrice(OfflinePlayer player, FarmerItem item, long amount) {
        if (item == null)
            return OptionalDouble.empty();
        return getSellPrice(player, item.getMaterial().parseItem(), amount, item.getPrice());
    }

    /**
     * Resolves the total sale price for a representative Bukkit item.
     * Manual pricing is the only mode that Farmer multiplies itself; market
     * adapters own the calculation so their dynamic rules remain authoritative.
     */
    public OptionalDouble getSellPrice(OfflinePlayer player, ItemStack item, long amount, double manualPrice) {
        if (amount <= 0L)
            return OptionalDouble.empty();
        Selection current = selection;
        if ("manual".equals(current.id)) {
            OptionalDouble unitPrice = validPrice(manualPrice);
            return unitPrice.isPresent()
                    ? validPrice(unitPrice.getAsDouble() * amount)
                    : OptionalDouble.empty();
        }
        if (current.provider == null || player == null || item == null || item.getType().isAir())
            return OptionalDouble.empty();
        // Bukkit ItemStack and the supported market APIs expose quantities as int.
        // Splitting a quote would change tiered or volume-dependent market prices.
        if (amount > Integer.MAX_VALUE)
            return OptionalDouble.empty();

        ItemStack single = item.clone();
        single.setAmount(1);
        try {
            OptionalDouble result = current.provider.getSellPrice(player, single, (int) amount);
            return result.isPresent() ? validPrice(result.getAsDouble()) : OptionalDouble.empty();
        } catch (RuntimeException | LinkageError exception) {
            if (failureLogs.add(current.id))
                plugin.getLogger().warning("Pricing provider '" + current.id + "' failed: "
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            return OptionalDouble.empty();
        }
    }

    public String getActiveProviderId() {
        return selection.id;
    }

    public List<String> getAvailableProviderIds() {
        List<String> result = new ArrayList<>();
        result.add("manual");
        result.addAll(availableProviders().keySet());
        return result;
    }

    private Map<String, SellPriceProvider> availableProviders() {
        Map<String, SellPriceProvider> result = new LinkedHashMap<>();
        for (SellPriceProvider provider : providers) {
            String id = normalizeId(provider.getId());
            if (!PROVIDER_ID.matcher(id).matches())
                continue;
            try {
                if (provider.isAvailable())
                    result.putIfAbsent(id, provider);
            } catch (RuntimeException | LinkageError ignored) {
                // A broken optional integration must not prevent Farmer from enabling.
            }
        }
        return result;
    }

    private void publish(Selection next) {
        Selection previous = selection;
        selection = next;
        if (!next.id.equals(previous.id))
            plugin.getLogger().info("Pricing source selected: " + next.id);
    }

    private static OptionalDouble validPrice(double value) {
        return Double.isFinite(value) && value > 0D && value <= MAX_SELL_PRICE
                ? OptionalDouble.of(value)
                : OptionalDouble.empty();
    }

    public static String normalizeId(String value) {
        if (value == null)
            return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("economyshopgui-premium".equals(normalized))
            return "economyshopgui";
        if ("essentialsx".equals(normalized))
            return "essentials";
        return normalized;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty())
            return "no details";
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private static final class Selection {
        private final String id;
        private final SellPriceProvider provider;

        private Selection(String id, SellPriceProvider provider) {
            this.id = id;
            this.provider = provider;
        }
    }
}
