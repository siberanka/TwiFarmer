package xyz.geik.farmer.configuration;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.geik.glib.shades.okaeri.configs.ConfigManager;
import xyz.geik.glib.shades.okaeri.configs.OkaeriConfig;
import xyz.geik.glib.shades.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Repairs core configuration files against the schema produced by their config classes.
 */
public final class ConfigurationRepair {
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_BACKUPS = 20;
    private static final int MAX_LIST_ENTRIES = 1024;
    private static final int MAX_STRING_LENGTH = 32768;
    private static final int MAX_DYNAMIC_NAMES = 2048;
    private static final Pattern LANGUAGE_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern DYNAMIC_NAME_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final Path dataDirectory;
    private final Path backupDirectory;
    private final Logger logger;

    public ConfigurationRepair(File dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory.toPath().toAbsolutePath().normalize();
        this.backupDirectory = this.dataDirectory.resolve("backups").resolve("config-repair");
        this.logger = logger;
    }

    public synchronized <T extends OkaeriConfig> RepairResult repair(
            Class<T> configClass, File targetFile, DocumentType documentType) {
        Path target = targetFile.toPath().toAbsolutePath().normalize();
        ensureManagedPath(target);

        try {
            Files.createDirectories(dataDirectory);
            YamlConfiguration schema = createSchema(configClass);
            return repairTarget(target, schema, documentType);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not safely repair " + target.getFileName(), exception);
        }
    }

    public synchronized RepairResult repair(File targetFile, InputStream defaults,
                                            DocumentType documentType) {
        Path target = targetFile.toPath().toAbsolutePath().normalize();
        ensureManagedPath(target);
        if (defaults == null)
            throw new IllegalArgumentException("Default configuration stream cannot be null");

        try (InputStream input = defaults) {
            byte[] bytes = readBounded(input);
            YamlConfiguration schema = new YamlConfiguration();
            schema.options().parseComments(true);
            schema.loadFromString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            return repairTarget(target, schema, documentType);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not load module configuration defaults", exception);
        }
    }

