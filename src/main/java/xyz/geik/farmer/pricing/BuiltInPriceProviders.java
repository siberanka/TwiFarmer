package xyz.geik.farmer.pricing;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection-backed adapters keep optional market APIs out of the release jar.
 */
final class BuiltInPriceProviders {

    private BuiltInPriceProviders() {}

    static List<SellPriceProvider> create(JavaPlugin plugin) {
        List<SellPriceProvider> result = new ArrayList<>();
        add(plugin, result, "ultimateshop", new String[]{"UltimateShop"}, UltimateShopProvider::new);
        add(plugin, result, "economyshopgui", new String[]{"EconomyShopGUI-Premium", "EconomyShopGUI"}, EconomyShopGuiProvider::new);
        add(plugin, result, "shopguiplus", new String[]{"ShopGUIPlus"}, ShopGuiPlusProvider::new);
        add(plugin, result, "excellentshop", new String[]{"ExcellentShop"}, ExcellentShopProvider::new);
        add(plugin, result, "zshop", new String[]{"zShop"}, ZShopProvider::new);
        add(plugin, result, "guishop", new String[]{"GUIShop"}, GuiShopProvider::new);
        add(plugin, result, "essentials", new String[]{"Essentials"}, EssentialsProvider::new);
        add(plugin, result, "cmi", new String[]{"CMI"}, CmiProvider::new);
        return result;
    }

    static boolean isBuiltIn(SellPriceProvider provider) {
        return provider instanceof ReflectiveProvider;
    }

    private static void add(JavaPlugin owner, List<SellPriceProvider> target, String id,
                            String[] pluginNames, ProviderFactory factory) {
        Plugin dependency = null;
        for (String pluginName : pluginNames) {
            dependency = Bukkit.getPluginManager().getPlugin(pluginName);
            if (dependency != null)
                break;
        }
        if (dependency == null)
            return;
        try {
            target.add(factory.create(id, owner, dependency));
        } catch (ReflectiveOperationException | LinkageError exception) {
            owner.getLogger().warning("Market plugin '" + dependency.getName()
                    + "' was found but its pricing API is incompatible: " + exception.getClass().getSimpleName());
        }
    }

    private interface ProviderFactory {
        SellPriceProvider create(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException;
    }

    private abstract static class ReflectiveProvider implements SellPriceProvider {
        private final String id;
        protected final JavaPlugin owner;
        protected final Plugin dependency;
        private final AtomicBoolean failureLogged = new AtomicBoolean();

        private ReflectiveProvider(String id, JavaPlugin owner, Plugin dependency) {
            this.id = id;
            this.owner = owner;
            this.dependency = dependency;
        }

        @Override
        public final String getId() {
            return id;
        }

        @Override
        public boolean isAvailable() {
            return dependency.isEnabled();
        }

        @Override
        public final OptionalDouble getUnitSellPrice(OfflinePlayer player, ItemStack item) {
            return getSellPrice(player, item, 1);
        }

        @Override
        public final OptionalDouble getSellPrice(OfflinePlayer player, ItemStack item, int amount) {
            if (!isAvailable() || player == null || item == null || item.getType().isAir() || amount <= 0)
                return OptionalDouble.empty();
            try {
                double value = query(player, item.clone(), amount);
                return Double.isFinite(value) && value > 0D ? OptionalDouble.of(value) : OptionalDouble.empty();
            } catch (Throwable throwable) {
                Throwable cause = throwable instanceof InvocationTargetException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                if (failureLogged.compareAndSet(false, true))
                    owner.getLogger().warning("Pricing API '" + id + "' failed and will return no price: "
                            + cause.getClass().getSimpleName() + ": " + safeMessage(cause));
                return OptionalDouble.empty();
            }
        }

        protected abstract double query(OfflinePlayer player, ItemStack item, int amount)
                throws ReflectiveOperationException;

        protected Class<?> apiClass(String name) throws ClassNotFoundException {
            return Class.forName(name, true, dependency.getClass().getClassLoader());
        }
    }

