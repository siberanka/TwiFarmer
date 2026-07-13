package xyz.geik.farmer.update;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    @Test
    void permitsOnlyOperatorsAndFarmerAdmins() {
        assertTrue(UpdateChecker.canNotify(player(true, false)));
        assertTrue(UpdateChecker.canNotify(player(false, true)));
        assertFalse(UpdateChecker.canNotify(player(false, false)));
    }

    @Test
    void fillsEveryMessagePlaceholderIncludingPluginName() {
        assertEquals("Farmer|v6-b116|v6-b117|https://github.com/release|prefix",
                UpdateChecker.formatMessage("{plugin}|{current}|{latest}|{url}|{prefix}", "prefix",
                        "Farmer", "v6-b116", "v6-b117", "https://github.com/release"));
    }

    private static Player player(boolean operator, boolean admin) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if ("isOp".equals(method.getName())) return operator;
                    if ("hasPermission".equals(method.getName())) return admin;
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }
}
