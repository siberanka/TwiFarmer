package xyz.geik.farmer.pricing;

import org.junit.jupiter.api.Test;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingManagerTest {

    @Test
    void normalizesSupportedAliasesAndCustomProviderIds() {
        assertEquals("economyshopgui", PricingManager.normalizeId(" EconomyShopGUI-Premium "));
        assertEquals("essentials", PricingManager.normalizeId("EssentialsX"));
        assertEquals("custom_market", PricingManager.normalizeId("CUSTOM_MARKET"));
        assertEquals("", PricingManager.normalizeId(null));
    }

    @Test
    void legacyProvidersRetainStaticTotalPricing() {
        SellPriceProvider provider = new SellPriceProvider() {
            @Override
            public String getId() {
                return "legacy";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public java.util.OptionalDouble getUnitSellPrice(OfflinePlayer player, ItemStack item) {
                return java.util.OptionalDouble.of(2.5D);
            }
        };

        assertEquals(12.5D, provider.getSellPrice(null, null, 5).getAsDouble());
        assertFalse(provider.getSellPrice(null, null, 0).isPresent());
        assertTrue(provider.getSellPrice(null, null, 1).isPresent());
    }

    @Test
    void dynamicProvidersControlTheExactQuantityQuote() {
        SellPriceProvider provider = new SellPriceProvider() {
            @Override
            public String getId() {
                return "dynamic";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public java.util.OptionalDouble getUnitSellPrice(OfflinePlayer player, ItemStack item) {
                return java.util.OptionalDouble.of(1D);
            }

            @Override
            public java.util.OptionalDouble getSellPrice(OfflinePlayer player, ItemStack item, int amount) {
                return java.util.OptionalDouble.of(amount * amount);
            }
        };

        assertEquals(25D, provider.getSellPrice(null, null, 5).getAsDouble());
    }
}
