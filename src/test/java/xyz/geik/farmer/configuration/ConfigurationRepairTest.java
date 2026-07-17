package xyz.geik.farmer.configuration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationRepairTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void repairsMissingUnknownWrongTypeAndSemanticValues() throws Exception {
        YamlConfiguration schema = yaml(configSchema());
        YamlConfiguration current = yaml(
                "settings:\n"
                        + "  lang: ../../evil\n"
                        + "  farmer-price: -50\n"
                        + "  default-max-farmer-user: many\n"
                        + "  allowed-worlds: ['', world, world, 4]\n"
                        + "  obsolete: true\n"
                        + "production:\n  re-calculate: 0\n  items: []\n"
                        + "tax:\n  rate: 150\n"
                        + "pricing:\n"
                        + "  source: EconomyShopGUI-Premium\n"
                        + "  auto-priority: [UltimateShop, '', ultimateshop, EssentialsX, 3]\n"
                        + "bedrock-forms:\n"
                        + "  page-size: 100\n"
                        + "  session-timeout-ms: 10\n"
                        + "  click-cooldown-ms: -1\n"
                        + "  max-lore-lines: 50\n"
                        + "update-checker:\n  enable: invalid\n  check-interval-hours: 0\n"
                        + "database:\n  database-type: oracle\n  port: invalid\n"
                        + "gui:\n"
                        + "  farmer-layout: [bad]\n"
                        + "  manage-layout: ['         ']\n"
                        + "  buy-farmer-layout: ['         ']\n"
                        + "  users-layout: ['         ']\n"
                        + "  geyser-layout: ['         ']\n"
                        + "  module-layout: ['         ']\n");

        ConfigurationRepair.RepairResult result = ConfigurationRepair.repairDocument(
                current, schema, ConfigurationRepair.DocumentType.CONFIG,
                ConfigurationRepairTest.class.getClassLoader());

        assertTrue(result.changed());
        assertEquals("en", current.getString("settings.lang"));
        assertEquals(1000D, current.getDouble("settings.farmer-price"));
        assertEquals(3, current.getInt("settings.default-max-farmer-user"));
        assertEquals(Arrays.asList("world"), current.getStringList("settings.allowed-worlds"));
        assertFalse(current.contains("settings.obsolete"));
        assertEquals(20, current.getInt("bedrock-forms.page-size"));
        assertEquals(180, current.getInt("bedrock-forms.max-button-length"));
        assertEquals("economyshopgui", current.getString("pricing.source"));
        assertEquals(Arrays.asList("ultimateshop", "essentials"),
                current.getStringList("pricing.auto-priority"));
        assertTrue(current.getBoolean("update-checker.enable"));
        assertEquals(6, current.getInt("update-checker.check-interval-hours"));
        assertEquals("SQLite", current.getString("database.database-type"));
        assertEquals(schema.getStringList("gui.farmer-layout"),
                current.getStringList("gui.farmer-layout"));
    }

    @Test
    void preservesValidDynamicNamesAndRepairsLanguageEntries() throws Exception {
        YamlConfiguration schema = yaml(
                "messages:\n  prefix: '&3Farmer'\n"
                        + "  update-available: '{prefix} [{plugin}] {current} {latest} {url}'\n"
                        + "commands:\n"
                        + "  about: ['about']\n"
                        + "  info-header: ['info']\n"
                        + "gui:\n"
                        + "  farmer-gui:\n"
                        + "    items:\n"
                        + "      group-items:\n"
                        + "        name: '&e{material}'\n"
                        + "        names: {}\n");
        YamlConfiguration current = yaml(
                "messages:\n  prefix: '   '\n  update-available: '&eUpdate'\n  legacy: old\n"
                        + "commands:\n  about: []\n  info-header: invalid\n"
                        + "gui:\n"
                        + "  farmer-gui:\n"
                        + "    items:\n"
                        + "      group-items:\n"
                        + "        name: '&a{material}'\n"
                        + "        names:\n"
                        + "          WHEAT: '&eBugday'\n"
                        + "          BROKEN: ''\n"
                        + "          nested:\n"
                        + "            value: nope\n");

        ConfigurationRepair.RepairResult result = ConfigurationRepair.repairDocument(
                current, schema, ConfigurationRepair.DocumentType.LANGUAGE,
                ConfigurationRepairTest.class.getClassLoader());

        assertTrue(result.changed());
        assertEquals("&3Farmer", current.getString("messages.prefix"));
        assertTrue(current.getString("messages.update-available").contains("{plugin}"));
        assertTrue(current.getString("messages.update-available").contains("{url}"));
        assertFalse(current.contains("messages.legacy"));
        assertEquals(Arrays.asList("about"), current.getStringList("commands.about"));
        assertEquals("&eBugday", current.getString(
                "gui.farmer-gui.items.group-items.names.WHEAT"));
        assertFalse(current.contains("gui.farmer-gui.items.group-items.names.BROKEN"));
        assertFalse(current.contains("gui.farmer-gui.items.group-items.names.nested"));
    }

    @Test
    void backsUpMalformedYamlBeforeResettingToDefaults() throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        Files.write(target, "settings: [broken".getBytes(StandardCharsets.UTF_8));
        ConfigurationRepair repair = new ConfigurationRepair(
                temporaryDirectory.toFile(), Logger.getLogger("configuration-repair-test"));

        ConfigurationRepair.RepairResult result = repair.repair(
                ConfigFile.class, target.toFile(), ConfigurationRepair.DocumentType.CONFIG);

        assertTrue(result.isReset());
        assertNotNull(result.getBackup());
        assertTrue(Files.isRegularFile(result.getBackup()));
        assertEquals("settings: [broken", new String(
                Files.readAllBytes(result.getBackup()), StandardCharsets.UTF_8));
        YamlConfiguration repaired = new YamlConfiguration();
        repaired.load(target.toFile());
        assertEquals("en", repaired.getString("settings.lang"));
    }

    @Test
    void generatesCompleteSchemaAndBacksUpOrdinaryRepairs() throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        ConfigurationRepair repair = new ConfigurationRepair(
                temporaryDirectory.toFile(), Logger.getLogger("configuration-repair-schema-test"));

        ConfigurationRepair.RepairResult created = repair.repair(
                ConfigFile.class, target.toFile(), ConfigurationRepair.DocumentType.CONFIG);
        assertTrue(created.changed());
        assertTrue(Files.isRegularFile(target));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(target.toFile());
        assertTrue(configuration.contains("bedrock-forms.max-button-length"));
        assertEquals("auto", configuration.getString("pricing.source"));
        assertTrue(configuration.getStringList("pricing.auto-priority").contains("ultimateshop"));
        assertTrue(configuration.getBoolean("update-checker.enable"));
        assertEquals(6, configuration.getInt("update-checker.check-interval-hours"));
        assertTrue(configuration.contains("gui.farmer-layout"));
        configuration.set("settings.farmer-price", -1);
        configuration.set("bedrock-forms.max-button-length", null);
        configuration.set("obsolete-root", "remove me");
        configuration.save(target.toFile());

        ConfigurationRepair.RepairResult repaired = repair.repair(
                ConfigFile.class, target.toFile(), ConfigurationRepair.DocumentType.CONFIG);
        assertTrue(repaired.changed());
        assertNotNull(repaired.getBackup());
        assertTrue(Files.isRegularFile(repaired.getBackup()));

        YamlConfiguration verified = new YamlConfiguration();
        verified.load(target.toFile());
        assertEquals(1000D, verified.getDouble("settings.farmer-price"));
        assertEquals(180, verified.getInt("bedrock-forms.max-button-length"));
        assertFalse(verified.contains("obsolete-root"));
    }

    @Test
    void repairsModuleFilesFromBundledDefaults() throws Exception {
        Path target = temporaryDirectory.resolve("modules/test/lang/en.yml");
        Files.createDirectories(target.getParent());
        Files.write(target, ("enabled: invalid\n"
                + "messages:\n  obsolete: remove\n").getBytes(StandardCharsets.UTF_8));
        byte[] defaults = ("enabled: true\n"
                + "messages:\n  title: '&aModule'\n").getBytes(StandardCharsets.UTF_8);
        ConfigurationRepair repair = new ConfigurationRepair(
                temporaryDirectory.toFile(), Logger.getLogger("configuration-repair-module-test"));

        ConfigurationRepair.RepairResult result = repair.repair(
                target.toFile(), new ByteArrayInputStream(defaults),
                ConfigurationRepair.DocumentType.MODULE);

        assertTrue(result.changed());
        assertNotNull(result.getBackup());
        YamlConfiguration verified = new YamlConfiguration();
        verified.load(target.toFile());
        assertTrue(verified.getBoolean("enabled"));
        assertEquals("&aModule", verified.getString("messages.title"));
        assertFalse(verified.contains("messages.obsolete"));
    }

    @Test
    void generatesCompleteBuiltInLanguageSchemasIdempotently() throws Exception {
        ConfigurationRepair repair = new ConfigurationRepair(
                temporaryDirectory.toFile(), Logger.getLogger("configuration-repair-language-test"));
        Path english = temporaryDirectory.resolve("lang/en.yml");
        Path turkish = temporaryDirectory.resolve("lang/tr.yml");

        repair.repair(xyz.geik.farmer.configuration.lang.en.class, english.toFile(),
                ConfigurationRepair.DocumentType.LANGUAGE);
        repair.repair(xyz.geik.farmer.configuration.lang.tr.class, turkish.toFile(),
                ConfigurationRepair.DocumentType.LANGUAGE);

        YamlConfiguration en = new YamlConfiguration();
        en.load(english.toFile());
        YamlConfiguration tr = new YamlConfiguration();
        tr.load(turkish.toFile());
        assertFalse(en.getStringList("commands.about").isEmpty());
        assertNotNull(en.getString("bedrock-forms.change-role"));
        assertNotNull(en.getString("messages.bedrock-form-error"));
        assertNotNull(en.getString("messages.update-available"));
        assertNotNull(en.getString("messages.sell-price-unavailable"));
        assertNotNull(en.getString("messages.sell-payment-failed"));
        assertNotNull(en.getString("gui.farmer-gui.items.group-items.name"));
        assertFalse(tr.getStringList("commands.about").isEmpty());
        assertNotNull(tr.getString("bedrock-forms.change-role"));
        assertNotNull(tr.getString("messages.bedrock-form-error"));
        assertNotNull(tr.getString("messages.update-available"));
        assertNotNull(tr.getString("messages.sell-price-unavailable"));
        assertNotNull(tr.getString("messages.sell-payment-failed"));
        assertNotNull(tr.getString("gui.farmer-gui.items.group-items.name"));

        ConfigurationRepair.RepairResult secondPass = repair.repair(
                xyz.geik.farmer.configuration.lang.en.class, english.toFile(),
                ConfigurationRepair.DocumentType.LANGUAGE);
        assertFalse(secondPass.changed());
    }

    private static YamlConfiguration yaml(String value) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(value);
        return configuration;
    }

    private static String configSchema() {
        return "settings:\n"
                + "  lang: en\n"
                + "  farmer-price: 1000.0\n"
                + "  default-max-farmer-user: 3\n"
                + "  allowed-worlds: [world]\n"
                + "production:\n  re-calculate: 15\n  items: []\n"
                + "tax:\n  rate: 20.0\n"
                + "pricing:\n"
                + "  source: auto\n"
                + "  auto-priority: [ultimateshop, economyshopgui]\n"
                + "bedrock-forms:\n"
                + "  page-size: 20\n"
                + "  session-timeout-ms: 30000\n"
                + "  click-cooldown-ms: 250\n"
                + "  max-lore-lines: 4\n"
                + "  max-button-length: 180\n"
                + "update-checker:\n"
                + "  enable: true\n"
                + "  check-interval-hours: 6\n"
                + "  connect-timeout-seconds: 5\n"
                + "  request-timeout-seconds: 8\n"
                + "database:\n  database-type: SQLite\n  port: '3306'\n"
                + "gui:\n"
                + "  farmer-layout: ['    m    ']\n"
                + "  manage-layout: ['    m    ']\n"
                + "  buy-farmer-layout: ['    b    ']\n"
                + "  users-layout: ['    h    ']\n"
                + "  geyser-layout: ['    l    ']\n"
                + "  module-layout: ['    s    ']\n";
    }
}
