package xyz.geik.farmer.update;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.configuration.ConfigFile;
import xyz.geik.glib.chat.ChatUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Asynchronous, lifecycle-safe checker for the fixed TwiFarmer repository. */
public final class UpdateChecker implements Listener {
    static final String ADMIN_PERMISSION = "farmer.admin";
    private static final URI RELEASE_API = URI.create(
            "https://api.github.com/repos/siberanka/TwiFarmer/releases/latest");
    private static final long TICKS_PER_HOUR = 72_000L;
    private static final long FAILURE_LOG_INTERVAL_NANOS = 3_600_000_000_000L;
    private static final int MAX_NOTIFIED_PLAYERS = 4_096;

    private final Main plugin;
    private final boolean enabled;
    private final int checkIntervalHours;
    private final int requestTimeoutSeconds;
    private final String currentVersion;
    private final HttpClient client;
    private final AtomicBoolean requestRunning = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicReference<CompletableFuture<?>> request = new AtomicReference<>();
    private final ConcurrentMap<UUID, String> notifiedPlayers = new ConcurrentHashMap<>();
    private volatile ScheduledTask periodicTask;
    private volatile GitHubReleaseParser.ReleaseInfo availableUpdate;
    private volatile String consoleNotifiedTag;
    private volatile String responseEtag;
    private volatile boolean running;

    public UpdateChecker(Main plugin, ConfigFile.UpdateChecker settings) {
        this.plugin = plugin;
        this.enabled = settings.isEnable();
        this.checkIntervalHours = clamp(settings.getCheckIntervalHours(), 1, 168);
        int connectTimeoutSeconds = clamp(settings.getConnectTimeoutSeconds(), 2, 30);
        this.requestTimeoutSeconds = clamp(settings.getRequestTimeoutSeconds(), 3, 60);
        this.currentVersion = plugin.getDescription().getVersion();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void start() {
        stop();
        if (!enabled)
            return;
        if (!ReleaseVersion.parse(currentVersion).isPresent()) {
            plugin.getLogger().warning("Farmer update check is disabled: current version is invalid.");
            return;
        }
        running = true;
        final long serviceGeneration = generation.incrementAndGet();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        checkNow(serviceGeneration);
        try {
            long intervalTicks = Math.multiplyExact((long) checkIntervalHours, TICKS_PER_HOUR);
            periodicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    task -> checkNow(serviceGeneration), intervalTicks, intervalTicks);
        } catch (RuntimeException exception) {
            logFailure("could not schedule periodic update checks", exception);
        }
    }

    public void stop() {
        synchronized (notifiedPlayers) {
            running = false;
            availableUpdate = null;
            notifiedPlayers.clear();
        }
        generation.incrementAndGet();
        HandlerList.unregisterAll(this);
        ScheduledTask task = periodicTask;
        periodicTask = null;
        if (task != null)
            task.cancel();
        CompletableFuture<?> active = request.getAndSet(null);
        if (active != null)
            active.cancel(true);
        requestRunning.set(false);
        consoleNotifiedTag = null;
        responseEtag = null;
    }

