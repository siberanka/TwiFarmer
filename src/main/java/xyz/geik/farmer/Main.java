package xyz.geik.farmer;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import space.arim.morepaperlib.MorePaperLib;
import xyz.geik.farmer.api.FarmerAPI;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.commands.FarmerCommand;
import xyz.geik.farmer.compatibility.RuntimeCompatibility;
import xyz.geik.farmer.configuration.ConfigFile;
import xyz.geik.farmer.configuration.ConfigurationRepair;
import xyz.geik.farmer.configuration.LangFile;
import xyz.geik.farmer.database.MySQL;
import xyz.geik.farmer.database.SQL;
import xyz.geik.farmer.database.SQLite;
import xyz.geik.farmer.helpers.*;
import xyz.geik.farmer.integrations.Integrations;
import xyz.geik.farmer.integrations.bedrock.BedrockMenus;
import xyz.geik.farmer.listeners.ListenerRegister;
import xyz.geik.farmer.listeners.backend.ChatEvent;
import xyz.geik.farmer.modules.FarmerModule;
import xyz.geik.farmer.pricing.PricingManager;
import xyz.geik.farmer.update.UpdateChecker;
import xyz.geik.farmer.shades.storage.Config;
import xyz.geik.glib.GLib;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.database.Database;
import xyz.geik.glib.database.DatabaseType;
import xyz.geik.glib.economy.Economy;
import xyz.geik.glib.economy.EconomyAPI;
import xyz.geik.glib.module.ModuleManager;
import xyz.geik.glib.shades.okaeri.configs.ConfigManager;
import xyz.geik.glib.shades.okaeri.configs.OkaeriConfig;
import xyz.geik.glib.shades.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import xyz.geik.glib.shades.triumphteam.cmd.bukkit.BukkitCommandManager;
import xyz.geik.glib.shades.triumphteam.cmd.bukkit.message.BukkitMessageKey;
import xyz.geik.glib.shades.triumphteam.cmd.core.message.MessageKey;
import xyz.geik.glib.simplixstorage.SimplixStorageAPI;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Main class of farmer
 * There is only loads, apis and
 * startup task codes.
 */
@Getter
public class Main extends JavaPlugin {

    /**
     * Listener list of modules
     */
    public Map<FarmerModule, Listener> listenerList = new HashMap<>();

    @Getter @Setter
    private SimplixStorageAPI simplixStorageAPI;

    @Getter
    private ModuleManager moduleManager;

    @Getter @Setter
    private static Database database;

    @Getter
    private static SQL sql = null;

    @Getter @Setter
    private static Economy economy;

    /**
     * Instance of this class
     */
    @Getter
    private static Main instance;

    @Getter
    private static MorePaperLib morePaperLib;

    /**
     * Config files which using SimplixStorage API for it.
     * Also, you can find usage code of API on helpers#StorageAPI
     */
    @Getter
    private static Config itemsFile, levelFile;

    private static volatile ConfigurationSnapshot configurationSnapshot;

    private ConfigurationRepair configurationRepair;

    private UpdateChecker updateChecker;

    @Getter
    private static PricingManager pricingManager;

    /**
     * Main integration of plugin integrations#Integrations
     */
    @Getter
    @Setter
    private static Integrations integration;

    /**
     * CommandManager
     */
    @Getter
    private static BukkitCommandManager<CommandSender> commandManager;

    private boolean paperFamilyServer;
    private boolean farmersLoaded;
    private boolean enableCompleted;

    /**
     * Loading files before enable
     */
    @Override
    public void onLoad() {
        instance = this;
        if (!isPaperFamilyServer()) {
            getLogger().severe("Farmer requires a Paper-family server (Paper, Leaf, or Folia).");
            getLogger().severe("Plain Bukkit and Spigot servers are intentionally unsupported.");
            return;
        }

        paperFamilyServer = true;
        simplixStorageAPI = new SimplixStorageAPI(this);
        setupFiles();
        setupDatabase();
    }

