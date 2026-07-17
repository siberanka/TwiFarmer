package xyz.geik.farmer.helpers;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.compatibility.RuntimeCompatibility;
import xyz.geik.farmer.modules.FarmerModule;
import xyz.geik.farmer.modules.production.Production;
import xyz.geik.glib.GLib;
import xyz.geik.glib.api.ModuleDisableEvent;
import xyz.geik.glib.api.ModuleEnableEvent;
import xyz.geik.glib.chat.ChatUtils;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Registers, unregisters and manages the modules
 *
 * @author WaterArchery
 */
@Getter
public class ModuleHelper {

    private static final int MAX_MODULE_JARS = 128;
    private static final int MAX_CLASS_ENTRIES = 20000;
    private static final int MAX_FAILURE_LOGS = 20;

    private static ModuleHelper instance;
    private final List<FarmerModule> modules = new ArrayList<>();
    private final Set<URLClassLoader> externalClassLoaders = new HashSet<>();

    /**
     * @return the singleton instance of ModuleHelper
     */
    public synchronized static ModuleHelper getInstance() {
        if (instance == null) instance = new ModuleHelper();
        return instance;
    }

    /**
     * Loads all modules from the modules directory.
     */
    public void loadModules() {
        unloadModules();
        loadModule(new Production());

        try {
            File folder = new File(Main.getInstance().getDataFolder(), "/modules");
            // Mkdirs path
            loadFolderIfNotExists(folder);
            // files that we wanted to load
            File[] files = null;
            try {
                files = folder.listFiles(pathname -> pathname.getName().endsWith(".jar"));
            }
            catch (Exception e) {}
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                int moduleJarCount = Math.min(files.length, MAX_MODULE_JARS);
                if (files.length > MAX_MODULE_JARS)
                    Main.getInstance().getLogger().warning("Only the first " + MAX_MODULE_JARS
                            + " module jars will be scanned; found " + files.length + '.');
                int loggedFailures = 0;
                for (int fileIndex = 0; fileIndex < moduleJarCount; fileIndex++) {
                    File file = files[fileIndex];
                    if (!file.getName().endsWith(".jar")) continue;

                    ClassLoader parentLoader = ClassHelper.class.getClassLoader();
                    URLClassLoader moduleLoader = null;
                    boolean retainModuleLoader = false;
                    try (FileInputStream fileInputStream = new FileInputStream(file.getAbsoluteFile());
                         ZipInputStream zipInputStream = new ZipInputStream(fileInputStream)) {
                        moduleLoader = new URLClassLoader(new URL[]{file.toURI().toURL()}, parentLoader);
                        int classEntries = 0;
                        for (ZipEntry zipEntry = zipInputStream.getNextEntry(); zipEntry != null;
                             zipEntry = zipInputStream.getNextEntry()) {
                            if (++classEntries > MAX_CLASS_ENTRIES) {
                                Main.getInstance().getLogger().warning("Skipped oversized module jar " + file.getName());
                                break;
                            }
                            if (!zipEntry.isDirectory() && zipEntry.getName().endsWith(".class")) {
                                String entryName = zipEntry.getName();
                                String className = entryName.substring(0, entryName.length() - ".class".length())
                                        .replace('/', '.');

                                try {
                                    Class<?> loadedClass = moduleLoader.loadClass(className);
                                    Class<?> superClass = loadedClass.getSuperclass();
                                    if (superClass == null || !superClass.getName().endsWith("FarmerModule"))
                                        continue;
                                    FarmerModule module = (FarmerModule) loadedClass.getDeclaredConstructor().newInstance();
                                    loadModule(module);
                                    retainModuleLoader = true;
                                } catch (Exception | LinkageError failure) {
                                    if (loggedFailures++ < MAX_FAILURE_LOGS)
                                        Main.getInstance().getLogger().warning("Skipped incompatible module class "
                                                + className + " from " + file.getName() + ": "
                                                + RuntimeCompatibility.summarize(failure));
                                }
                            }
                        }
                    } catch (Exception | LinkageError failure) {
                        Main.getInstance().getLogger().warning("Could not scan module " + file.getName() + ": "
                                + RuntimeCompatibility.summarize(failure));
                    } finally {
                        if (moduleLoader != null) {
                            if (retainModuleLoader)
                                externalClassLoaders.add(moduleLoader);
                            else
                                closeModuleLoader(moduleLoader, file.getName());
                        }
                    }
                }
                if (loggedFailures > MAX_FAILURE_LOGS)
                    Main.getInstance().getLogger().warning("Suppressed " + (loggedFailures - MAX_FAILURE_LOGS)
                            + " additional incompatible module class errors.");
            }
        } catch (Exception | LinkageError failure) {
            Main.getInstance().getLogger().warning("External Farmer modules could not be scanned: "
                    + RuntimeCompatibility.summarize(failure));
        }
    }

    /**
     * Loads folder if not exists
     *
     * @param folder that we needed to mkdir
     */
    private static void loadFolderIfNotExists(File folder) {
        if (!folder.exists())
            folder.mkdirs();
    }

    /**
     * Loads a single module.
     *
     * @param module the module to load
     */
    public void loadModule(FarmerModule module) {
        module.setEnabled(true);
        boolean initialized = false;
        try {
            module.onEnable();
            initialized = true;
            modules.add(module);
            Main.getMorePaperLib().scheduling().globalRegionalScheduler().run(
                    () -> Bukkit.getPluginManager().callEvent(new ModuleEnableEvent(module)));
        } catch (RuntimeException | LinkageError failure) {
            modules.remove(module);
            module.setEnabled(false);
            if (initialized) {
                try {
                    module.onDisable();
                } catch (RuntimeException | LinkageError rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Unloads all currently loaded modules.
     */
    public void unloadModules() {
        for (FarmerModule module : new ArrayList<>(modules)) {
            try {
                if (!module.isEnabled())
                    continue;
                module.setEnabled(false);
                Bukkit.getPluginManager().callEvent(new ModuleDisableEvent(module));
                module.onDisable();
                String message = "&3[" + GLib.getInstance().getName() + "] &c" + module.getName() + " disabled.";
                ChatUtils.sendMessage(Bukkit.getConsoleSender(), message);
            } catch (RuntimeException | LinkageError failure) {
                Main.getInstance().getLogger().warning("Could not cleanly disable module " + module.getName() + ": "
                        + RuntimeCompatibility.summarize(failure));
            } finally {
                module.setEnabled(false);
                modules.remove(module);
            }
        }
        for (URLClassLoader classLoader : new ArrayList<>(externalClassLoaders))
            closeModuleLoader(classLoader, "external module");
        externalClassLoaders.clear();
    }

    private void closeModuleLoader(URLClassLoader classLoader, String source) {
        try {
            classLoader.close();
        } catch (IOException failure) {
            Main.getInstance().getLogger().warning("Could not close " + source + " class loader: "
                    + RuntimeCompatibility.summarize(failure));
        }
    }

    /**
     * Returns a module by its name.
     *
     * @param name the name of the module
     * @return the module with the specified name, or null if not found
     */
    public @Nullable FarmerModule getModule(String name) {
        for (FarmerModule module : modules) {
            if (module.getName().equalsIgnoreCase(name)) return module;
        }

        return null;
    }

    /**
     * Returns a module by its class type.
     *
     * @param classType the class type of the module
     * @param <T> the type of the module
     * @return the module with the specified class type, or null if not found
     */
    public @Nullable <T extends FarmerModule> FarmerModule getModule(Class<T> classType) {
        for (FarmerModule module : modules) {
            if (module.getClass() == classType) return module;
        }

        return null;
    }

}
