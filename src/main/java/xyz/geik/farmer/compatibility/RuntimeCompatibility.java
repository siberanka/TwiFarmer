package xyz.geik.farmer.compatibility;

import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Verifies shaded compatibility libraries before Farmer publishes runtime state.
 */
public final class RuntimeCompatibility {

    private static final String XMATERIAL = "xyz.geik.glib.shades.xseries.XMaterial";
    private static final String[] REQUIRED_CLASSES = {
            "xyz.geik.glib.shades.xseries.XSound",
            "xyz.geik.glib.shades.xseries.messages.Titles",
            "xyz.geik.glib.shades.xseries.profiles.builder.XSkull",
            "xyz.geik.glib.shades.xseries.profiles.objects.Profileable",
            "xyz.geik.glib.shades.xseries.reflection.parser.ReflectionParser",
            "xyz.geik.glib.shades.inventorygui.InventoryGui"
    };

    private RuntimeCompatibility() {}

    public static boolean verify(JavaPlugin plugin) {
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> materialClass = Class.forName(XMATERIAL, true, loader);
            int major = invokeVersion(materialClass, "getVersionMajor");
            int minor = invokeVersion(materialClass, "getVersionMinor");
            if (!isSupportedVersion(major, minor))
                throw new IllegalStateException("unsupported Minecraft version " + major + "." + minor);

            // These are the 26.x-sensitive surfaces Farmer reaches after startup.
            for (String className : REQUIRED_CLASSES)
                Class.forName(className, true, loader);

            materialClass.getMethod("matchXMaterial", String.class);
            materialClass.getMethod("parseItem");
            plugin.getLogger().info("Runtime compatibility verified for Minecraft " + major + "." + minor + ".");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            plugin.getLogger().severe("Farmer runtime compatibility check failed: " + summarize(failure));
            plugin.getLogger().severe("Farmer was disabled before listeners, commands, modules, or cached stock were published.");
            return false;
        }
    }

    private static int invokeVersion(Class<?> materialClass, String methodName) throws ReflectiveOperationException {
        Method method = materialClass.getMethod(methodName);
        Object result = method.invoke(null);
        if (!(result instanceof Number))
            throw new IllegalStateException(XMATERIAL + '#' + methodName + " returned a non-number");
        return ((Number) result).intValue();
    }

    static boolean isSupportedVersion(int major, int minor) {
        return (major == 1 && minor == 21) || (major == 26 && minor >= 1);
    }

    public static String summarize(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty())
            message = "no details";
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (message.length() > 240)
            message = message.substring(0, 240);
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
