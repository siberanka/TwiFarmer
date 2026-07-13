package xyz.geik.farmer.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionTest {
    @Test
    void comparesFarmerBuildTags() {
        assertTrue(ReleaseVersion.isNewer("v6-b116", "v6-b117"));
        assertTrue(ReleaseVersion.isNewer("v6-b999", "v7-b1"));
        assertFalse(ReleaseVersion.isNewer("v6-b117", "v6-b116"));
        assertFalse(ReleaseVersion.isNewer("v6-b117", "v6-b117"));
    }

    @Test
    void rejectsMalformedAndUnboundedTags() {
        assertFalse(ReleaseVersion.parse("6.117.0").isPresent());
        assertFalse(ReleaseVersion.parse("v6-b0117").isPresent());
        assertFalse(ReleaseVersion.parse("v6-b117-beta").isPresent());
        assertFalse(ReleaseVersion.parse("9".repeat(65)).isPresent());
    }
}
