package xyz.geik.farmer.api;

import xyz.geik.farmer.Main;
import xyz.geik.farmer.configuration.ConfigurationRepair;

import java.io.File;
import java.io.InputStream;

/**
 * Allows Farmer modules to apply the same backup-first YAML repair lifecycle.
 */
public final class FarmerConfigurationAPI {
    private FarmerConfigurationAPI() {
    }

    public static ConfigurationRepair.RepairResult repairModuleFile(
            File target, InputStream bundledDefaults) {
        return Main.getInstance().getConfigurationRepair().repair(
                target, bundledDefaults, ConfigurationRepair.DocumentType.MODULE);
    }
}
