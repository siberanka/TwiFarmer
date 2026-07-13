package xyz.geik.farmer.update;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comparator for Farmer's v&lt;major&gt;-b&lt;build&gt; release tags. */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private static final int MAX_VERSION_LENGTH = 64;
    private static final Pattern VERSION = Pattern.compile("(?i)^v?(0|[1-9][0-9]*)-b(0|[1-9][0-9]*)$");

    private final BigInteger major;
    private final BigInteger build;

    private ReleaseVersion(BigInteger major, BigInteger build) {
        this.major = major;
        this.build = build;
    }

    public static Optional<ReleaseVersion> parse(String raw) {
        if (raw == null)
            return Optional.empty();
        String value = raw.trim();
        if (value.length() > MAX_VERSION_LENGTH)
            return Optional.empty();
        Matcher matcher = VERSION.matcher(value);
        if (!matcher.matches())
            return Optional.empty();
        return Optional.of(new ReleaseVersion(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2))));
    }

    public static boolean isNewer(String current, String candidate) {
        Optional<ReleaseVersion> currentVersion = parse(current);
        Optional<ReleaseVersion> candidateVersion = parse(candidate);
        return currentVersion.isPresent() && candidateVersion.isPresent()
                && candidateVersion.get().compareTo(currentVersion.get()) > 0;
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        int majorResult = major.compareTo(other.major);
        return majorResult != 0 ? majorResult : build.compareTo(other.build);
    }
}