    private static final class UltimateShopProvider extends ReflectiveProvider {
        private final Method getVaultSellPrice;

        private UltimateShopProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            getVaultSellPrice = apiClass("cn.superiormc.ultimateshop.api.ShopHelper")
                    .getMethod("getVaultSellPrice", ItemStack[].class, Player.class, int.class);
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Player online = player.getPlayer();
            if (online == null)
                return -1D;
            return number(getVaultSellPrice.invoke(null, new ItemStack[]{withAmount(item, 1)}, online, amount));
        }
    }

    private static final class EconomyShopGuiProvider extends ReflectiveProvider {
        private final Method getSellPrice;
        private final Method economyType;

        private EconomyShopGuiProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            getSellPrice = apiClass("me.gypopo.economyshopgui.api.EconomyShopGUIHook")
                    .getMethod("getSellPrice", OfflinePlayer.class, ItemStack.class);
            Class<?> economyTypeClass = apiClass("me.gypopo.economyshopgui.util.EconomyType");
            economyType = economyTypeClass.getMethod("getFromString", String.class);
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Object result = getSellPrice.invoke(null, player, withAmount(item, amount));
            if (!(result instanceof Optional) || !((Optional<?>) result).isPresent())
                return -1D;
            Object sellPrice = ((Optional<?>) result).get();
            Object vaultType = economyType.invoke(null, "VAULT");
            if (vaultType == null)
                return -1D;
            Method priceMethod = Arrays.stream(sellPrice.getClass().getMethods())
                    .filter(method -> method.getName().equals("getPrice") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(vaultType))
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("SellPrice#getPrice(EcoType)"));
            return number(priceMethod.invoke(sellPrice, vaultType));
        }
    }

    private static final class ShopGuiPlusProvider extends ReflectiveProvider {
        private final Method getSellPrice;

        private ShopGuiPlusProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            getSellPrice = apiClass("net.brcdev.shopgui.ShopGuiPlusApi")
                    .getMethod("getItemStackPriceSell", Player.class, ItemStack.class);
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Player online = player.getPlayer();
            return online == null ? -1D : number(getSellPrice.invoke(null, online, withAmount(item, amount)));
        }
    }

    private static final class ExcellentShopProvider extends ReflectiveProvider {
        private final Method getVirtualShop;
        private final Method findProduct;
        private final Object sellType;

        @SuppressWarnings({"rawtypes", "unchecked"})
        private ExcellentShopProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            Class<?> api = apiClass("su.nightexpress.excellentshop.ShopAPI");
            getVirtualShop = api.getMethod("getVirtualShop");
            Class<?> tradeType = apiClass("su.nightexpress.excellentshop.api.product.TradeType");
            sellType = Enum.valueOf((Class<? extends Enum>) tradeType.asSubclass(Enum.class), "SELL");
            Object virtualShop = getVirtualShop.invoke(null);
            findProduct = virtualShop.getClass().getMethod("getBestProductFor", ItemStack.class, tradeType);
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Player online = player.getPlayer();
            if (online == null)
                return -1D;
            Object virtualShop = getVirtualShop.invoke(null);
            Object product = findProduct.invoke(virtualShop, item, sellType);
            if (product == null || !bool(product, "isSellable") || !bool(product, "canTrade", Player.class, online))
                return -1D;
            String currencyId = String.valueOf(invoke(product, "getCurrencyId")).toLowerCase(Locale.ROOT);
            if (!currencyId.contains("vault"))
                return -1D;
            int unitSize = Math.max(1, ((Number) invoke(product, "getUnitSize")).intValue());
            if (amount % unitSize != 0)
                return -1D;
            int units = amount / unitSize;
            try {
                return number(invoke(product, "getFinalSellPrice", Player.class, online, int.class, units));
            } catch (NoSuchMethodException ignored) {
                // Older ExcellentShop APIs have no quantity overload; use their static product price.
                return number(invoke(product, "getFinalSellPrice", Player.class, online)) * units;
            }
        }
    }

    private static final class ZShopProvider extends ReflectiveProvider {
        private final Class<?> managerClass;
        private final Method getItemButton;

        private ZShopProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            managerClass = apiClass("fr.maxlego08.zshop.api.ShopManager");
            getItemButton = managerClass.getMethod("getItemButton", Player.class, ItemStack.class);
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Player online = player.getPlayer();
            if (online == null)
                return -1D;
            RegisteredServiceProvider registration = Bukkit.getServicesManager().getRegistration((Class) managerClass);
            if (registration == null)
                return -1D;
            Object result = getItemButton.invoke(registration.getProvider(), online, item);
            if (!(result instanceof Optional) || !((Optional<?>) result).isPresent())
                return -1D;
            Object button = ((Optional<?>) result).get();
            if (!bool(button, "canSell"))
                return -1D;
            return number(invoke(button, "getSellPrice", Player.class, online, int.class, amount));
        }
    }

    private static final class GuiShopProvider extends ReflectiveProvider {
        private final Method getSellPrice;

        private GuiShopProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            getSellPrice = apiClass("com.pablo67340.guishop.api.GUIShopAPI")
                    .getMethod("getSellPrice", ItemStack.class, int.class);
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            return number(getSellPrice.invoke(null, item, amount));
        }
    }

    private static final class EssentialsProvider extends ReflectiveProvider {
        private final Method getWorth;

        private EssentialsProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            getWorth = dependency.getClass().getMethod("getWorth");
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Object worth = getWorth.invoke(dependency);
            if (worth == null)
                return -1D;
            Method getPrice = Arrays.stream(worth.getClass().getMethods())
                    .filter(method -> method.getName().equals("getPrice") && method.getParameterCount() == 2
                            && method.getParameterTypes()[1] == ItemStack.class)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("Worth#getPrice"));
            return number(getPrice.invoke(worth, dependency, withAmount(item, amount)));
        }
    }

    private static final class CmiProvider extends ReflectiveProvider {
        private final Method getInstance;
        private final Method getWorthManager;

        private CmiProvider(String id, JavaPlugin owner, Plugin dependency) throws ReflectiveOperationException {
            super(id, owner, dependency);
            Class<?> cmiClass = apiClass("com.Zrips.CMI.CMI");
            getInstance = cmiClass.getMethod("getInstance");
            getWorthManager = cmiClass.getMethod("getWorthManager");
        }

        @Override
        protected double query(OfflinePlayer player, ItemStack item, int amount) throws ReflectiveOperationException {
            Object cmi = getInstance.invoke(null);
            Object manager = cmi == null ? null : getWorthManager.invoke(cmi);
            if (manager == null)
                return -1D;
            ItemStack requested = withAmount(item, amount);
            Object worthItem = invoke(manager, "getWorth", ItemStack.class, requested);
            if (worthItem == null)
                return -1D;
            return number(invoke(worthItem, "getPlayerSellPrice", ItemStack.class, requested,
                    boolean.class, true, boolean.class, true));
        }
    }

    private static ItemStack withAmount(ItemStack item, int amount) {
        ItemStack requested = item.clone();
        requested.setAmount(amount);
        return requested;
    }

    private static Object invoke(Object target, String name, Object... typedArguments) throws ReflectiveOperationException {
        if (typedArguments.length % 2 != 0)
            throw new IllegalArgumentException("Typed arguments must be class/value pairs");
        Class<?>[] parameterTypes = new Class<?>[typedArguments.length / 2];
        Object[] values = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            parameterTypes[index] = (Class<?>) typedArguments[index * 2];
            values[index] = typedArguments[index * 2 + 1];
        }
        Method method = target.getClass().getMethod(name, parameterTypes);
        return method.invoke(target, values);
    }

    private static boolean bool(Object target, String name, Object... typedArguments) throws ReflectiveOperationException {
        Object value = invoke(target, name, typedArguments);
        return value instanceof Boolean && (Boolean) value;
    }

    private static double number(Object value) {
        if (value instanceof BigDecimal)
            return ((BigDecimal) value).doubleValue();
        return value instanceof Number ? ((Number) value).doubleValue() : -1D;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty())
            return "no details";
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
