package xyz.geik.farmer.modules;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.FarmerConfigurationAPI;
import xyz.geik.farmer.helpers.ModuleHelper;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.shades.storage.Config;
import xyz.geik.glib.module.GModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;

/**
 * Module system of Farmer
 * You can extend a class with
 * this class and register it
 *
 * @author Geyik
 */
@Getter
@Setter
public abstract class FarmerModule extends GModule {

    private Config lang;
    private boolean isHasGui = false;
    @Getter@Setter
    private static boolean isModulesUseGui = false;
    @Getter@Setter
    private boolean defaultState = false;

    /**
     * Set default config of plugin
     * You may use it in your onLoad
     * or onEnable method of your module
     *
     * <p><b>IMPORTANT</b> file must be in
     * resources/lang folder, and it
     * must be named of lang as farmer.</p>
     *
     * @param langName name of lang
     */
    public void setLang(String langName, Class<?> targetClass) {
        validateLanguageName(langName);
        String fileName = langName + ".yml";
        installLanguage(fileName, getFileFromResourceAsStream(fileName, targetClass));
    }

    public void setLang(String langName, JavaPlugin plugin) {
        validateLanguageName(langName);
        String fileName = langName + ".yml";
        installLanguage(fileName, plugin.getResource(fileName));

    }

    private void validateLanguageName(String langName) {
        if (langName == null || !langName.matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Invalid module language name");
    }

    private void installLanguage(String fileName, InputStream defaults) {
        if (defaults == null)
            throw new IllegalArgumentException("Module language resource not found: " + fileName);

        byte[] defaultBytes = readDefaults(defaults);
        Path modulesDirectory = Main.getInstance().getDataFolder().toPath()
                .toAbsolutePath().normalize().resolve("modules");
        Path languageDirectory = modulesDirectory
                .resolve(getName().toLowerCase(java.util.Locale.ROOT)).resolve("lang").normalize();
        if (!languageDirectory.startsWith(modulesDirectory))
            throw new IllegalArgumentException("Unsafe module name: " + getName());

        File target = languageDirectory.resolve(fileName).toFile();
        FarmerConfigurationAPI.repairModuleFile(target, new ByteArrayInputStream(defaultBytes));
        lang = new Config(fileName, languageDirectory.toString(), new ByteArrayInputStream(defaultBytes));
    }

    private byte[] readDefaults(InputStream defaults) {
        try (InputStream input = defaults; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 8 * 1024 * 1024)
                    throw new IllegalArgumentException("Module language resource exceeds 8 MiB");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read module language defaults", exception);
        }
    }

    /**
     * Gets inputstream of file
     * @param fileName name of file
     * @param targetClass instance of target class
     * @return InputStream object
     */
    private InputStream getFileFromResourceAsStream(String fileName, Class<?> targetClass) {
        final String path = targetClass.getSimpleName().toLowerCase() + "/lang/" + fileName;
        ClassLoader classLoader = targetClass.getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(path);

        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + path);
        } else {
            return inputStream;
        }
    }

    /**
     * Save attributes to database
     *
     * @param con Connection to database
     * @param farmer Farmer to save
     * @throws SQLException if SQL error occurs
     */
    public static void databaseUpdateAttribute(@NotNull Connection con, @NotNull Farmer farmer) throws SQLException {
        final String SQL_QUERY = "UPDATE Farmers SET attributes = ? WHERE id = ?";
        try (PreparedStatement statement = con.prepareStatement(SQL_QUERY)) {
            statement.setString(1, attributeSerializer(farmer.getModuleAttributes()));
            statement.setInt(2, farmer.getId());
            statement.executeUpdate();
        }
    }

    /**
     * Get attributes from database
     *
     * @param con Connection to database
     * @param farmer Farmer to get attributes
     * @throws SQLException if SQL error occurs
     */
    public static void databaseGetAttributes(Connection con, @NotNull Farmer farmer) throws SQLException {
        final String SQL_QUERY = "SELECT attributes FROM Farmers WHERE id = ?";
        try (PreparedStatement statement = con.prepareStatement(SQL_QUERY)) {
            statement.setInt(1, farmer.getId());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next())
                farmer.setModuleAttributes(attributeDeserializer(resultSet.getString("attributes")));
            else farmer.setModuleAttributes(new HashMap<>());
        }
    }

    /**
     * Serialize attribute data to save in database
     *
     * @param attributes Attributes to serialize
     * @return Serialized attributes
     */
    private static @Nullable String attributeSerializer(@NotNull HashMap<String, Boolean> attributes) {
        if (attributes.isEmpty())
            return null;
        StringBuilder builder = new StringBuilder();
        for (String key : attributes.keySet())
            builder.append(key).append(":").append(attributes.get(key)).append(";");

        if (builder.toString().isEmpty())
            return null;
        else
            return builder.toString();
    }

    /**
     * Deserialize attribute data from database
     *
     * @param attributes Attributes to deserialize
     * @return Deserialized attributes
     */
    private static @NotNull HashMap<String, Boolean> attributeDeserializer(String attributes) {
        HashMap<String, Boolean> map = new HashMap<>();
        if (attributes == null || attributes.isEmpty())
            return map;
        for (String attribute : attributes.split(";"))
            if (!attribute.isEmpty())
                map.put(attribute.split(":")[0], Boolean.parseBoolean(attribute.split(":")[1]));
        return map;
    }

    /**
     * Checks if any module uses GUI and makes action
     * @see FarmerModule
     */
    public static void calculateModulesUseGui() {
        setModulesUseGui(ModuleHelper.getInstance().getModules().stream().anyMatch(FarmerModule::isHasGui));
    }
}