    private void checkNow(final long serviceGeneration) {
        if (!isCurrent(serviceGeneration) || !requestRunning.compareAndSet(false, true))
            return;
        HttpRequest.Builder builder = HttpRequest.newBuilder(RELEASE_API)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Farmer/" + currentVersion)
                .GET();
        if (responseEtag != null)
            builder.header("If-None-Match", responseEtag);

        CompletableFuture<Void> future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> handleResponse(response, serviceGeneration))
                .exceptionally(exception -> {
                    if (isCurrent(serviceGeneration))
                        logFailure("could not query GitHub releases", unwrap(exception));
                    return null;
                }).whenComplete((ignored, exception) -> {
                    if (generation.get() == serviceGeneration)
                        requestRunning.set(false);
                });
        request.set(future);
    }

    private void handleResponse(HttpResponse<InputStream> response, long serviceGeneration) {
        if (!isCurrent(serviceGeneration) || response.statusCode() == 304) {
            closeResponse(response);
            return;
        }
        String body;
        try (InputStream stream = response.body()) {
            if (response.statusCode() != 200) {
                logFailure("GitHub release API returned HTTP " + response.statusCode(), null);
                return;
            }
            byte[] bytes = readBounded(stream);
            if (bytes == null) {
                logFailure("GitHub release API response exceeded the size limit", null);
                return;
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logFailure("could not read the GitHub release response", exception);
            return;
        }
        response.headers().firstValue("ETag")
                .filter(value -> value.length() <= 256 && value.matches("[\\x21-\\x7E]+"))
                .ifPresent(value -> responseEtag = value);
        GitHubReleaseParser.ReleaseInfo latest = GitHubReleaseParser.parse(body).orElse(null);
        if (latest == null) {
            logFailure("GitHub release API returned an invalid response", null);
            return;
        }
        if (!ReleaseVersion.isNewer(currentVersion, latest.getTag())) {
            availableUpdate = null;
            return;
        }
        GitHubReleaseParser.ReleaseInfo previous = availableUpdate;
        availableUpdate = latest;
        if (previous == null || !previous.getTag().equals(latest.getTag())) {
            notifiedPlayers.clear();
            notifyAdmins(latest, serviceGeneration);
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (output.size() + read > GitHubReleaseParser.MAX_RESPONSE_LENGTH)
                return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void notifyAdmins(GitHubReleaseParser.ReleaseInfo update, long serviceGeneration) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                synchronized (notifiedPlayers) {
                    if (!isCurrent(serviceGeneration) || availableUpdate != update)
                        return;
                    if (!update.getTag().equals(consoleNotifiedTag)) {
                        consoleNotifiedTag = update.getTag();
                        ChatUtils.sendMessage(Bukkit.getConsoleSender(), notificationMessage(update));
                    }
                }
                for (Player player : new ArrayList<Player>(Bukkit.getOnlinePlayers()))
                    player.getScheduler().run(plugin, ignored -> notifyPlayer(player, update), null);
            });
        } catch (RuntimeException exception) {
            logFailure("could not schedule update notifications", exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        GitHubReleaseParser.ReleaseInfo update = availableUpdate;
        if (running && update != null)
            notifyPlayer(event.getPlayer(), update);
    }

    private void notifyPlayer(Player player, GitHubReleaseParser.ReleaseInfo update) {
        if (!canNotify(player))
            return;
        UUID id = player.getUniqueId();
        synchronized (notifiedPlayers) {
            if (!running || availableUpdate != update || update.getTag().equals(notifiedPlayers.get(id)))
                return;
            if (notifiedPlayers.size() < MAX_NOTIFIED_PLAYERS)
                notifiedPlayers.put(id, update.getTag());
            ChatUtils.sendMessage(player, notificationMessage(update));
        }
    }

    private String notificationMessage(GitHubReleaseParser.ReleaseInfo update) {
        String template = Main.getLangFile().getMessages().getUpdateAvailable();
        if (template == null || template.trim().isEmpty())
            template = "{prefix} &e[{plugin}] Update available: &f{current} &7-> &a{latest}&7. Download: &b{url}";
        return formatMessage(template, Main.getLangFile().getMessages().getPrefix(), plugin.getName(),
                currentVersion, update.getTag(), update.getDownloadUrl());
    }

    static boolean canNotify(Player player) {
        return player.isOp() || player.hasPermission(ADMIN_PERMISSION);
    }

    static String formatMessage(String template, String prefix, String pluginName,
                                String current, String latest, String url) {
        return template.replace("{prefix}", prefix).replace("{plugin}", pluginName)
                .replace("{current}", current).replace("{latest}", latest).replace("{url}", url);
    }

    private boolean isCurrent(long serviceGeneration) {
        return running && generation.get() == serviceGeneration;
    }

    private void closeResponse(HttpResponse<InputStream> response) {
        try (InputStream ignored = response.body()) {
        } catch (IOException exception) {
            logFailure("could not close the GitHub release response", exception);
        }
    }

    private void logFailure(String message, Throwable exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if (now < next || !nextFailureLogNanos.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS))
            return;
        if (exception == null)
            plugin.getLogger().warning("Farmer " + message + '.');
        else
            plugin.getLogger().log(Level.WARNING, "Farmer " + message + '.', exception);
    }

    private Throwable unwrap(Throwable exception) {
        return exception.getCause() == null ? exception : exception.getCause();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
