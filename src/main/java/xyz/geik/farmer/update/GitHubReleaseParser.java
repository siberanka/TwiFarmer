package xyz.geik.farmer.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.Optional;

/** Parses and validates the bounded GitHub release fields used by Farmer. */
public final class GitHubReleaseParser {
    private static final String RELEASE_PATH = "/siberanka/TwiFarmer/releases/";
    static final int MAX_RESPONSE_LENGTH = 65_536;

    private GitHubReleaseParser() {
    }

    public static Optional<ReleaseInfo> parse(String body) {
        if (body == null || body.trim().isEmpty() || body.length() > MAX_RESPONSE_LENGTH)
            return Optional.empty();
        try {
            JsonElement root = new JsonParser().parse(body);
            if (!root.isJsonObject())
                return Optional.empty();
            JsonObject release = root.getAsJsonObject();
            String tag = string(release, "tag_name");
            if (tag == null || !ReleaseVersion.parse(tag).isPresent())
                return Optional.empty();
            String releaseUrl = validateUrl(string(release, "html_url")).orElse(null);
            String downloadUrl = findJar(release.getAsJsonArray("assets")).orElse(releaseUrl);
            return downloadUrl == null ? Optional.empty() : Optional.of(new ReleaseInfo(tag, downloadUrl));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> findJar(JsonArray assets) {
        if (assets == null || assets.size() > 128)
            return Optional.empty();
        for (JsonElement element : assets) {
            if (!element.isJsonObject())
                continue;
            JsonObject asset = element.getAsJsonObject();
            String name = string(asset, "name");
            if (name != null && name.startsWith("Farmer-v") && name.endsWith(".jar")) {
                Optional<String> url = validateUrl(string(asset, "browser_download_url"));
                if (url.isPresent())
                    return url;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateUrl(String raw) {
        if (raw == null || raw.length() > 512)
            return Optional.empty();
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPath() == null
                    || !uri.getPath().startsWith(RELEASE_PATH))
                return Optional.empty();
            return Optional.of(uri.toASCIIString());
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    public static final class ReleaseInfo {
        private final String tag;
        private final String downloadUrl;

        private ReleaseInfo(String tag, String downloadUrl) {
            this.tag = tag;
            this.downloadUrl = downloadUrl;
        }

        public String getTag() {
            return tag;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }
    }
}