    private RepairResult repairTarget(Path target, YamlConfiguration schema,
                                      DocumentType documentType) throws IOException {
        Files.createDirectories(dataDirectory);
        if (!Files.exists(target)) {
            atomicSave(schema, target);
            RepairResult created = new RepairResult();
            created.added = schema.getKeys(true).size();
            created.created = true;
            logResult(target, created);
            return created;
        }

        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target))
            throw new IllegalStateException("Configuration target must be a regular, non-symbolic file: "
                    + target.getFileName());
        if (Files.size(target) > MAX_FILE_BYTES)
            return resetInvalidFile(target, schema, "file exceeds 8 MiB");

        YamlConfiguration current = new YamlConfiguration();
        current.options().parseComments(true);
        try {
            current.load(target.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            return resetInvalidFile(target, schema, "malformed YAML");
        }

        RepairResult result = repairDocument(current, schema, documentType,
                ConfigurationRepair.class.getClassLoader());
        if (!result.changed())
            return result;

        result.backup = backup(target);
        atomicSave(current, target);
            pruneBackupsQuietly();
        logResult(target, result);
        return result;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0L;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_FILE_BYTES)
                throw new IOException("Default configuration exceeds 8 MiB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private <T extends OkaeriConfig> YamlConfiguration createSchema(Class<T> configClass) {
        T defaults = ConfigManager.create(configClass,
                config -> config.withConfigurer(new YamlBukkitConfigurer()));
        YamlConfiguration schema = new YamlConfiguration();
        schema.options().parseComments(true);
        try {
            schema.loadFromString(defaults.saveToString());
            return schema;
        } catch (InvalidConfigurationException exception) {
            throw new IllegalStateException("Generated configuration schema is invalid", exception);
        }
    }

    private RepairResult resetInvalidFile(Path target, YamlConfiguration schema, String reason)
            throws IOException {
        RepairResult result = new RepairResult();
        result.corrected = Math.max(1, schema.getKeys(true).size());
        result.reset = true;
        result.reason = reason;
        result.backup = backup(target);
        atomicSave(schema, target);
        pruneBackupsQuietly();
        logResult(target, result);
        return result;
    }

    static RepairResult repairDocument(YamlConfiguration current, YamlConfiguration schema,
                                       DocumentType documentType, ClassLoader classLoader) {
        RepairResult result = new RepairResult();
        repairSection(current, schema, "", result);
        if (documentType == DocumentType.CONFIG)
            repairConfigSemantics(current, schema, result, classLoader);
        else if (documentType == DocumentType.LANGUAGE)
            repairLanguageSemantics(current, schema, result);
        return result;
    }

    private static void repairSection(ConfigurationSection current, ConfigurationSection schema,
                                      String path, RepairResult result) {
        Set<String> schemaKeys = schema.getKeys(false);
        if (schemaKeys.isEmpty() && path.endsWith(".names")) {
            repairDynamicNames(current, result);
            return;
        }

        for (String key : new ArrayList<>(current.getKeys(false))) {
            if (!schemaKeys.contains(key)) {
                current.set(key, null);
                result.removed++;
                result.detail("removed unknown " + join(path, key));
            }
        }

        for (String key : schemaKeys) {
            String childPath = join(path, key);
            Object expected = schema.get(key);
            Object actual = current.get(key);
            if (actual == null) {
                copyNode(schema, current, key);
                result.added++;
                result.detail("added missing " + childPath);
                continue;
            }

            if (expected instanceof ConfigurationSection) {
                if (!(actual instanceof ConfigurationSection)) {
                    current.set(key, null);
                    copyNode(schema, current, key);
                    result.corrected++;
                    result.detail("replaced invalid section " + childPath);
                } else {
                    repairSection((ConfigurationSection) actual, (ConfigurationSection) expected,
                            childPath, result);
                }
                continue;
            }

            if (!compatible(actual, expected) || invalidScalar(actual, expected)) {
                current.set(key, copyValue(expected));
                copyComments(schema, current, key);
                result.corrected++;
                result.detail("replaced invalid value " + childPath);
                continue;
            }

            if (actual instanceof List) {
                List<?> list = (List<?>) actual;
                if (list.size() > MAX_LIST_ENTRIES) {
                    current.set(key, copyValue(expected));
                    result.corrected++;
                    result.detail("replaced oversized list " + childPath);
                    continue;
                }
                List<String> cleaned = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(value -> value.length() <= MAX_STRING_LENGTH)
                        .collect(Collectors.toList());
                if (cleaned.size() != list.size()) {
                    if (cleaned.isEmpty() && !((List<?>) expected).isEmpty())
                        current.set(key, copyValue(expected));
                    else
                        current.set(key, cleaned);
                    result.corrected++;
                    result.detail("removed invalid list entries from " + childPath);
                }
            }
        }
    }

    private static void repairDynamicNames(ConfigurationSection section, RepairResult result) {
        List<String> keys = new ArrayList<>(section.getKeys(false));
        if (keys.size() > MAX_DYNAMIC_NAMES) {
            for (String key : keys)
                section.set(key, null);
            result.removed += keys.size();
            result.detail("cleared oversized material names map");
            return;
        }
        for (String key : keys) {
            Object value = section.get(key);
            if (!DYNAMIC_NAME_KEY.matcher(key).matches() || !(value instanceof String)
                    || ((String) value).trim().isEmpty()
                    || ((String) value).length() > MAX_STRING_LENGTH) {
                section.set(key, null);
                result.removed++;
                result.detail("removed invalid material name " + key);
            }
        }
    }

    private static boolean compatible(Object actual, Object expected) {
        if (expected instanceof Number)
            return actual instanceof Number;
        if (expected instanceof List)
            return actual instanceof List;
        if (expected instanceof Map)
            return actual instanceof Map || actual instanceof ConfigurationSection;
        return expected != null && expected.getClass().isInstance(actual);
    }

    private static boolean invalidScalar(Object actual, Object expected) {
        if (actual instanceof String) {
            String value = (String) actual;
            return value.length() > MAX_STRING_LENGTH
                    || (!((String) expected).isEmpty() && value.trim().isEmpty());
        }
        if (actual instanceof Double) {
            double value = (Double) actual;
            return Double.isNaN(value) || Double.isInfinite(value);
        }
        if (actual instanceof Float) {
            float value = (Float) actual;
            return Float.isNaN(value) || Float.isInfinite(value);
        }
        return false;
    }

    private static void repairConfigSemantics(YamlConfiguration current, YamlConfiguration schema,
                                              RepairResult result, ClassLoader classLoader) {
        repairLanguageName(current, schema, result, classLoader);
        repairNumber(current, schema, result, "settings.farmer-price", 0D, 1.0E15D);
        repairNumber(current, schema, result, "settings.default-max-farmer-user", 0D, 100000D);
        repairNumber(current, schema, result, "production.re-calculate", 1D, 86400D);
        repairNumber(current, schema, result, "tax.rate", 0D, 100D);
        repairNumber(current, schema, result, "bedrock-forms.page-size", 5D, 50D);
        repairNumber(current, schema, result, "bedrock-forms.session-timeout-ms", 1000D, 300000D);
        repairNumber(current, schema, result, "bedrock-forms.click-cooldown-ms", 0D, 5000D);
        repairNumber(current, schema, result, "bedrock-forms.max-lore-lines", 0D, 10D);
        repairNumber(current, schema, result, "bedrock-forms.max-button-length", 32D, 512D);
        repairDatabase(current, schema, result);
        repairStringList(current, schema, result, "settings.allowed-worlds", false);
        repairStringList(current, schema, result, "production.items", true);
        repairLayout(current, schema, result, "gui.farmer-layout");
        repairLayout(current, schema, result, "gui.manage-layout");
        repairLayout(current, schema, result, "gui.buy-farmer-layout");
        repairLayout(current, schema, result, "gui.users-layout");
        repairLayout(current, schema, result, "gui.geyser-layout");
        repairLayout(current, schema, result, "gui.module-layout");
    }

    private static void repairLanguageSemantics(YamlConfiguration current, YamlConfiguration schema,
                                                RepairResult result) {
        repairRequiredList(current, schema, result, "commands.about");
        repairRequiredList(current, schema, result, "commands.info-header");
    }

    private static void repairLanguageName(YamlConfiguration current, YamlConfiguration schema,
                                           RepairResult result, ClassLoader classLoader) {
        String value = current.getString("settings.lang", "").trim().toLowerCase(Locale.ROOT);
        boolean valid = LANGUAGE_NAME.matcher(value).matches();
        if (valid) {
            try {
                Class.forName("xyz.geik.farmer.configuration.lang." + value, false, classLoader);
            } catch (ClassNotFoundException | LinkageError exception) {
                valid = false;
            }
        }
        if (!valid) {
            replaceWithSchema(current, schema, result, "settings.lang", "unsupported language");
        } else if (!value.equals(current.getString("settings.lang"))) {
            current.set("settings.lang", value);
            result.corrected++;
            result.detail("normalized settings.lang");
        }
    }

    private static void repairDatabase(YamlConfiguration current, YamlConfiguration schema,
                                       RepairResult result) {
        String type = current.getString("database.database-type", "");
        if (type.equalsIgnoreCase("sqlite")) {
            if (!"SQLite".equals(type)) {
                current.set("database.database-type", "SQLite");
                result.corrected++;
            }
            return;
        }
        if (!type.equalsIgnoreCase("mysql")) {
            replaceWithSchema(current, schema, result, "database.database-type", "invalid database type");
            return;
        }
        if (!"MySQL".equals(type)) {
            current.set("database.database-type", "MySQL");
            result.corrected++;
        }
        String port = current.getString("database.port", "");
        try {
            int parsed = Integer.parseInt(port);
            if (parsed < 1 || parsed > 65535)
                throw new NumberFormatException("out of range");
        } catch (NumberFormatException exception) {
            replaceWithSchema(current, schema, result, "database.port", "invalid database port");
        }
    }

    private static void repairNumber(YamlConfiguration current, YamlConfiguration schema,
                                     RepairResult result, String path, double minimum, double maximum) {
        Object raw = current.get(path);
        if (!(raw instanceof Number))
            return;
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < minimum || value > maximum)
            replaceWithSchema(current, schema, result, path, "out-of-range number");
    }

    private static void repairStringList(YamlConfiguration current, YamlConfiguration schema,
                                         RepairResult result, String path, boolean allowEmpty) {
        List<?> values = current.getList(path);
        if (values == null)
            return;
        List<String> cleaned = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isEmpty() && value.length() <= 128)
                .distinct()
                .collect(Collectors.toList());
        if ((!allowEmpty && cleaned.isEmpty()) || cleaned.size() != values.size()) {
            if (!allowEmpty && cleaned.isEmpty())
                replaceWithSchema(current, schema, result, path, "empty required list");
            else {
                current.set(path, cleaned);
                result.corrected++;
                result.detail("cleaned " + path);
            }
        }
    }

    private static void repairLayout(YamlConfiguration current, YamlConfiguration schema,
                                     RepairResult result, String path) {
        List<String> layout = current.getStringList(path);
        boolean valid = !layout.isEmpty() && layout.size() <= 6;
        for (String row : layout)
            valid &= row != null && row.length() == 9 && row.chars().allMatch(character ->
                    character == ' ' || (character >= 33 && character <= 126));
        if (!valid)
            replaceWithSchema(current, schema, result, path, "invalid GUI layout");
    }

    private static void repairRequiredList(YamlConfiguration current, YamlConfiguration schema,
                                           RepairResult result, String path) {
        List<?> list = current.getList(path);
        if (list == null || list.isEmpty())
            replaceWithSchema(current, schema, result, path, "empty required language list");
    }

    private static void replaceWithSchema(YamlConfiguration current, YamlConfiguration schema,
                                          RepairResult result, String path, String reason) {
        current.set(path, copyValue(schema.get(path)));
        current.setComments(path, schema.getComments(path));
        current.setInlineComments(path, schema.getInlineComments(path));
        result.corrected++;
        result.detail(reason + " at " + path);
    }

    private static void copyNode(ConfigurationSection source, ConfigurationSection target, String key) {
        Object value = source.get(key);
        if (value instanceof ConfigurationSection) {
            ConfigurationSection child = target.createSection(key);
            copyComments(source, target, key);
            for (String childKey : ((ConfigurationSection) value).getKeys(false))
                copyNode((ConfigurationSection) value, child, childKey);
        } else {
            target.set(key, copyValue(value));
            copyComments(source, target, key);
        }
    }

    private static Object copyValue(Object value) {
        if (value instanceof List)
            return new ArrayList<>((List<?>) value);
        if (value instanceof Map)
            return new java.util.LinkedHashMap<>((Map<?, ?>) value);
        return value;
    }

    private static void copyComments(ConfigurationSection source, ConfigurationSection target, String key) {
        target.setComments(key, source.getComments(key));
        target.setInlineComments(key, source.getInlineComments(key));
    }

    private Path backup(Path target) throws IOException {
        ensureManagedPath(backupDirectory.resolve("backup-set"));
        Files.createDirectories(backupDirectory);
        String baseName = BACKUP_TIME.format(Instant.now());
        Path backupSet = backupDirectory.resolve(baseName);
        int suffix = 1;
        while (Files.exists(backupSet))
            backupSet = backupDirectory.resolve(baseName + "-" + suffix++);

        Path relative = dataDirectory.relativize(target);
        Path destination = backupSet.resolve(relative).normalize();
        if (!destination.startsWith(backupSet))
            throw new IOException("Unsafe backup destination");
        ensureManagedPath(destination);
        Files.createDirectories(destination.getParent());
        Files.copy(target, destination, StandardCopyOption.COPY_ATTRIBUTES);
        return destination;
    }

    private void atomicSave(YamlConfiguration configuration, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".repair-", ".tmp");
        try {
            configuration.save(temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void pruneBackups() throws IOException {
        if (!Files.isDirectory(backupDirectory))
            return;
        List<Path> backupSets;
        try (Stream<Path> stream = Files.list(backupDirectory)) {
            backupSets = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
        }
        for (int index = MAX_BACKUPS; index < backupSets.size(); index++)
            deleteTree(backupSets.get(index));
    }

    private void pruneBackupsQuietly() {
        try {
            pruneBackups();
        } catch (IOException exception) {
            logger.warning("Could not prune old configuration backups: " + exception.getMessage());
        }
    }

    private void deleteTree(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(backupDirectory) || normalized.equals(backupDirectory))
            throw new IOException("Refusing to delete an unsafe backup path");
        try (Stream<Path> stream = Files.walk(normalized)) {
            List<Path> paths = stream.sorted(Collections.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths)
                Files.deleteIfExists(path);
        }
    }

    private void ensureManagedPath(Path target) {
        if (!target.startsWith(dataDirectory) || target.equals(dataDirectory))
            throw new IllegalArgumentException("Configuration path must stay inside the plugin data directory");
        Path current = dataDirectory;
        if (Files.isSymbolicLink(current))
            throw new IllegalArgumentException("Plugin data directory cannot be a symbolic link");
        for (Path segment : dataDirectory.relativize(target)) {
            current = current.resolve(segment);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current))
                throw new IllegalArgumentException("Configuration path cannot contain symbolic links");
        }
    }

    private void logResult(Path target, RepairResult result) {
        String relative = dataDirectory.relativize(target).toString();
        if (result.created) {
            logger.info("Created complete configuration file " + relative + ".");
            return;
        }
        String message = "Repaired " + relative + ": added=" + result.added
                + ", corrected=" + result.corrected + ", removed=" + result.removed;
        if (result.reason != null)
            message += ", reason=" + result.reason;
        if (result.backup != null)
            message += ", backup=" + dataDirectory.relativize(result.backup);
        logger.warning(message);
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    public enum DocumentType {
        CONFIG,
        LANGUAGE,
        MODULE
    }

    public static final class RepairResult {
        private int added;
        private int corrected;
        private int removed;
        private boolean created;
        private boolean reset;
        private String reason;
        private Path backup;
        private final List<String> details = new ArrayList<>();

        public boolean changed() {
            return created || reset || added > 0 || corrected > 0 || removed > 0;
        }

        private void detail(String detail) {
            if (details.size() < 50)
                details.add(detail);
        }

        public int getAdded() {
            return added;
        }

        public int getCorrected() {
            return corrected;
        }

        public int getRemoved() {
            return removed;
        }

        public boolean isReset() {
            return reset;
        }

        public Path getBackup() {
            return backup;
        }

        public List<String> getDetails() {
            return Collections.unmodifiableList(details);
        }
    }
}
