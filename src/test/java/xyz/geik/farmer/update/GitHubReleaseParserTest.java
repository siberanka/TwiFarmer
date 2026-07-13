package xyz.geik.farmer.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseParserTest {
    @Test
    void selectsValidatedFarmerJar() {
        String body = "{\"tag_name\":\"v6-b117\","
                + "\"html_url\":\"https://github.com/siberanka/TwiFarmer/releases/tag/v6-b117\","
                + "\"assets\":[{\"name\":\"Farmer-v6-b117.jar\","
                + "\"browser_download_url\":\"https://github.com/siberanka/TwiFarmer/releases/download/v6-b117/Farmer-v6-b117.jar\"}]}";
        GitHubReleaseParser.ReleaseInfo release = GitHubReleaseParser.parse(body).orElseThrow(AssertionError::new);
        assertEquals("v6-b117", release.getTag());
        assertTrue(release.getDownloadUrl().endsWith("Farmer-v6-b117.jar"));
    }

    @Test
    void rejectsMalformedOversizedAndForeignResponses() {
        assertFalse(GitHubReleaseParser.parse("not-json").isPresent());
        assertFalse(GitHubReleaseParser.parse("x".repeat(GitHubReleaseParser.MAX_RESPONSE_LENGTH + 1)).isPresent());
        assertFalse(GitHubReleaseParser.parse("{\"tag_name\":\"v6-b117\","
                + "\"html_url\":\"https://example.com/release\",\"assets\":[]}").isPresent());
    }
}