    /**
     * onEnable method called by the server runtime.
     * This is sort of the main(String... args) method.
     */
    @Override
    public void onEnable() {
        if (!paperFamilyServer) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!RuntimeCompatibility.verify(this)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            morePaperLib = new MorePaperLib(this);
            FarmerAPI.getFarmerManager();
            Integrations.registerIntegrations();
            new GLib(this, getLangFile().getMessages().getPrefix());
            pricingManager = new PricingManager(this);
            PlaceholderHelper.initialize();
            CacheLoader.loadAllItems();
            CacheLoader.loadAllLevels();
            registerEconomy();
            setupCommands();
            sendEnableMessage();
            restartUpdateChecker();
            getSql().loadAllFarmers();
            farmersLoaded = true;
            new ListenerRegister();
            BedrockMenus.initialize();
            loadMetrics();
            registerModules();
            enableCompleted = true;
        } catch (RuntimeException | LinkageError failure) {
            getLogger().severe("Farmer startup aborted: " + RuntimeCompatibility.summarize(failure));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * disable method called during server shutdown.
     * executing it right before close.
     * async tasks can be fail because server
     * can't handle async tasks while shutting down
     */
    @Override
    public void onDisable() {
        if (!paperFamilyServer)
            return;

        if (!enableCompleted)
            getLogger().warning("Cleaning up a partially initialized Farmer startup.");
        safeShutdown("update checker", this::stopUpdateChecker);
        safeShutdown("pending chat state", ChatEvent::clearPendingPlayers);
        safeShutdown("Bedrock forms", BedrockMenus::shutdown);
        if (farmersLoaded && getSql() != null)
            safeShutdown("farmer data save", () -> getSql().updateAllFarmers());
        if (getCommandManager() != null)
            safeShutdown("commands", CommandHelper::unregisterCommands);
        if (getModuleManager() != null)
            safeShutdown("modules", () -> ModuleHelper.getInstance().unloadModules());
        farmersLoaded = false;
        enableCompleted = false;
        pricingManager = null;
        commandManager = null;
        economy = null;
        integration = null;
        morePaperLib = null;
    }

    private void safeShutdown(String component, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            getLogger().warning("Could not cleanly stop " + component + ": "
                    + RuntimeCompatibility.summarize(failure));
        }
    }

    private boolean isPaperFamilyServer() {
        return hasPaperApi();
    }

