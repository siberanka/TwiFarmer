package xyz.geik.farmer.compatibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCompatibilityTest {

    @Test
    void acceptsAdvertisedMinecraftVersionLines() {
        assertTrue(RuntimeCompatibility.isSupportedVersion(1, 21));
        assertTrue(RuntimeCompatibility.isSupportedVersion(26, 1));
        assertTrue(RuntimeCompatibility.isSupportedVersion(26, 2));
        assertTrue(RuntimeCompatibility.isSupportedVersion(26, 9));
        assertTrue(RuntimeCompatibility.isSupportedVersion(26, Integer.MAX_VALUE));
    }

    @Test
    void rejectsUnverifiedMinecraftVersionLines() {
        assertFalse(RuntimeCompatibility.isSupportedVersion(1, 20));
        assertFalse(RuntimeCompatibility.isSupportedVersion(25, 1));
        assertFalse(RuntimeCompatibility.isSupportedVersion(27, 1));
    }

    @Test
    void boundsNestedFailureDiagnostics() {
        String repeated = new String(new char[400]).replace('\0', 'x');
        String summary = RuntimeCompatibility.summarize(
                new IllegalStateException("wrapper", new LinkageError(repeated + "\ncontrolled")));
        assertTrue(summary.startsWith("LinkageError: "));
        assertTrue(summary.length() <= "LinkageError: ".length() + 240);
        assertFalse(summary.contains("\n"));
    }
}
