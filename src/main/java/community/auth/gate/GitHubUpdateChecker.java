package community.auth.gate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.plugin.java.JavaPlugin;

final class GitHubUpdateChecker {
    private static final Pattern JSON_STRING = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*\\\"([^\\\"\\\\]*(?:\\\\.[^\\\"\\\\]*)*)\\\"");
    private static final Pattern NUMERIC_VERSION = Pattern.compile("(?i)^v?(\\d+(?:\\.\\d+)*)$");

    private GitHubUpdateChecker() {
    }

    static void schedule(JavaPlugin plugin) {
        String configuredRepository = plugin.getConfig().getString(
                "updates.github-repository", "ZeagaBG/minecraft-server-auth");
        String repository = configuredRepository == null ? "" : configuredRepository.trim();
        int timeoutSeconds = Math.max(2, Math.min(15,
                plugin.getConfig().getInt("updates.timeout-seconds", 8)));
        String pluginName = plugin.getName();
        String currentVersion = plugin.getDescription().getVersion();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> check(plugin, repository, timeoutSeconds, pluginName, currentVersion));
    }

    private static void check(JavaPlugin plugin, String repository, int timeoutSeconds,
                              String pluginName, String currentVersion) {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            plugin.getLogger().warning("Update check skipped: updates.github-repository must be owner/repository.");
            return;
        }

        URI endpoint = URI.create("https://api.github.com/repos/" + repository + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", pluginName + "/" + currentVersion)
                .GET()
                .build();

        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                plugin.getLogger().info("Update check finished: no published stable GitHub release was found.");
                return;
            }
            if (response.statusCode() != 200) {
                plugin.getLogger().warning("Update check failed: GitHub returned HTTP status "
                        + response.statusCode() + ".");
                return;
            }

            String release = response.body();
            String latest = jsonString(release, "tag_name");
            String releaseUrl = jsonString(release, "html_url");
            if (latest == null || releaseUrl == null || !releaseUrl.startsWith("https://github.com/")) {
                plugin.getLogger().warning("Update check skipped: GitHub returned incomplete release information.");
                return;
            }

            Integer comparison = compareVersions(latest, currentVersion);
            if (comparison == null) {
                plugin.getLogger().warning("Update check could not compare version '" + currentVersion
                        + "' with GitHub release '" + latest + "'. Use numeric versions such as 1.2.3.");
            } else if (comparison > 0) {
                plugin.getLogger().info("Update available: " + latest + " (current: " + currentVersion
                        + "). Download it from " + releaseUrl);
            } else {
                plugin.getLogger().info("Update check finished: CommunityAuthGate is up to date ("
                        + currentVersion + "; latest: " + latest + ").");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Update check was interrupted before it could finish.");
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().warning("Update check could not reach GitHub: " + exception.getMessage());
        }
    }

    private static String jsonString(String json, String field) {
        Pattern fieldPattern = Pattern.compile(String.format(JSON_STRING.pattern(), Pattern.quote(field)));
        Matcher matcher = fieldPattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Integer compareVersions(String left, String right) {
        long[] leftParts = versionParts(left);
        long[] rightParts = versionParts(right);
        if (leftParts == null || rightParts == null) {
            return null;
        }
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            long leftPart = index < leftParts.length ? leftParts[index] : 0;
            long rightPart = index < rightParts.length ? rightParts[index] : 0;
            int comparison = Long.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static long[] versionParts(String version) {
        Matcher matcher = NUMERIC_VERSION.matcher(version);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Arrays.stream(matcher.group(1).split("\\."))
                    .mapToLong(Long::parseLong)
                    .toArray();
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