    private boolean hasPaperApi() {
        try {
            Class.forName("io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager", false, getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Setups config, lang and modules file file
     */
    public void setupFiles() {
        try {
            configurationRepair = new ConfigurationRepair(getDataFolder(), getLogger());
            reloadConfigurationFiles();
            itemsFile = getSimplixStorageAPI().initConfig("items");
            levelFile = getSimplixStorageAPI().initConfig("levels");
            WorldHelper.loadAllowedWorlds();
        } catch (Exception exception) {
            getServer().getPluginManager().disablePlugin(this);
            throw new RuntimeException("Error loading configuration file", exception);
        }
    }

    /**
     * Repairs and reloads config.yml and the selected language as one publication step.
     */
    public synchronized void reloadConfigurationFiles() {
        if (configurationRepair == null)
            configurationRepair = new ConfigurationRepair(getDataFolder(), getLogger());

        File configTarget = new File(getDataFolder(), "config.yml");
        configurationRepair.repair(ConfigFile.class, configTarget,
                ConfigurationRepair.DocumentType.CONFIG);
        ConfigFile loadedConfig = loadOkaeriConfig(ConfigFile.class, configTarget);

        String langName = loadedConfig.getSettings().getLang();
        try {
            Set<String> languages = new LinkedHashSet<>(Arrays.asList("en", "tr", langName));
            LangFile loadedLanguage = null;
            for (String language : languages) {
                Class<? extends LangFile> languageClass = Class
                        .forName("xyz.geik.farmer.configuration.lang." + language)
                        .asSubclass(LangFile.class);
                File languageTarget = new File(new File(getDataFolder(), "lang"), language + ".yml");
                configurationRepair.repair(languageClass, languageTarget,
                        ConfigurationRepair.DocumentType.LANGUAGE);
                if (language.equals(langName))
                    loadedLanguage = loadOkaeriConfig(languageClass, languageTarget);
            }
            if (loadedLanguage == null)
                throw new IllegalStateException("Selected language was not loaded: " + langName);

            configurationSnapshot = new ConfigurationSnapshot(loadedConfig, loadedLanguage);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Validated language class is unavailable: " + langName, exception);
        }
    }

    private <T extends OkaeriConfig> T loadOkaeriConfig(Class<T> type, File target) {
        return ConfigManager.create(type, config -> {
            config.withConfigurer(new YamlBukkitConfigurer());
            config.withBindFile(target);
            config.saveDefaults();
            config.load(true);
        });
    }

    public static ConfigFile getConfigFile() {
        ConfigurationSnapshot snapshot = configurationSnapshot;
        if (snapshot == null)
            throw new IllegalStateException("Configuration has not been loaded yet");
        return snapshot.configFile;
    }

    public static LangFile getLangFile() {
        ConfigurationSnapshot snapshot = configurationSnapshot;
        if (snapshot == null)
            throw new IllegalStateException("Language has not been loaded yet");
        return snapshot.langFile;
    }

    public ConfigurationRepair getConfigurationRepair() {
        if (configurationRepair == null)
            throw new IllegalStateException("Configuration repair service has not been initialized");
        return configurationRepair;
    }

    public void restartUpdateChecker() {
        stopUpdateChecker();
        updateChecker = new UpdateChecker(this, getConfigFile().getUpdateChecker());
        updateChecker.start();
    }

    private void stopUpdateChecker() {
        if (updateChecker != null) {
            updateChecker.stop();
            updateChecker = null;
        }
    }

    private static final class ConfigurationSnapshot {
        private final ConfigFile configFile;
        private final LangFile langFile;

        private ConfigurationSnapshot(ConfigFile configFile, LangFile langFile) {
            this.configFile = configFile;
            this.langFile = langFile;
        }
    }

    /**
     * Setups database
     */
    private void setupDatabase() {
        DatabaseType type = DatabaseType.getDatabaseType(getConfigFile().getDatabase().getDatabaseType());
        if (type.equals(DatabaseType.SQLite))
            this.sql = new SQLite();
        else
            this.sql = new MySQL();
    }

    /**
     * Registers economy
     */
    private void registerEconomy() {
        Main.economy = new EconomyAPI(this, getConfigFile().getSettings().getEconomy()).getEconomy();
    }

    /**
     * Register modules to this plugin
     */
    private void registerModules() {
        this.moduleManager = new ModuleManager();
        //getModuleManager().enableModules();
        //FarmerModule.calculateModulesUseGui();

        ModuleHelper helper = ModuleHelper.getInstance();
        helper.loadModules();
        FarmerModule.calculateModulesUseGui();
    }

    /**
     * Sends enable message to console.
     */
    private static void sendEnableMessage() {
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color("&6&l		FARMER 		&b"));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color("&aDeveloped by &2Geik"));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color("&aContributors &2" + Arrays.toString(Main.getInstance().getDescription().getAuthors().toArray())));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color("&aDiscord: &2https://discord.geik.xyz"));
        Bukkit.getConsoleSender().sendMessage(ChatUtils.color("&aWeb: &2https://geik.xyz"));
    }

    /**
     * Custom charted metrics loader
     */
    private void loadMetrics() {
        Metrics metrics = new Metrics(Main.instance, 9646);
        metrics.addCustomChart(new Metrics.SingleLineChart("ciftci_sayisi", () -> FarmerManager.getFarmers().size()));
        metrics.addCustomChart(new Metrics.SimplePie("api_eklentisi", () -> {
            Integrations activeIntegration = getIntegration();
            if (activeIntegration == null)
                return "none";

            String integrationName = activeIntegration.getClass().getSimpleName();
            return integrationName.isEmpty() ? "unknown" : integrationName;
        }));
    }

    /**
     * Setups commands
     */
    private void setupCommands() {
        commandManager = BukkitCommandManager.create(this);
        commandManager.registerCommand(new FarmerCommand());
        commandManager.registerMessage(MessageKey.INVALID_ARGUMENT, (sender, invalidArgumentContext) ->
                ChatUtils.sendMessage(sender, getLangFile().getMessages().getInvalidArgument()));
        commandManager.registerMessage(MessageKey.UNKNOWN_COMMAND, (sender, invalidArgumentContext) ->
                ChatUtils.sendMessage(sender, getLangFile().getMessages().getUnknownCommand()));
        commandManager.registerMessage(MessageKey.NOT_ENOUGH_ARGUMENTS, (sender, invalidArgumentContext) ->
                ChatUtils.sendMessage(sender, getLangFile().getMessages().getNotEnoughArguments()));
        commandManager.registerMessage(MessageKey.TOO_MANY_ARGUMENTS, (sender, invalidArgumentContext) ->
                ChatUtils.sendMessage(sender, getLangFile().getMessages().getTooManyArguments()));
        commandManager.registerMessage(BukkitMessageKey.NO_PERMISSION, (sender, invalidArgumentContext) ->
                ChatUtils.sendMessage(sender, getLangFile().getMessages().getNoPerm()));
    }

    /**
     * Constructor of class
     */
    public Main() {}
}
