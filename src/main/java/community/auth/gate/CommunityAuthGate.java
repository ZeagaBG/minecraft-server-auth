package community.auth.gate;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class CommunityAuthGate extends JavaPlugin {
    private static final String ADMIN_PERMISSION = "communityauth.admin";
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final Set<UUID> restrictedSessions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> authorizedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingApprovalSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStartedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> eligibilityLastSuccess = new ConcurrentHashMap<>();
    private final Map<UUID, AttemptWindow> attemptWindows = new ConcurrentHashMap<>();
    private AuthStore store;
    private DiscordAuthService discord;
    private volatile boolean gateActive;
    private volatile int maxUnauthenticatedPlayers = 8;

    private record AttemptWindow(long startedAt, int attempts) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("updates.enabled", true)) {
            GitHubUpdateChecker.schedule(this);
        }
        maxUnauthenticatedPlayers = Math.max(1,
                getConfig().getInt("verification.max-unauthenticated-players", 8));
        try {
            store = new AuthStore(this);
        } catch (IOException | InvalidConfigurationException exception) {
            getLogger().log(Level.SEVERE,
                    "Could not load auth-data.yml. The plugin will stay disabled to protect saved account links.",
                    exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new RestrictedPlayerListener(this), this);

        PluginCommand authCommand = getCommand("auth");
        if (authCommand == null) {
            getLogger().severe("The /auth command is missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        authCommand.setExecutor(this);
        authCommand.setTabCompleter(this);

        gateActive = getConfig().getBoolean("enabled", false);
        if (!gateActive) {
            getLogger().warning("Admission is disabled. Set enabled: true after completing setup.");
            return;
        }

        String tokenVariable = getConfig().getString(
                "discord.token-environment-variable", "COMMUNITY_AUTH_DISCORD_TOKEN");
        String token = System.getenv(tokenVariable);
        String guildId = getConfig().getString("discord.guild-id", "");
        if (token == null || token.isBlank() || guildId == null || !guildId.matches("\\d{17,20}")) {
            getLogger().severe("Admission is enabled but Discord setup is incomplete. Players will remain restricted.");
            getLogger().log(Level.SEVERE, "Set {0} and discord.guild-id, then restart.", tokenVariable);
        } else {
            try {
                discord = new DiscordAuthService(this);
                discord.start(token);
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE,
                        "Could not start the Discord bot. New players will remain restricted.", exception);
            }
        }

        if (Bukkit.hasWhitelist()) {
            getLogger().warning("The vanilla whitelist is enabled. Players not listed there cannot reach /auth.");
        }

        long timeoutSeconds = Math.max(30,
                getConfig().getLong("verification.authentication-timeout-seconds", 180));
        getServer().getScheduler().runTaskTimer(this, () -> enforceAuthenticationTimeout(timeoutSeconds), 20L, 20L);

        long intervalSeconds = Math.max(30,
                getConfig().getLong("verification.eligibility-recheck-seconds", 60));
        getServer().getScheduler().runTaskTimer(this, this::recheckAllAuthorizedPlayers,
                intervalSeconds * 20L, intervalSeconds * 20L);
        getLogger().info("CommunityAuthGate enabled; unauthenticated sessions are restricted.");
    }

    @Override
    public void onDisable() {
        if (discord != null) {
            discord.close();
        }
    }

    boolean isGateActive() {
        return gateActive;
    }

    boolean isAtUnauthenticatedLimit() {
        return restrictedSessions.size() >= maxUnauthenticatedPlayers;
    }

    boolean isRestricted(Player player) {
        return gateActive && restrictedSessions.contains(player.getUniqueId());
    }

    Component message(String key) {
        String configured = getConfig().getString("messages." + key);
        return LEGACY_SERIALIZER.deserialize(configured == null ? key : configured);
    }

    void beginAuthentication(Player player) {
        UUID playerId = player.getUniqueId();
        if (isAdminBypass(player)) {
            restrictedSessions.remove(playerId);
            authorizedSessions.remove(playerId);
            pendingApprovalSessions.remove(playerId);
            sessionStartedAt.remove(playerId);
            eligibilityLastSuccess.remove(playerId);
            attemptWindows.remove(playerId);
            return;
        }
        authorizedSessions.remove(playerId);
        eligibilityLastSuccess.remove(playerId);
        restrictedSessions.add(playerId);
        sessionStartedAt.put(playerId, System.currentTimeMillis());
        attemptWindows.remove(playerId);
        player.sendMessage(message("restricted"));
        player.sendMessage(Component.text("Use /mc-verify in Discord to get a code.", NamedTextColor.GRAY));
    }

    void endSession(Player player) {
        UUID playerId = player.getUniqueId();
        restrictedSessions.remove(playerId);
        authorizedSessions.remove(playerId);
        pendingApprovalSessions.remove(playerId);
        sessionStartedAt.remove(playerId);
        eligibilityLastSuccess.remove(playerId);
        attemptWindows.remove(playerId);
    }

    void onDiscordMemberChanged(String discordId) {
        if (!gateActive || discord == null || !isEnabled()) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            for (Map.Entry<UUID, String> entry : Map.copyOf(authorizedSessions).entrySet()) {
                if (entry.getValue().equals(discordId)) {
                    recheckPlayer(entry.getKey(), discordId);
                }
            }
        });
    }

    void onDiscordMemberRemoved(String discordId) {
        if (!gateActive || !isEnabled()) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            for (Map.Entry<UUID, String> entry : Map.copyOf(authorizedSessions).entrySet()) {
                if (!entry.getValue().equals(discordId)) {
                    continue;
                }
                authorizedSessions.remove(entry.getKey());
                eligibilityLastSuccess.remove(entry.getKey());
                Player player = getServer().getPlayer(entry.getKey());
                if (player != null) {
                    player.kick(Component.text("You are no longer a member of the required Discord server."));
                }
            }
            for (AuthStore.Link pending : store.pendingLinks()) {
                if (pending.discordId().equals(discordId)) {
                    pendingApprovalSessions.remove(pending.playerId());
                    try {
                        store.deny(pending.playerId());
                    } catch (IOException exception) {
                        getLogger().log(Level.SEVERE,
                                "Could not remove a pending link after its Discord member left.", exception);
                    }
                }
            }
        });
    }

    private void recheckAllAuthorizedPlayers() {
        if (!gateActive || discord == null || !discord.isReady()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : Map.copyOf(authorizedSessions).entrySet()) {
            recheckPlayer(entry.getKey(), entry.getValue());
        }
    }

    private void recheckPlayer(UUID playerId, String discordId) {
        discord.checkEligibility(discordId, result -> {
            if (!isEnabled()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                String currentlyAuthorizedAs = authorizedSessions.get(playerId);
                if (currentlyAuthorizedAs == null || !currentlyAuthorizedAs.equals(discordId)) {
                    return;
                }
                if (!result.available()) {
                    long lastSuccess = eligibilityLastSuccess.getOrDefault(playerId, 0L);
                    long graceMillis = Math.max(0,
                            getConfig().getLong("verification.eligibility-check-failure-grace-seconds", 300))
                            * 1000L;
                    if (lastSuccess > 0 && System.currentTimeMillis() - lastSuccess < graceMillis) {
                        getLogger().warning("Discord eligibility could not be rechecked; the active session remains "
                                + "authorized only during its configured failure grace period.");
                        return;
                    }
                    authorizedSessions.remove(playerId);
                    eligibilityLastSuccess.remove(playerId);
                    Player player = getServer().getPlayer(playerId);
                    if (player != null) {
                        player.kick(Component.text(
                                "Discord verification could not be confirmed. Please rejoin when Discord is available."));
                    }
                    getLogger().warning("Revoked an active session after Discord eligibility checks remained unavailable "
                            + "beyond the configured grace period.");
                    return;
                }
                if (!result.eligible()) {
                    Player player = getServer().getPlayer(playerId);
                    authorizedSessions.remove(playerId);
                    eligibilityLastSuccess.remove(playerId);
                    if (player != null) {
                        player.kick(Component.text("Your Discord access no longer meets the server requirements."));
                    }
                    getLogger().info("Revoked an online Minecraft session after Discord eligibility was lost.");
                } else {
                    eligibilityLastSuccess.put(playerId, System.currentTimeMillis());
                }
            });
        });
    }

    private void enforceAuthenticationTimeout(long timeoutSeconds) {
        long cutoff = System.currentTimeMillis() - timeoutSeconds * 1000L;
        for (Player player : getServer().getOnlinePlayers()) {
            if (!restrictedSessions.contains(player.getUniqueId())) {
                continue;
            }
            Long startedAt = sessionStartedAt.get(player.getUniqueId());
            if (startedAt != null && startedAt < cutoff) {
                player.kick(Component.text("Verification timed out. Rejoin after requesting a fresh Discord code."));
            }
        }
    }

    private void requireAuthenticationCode(Player player, String rawCode) {
        UUID playerId = player.getUniqueId();
        if (!gateActive) {
            player.sendMessage(Component.text("Discord admission is currently disabled.", NamedTextColor.RED));
            return;
        }
        if (discord == null || !discord.isReady()) {
            player.sendMessage(message("unavailable"));
            return;
        }
        if (!consumeAttempt(playerId)) {
            player.sendMessage(Component.text("Too many code attempts. Wait a few minutes before trying again.", NamedTextColor.RED));
            return;
        }
        Optional<DiscordAuthService.CodeClaim> claim = discord.consumeCode(rawCode);
        if (claim.isEmpty()) {
            player.sendMessage(message("denied"));
            return;
        }

        String discordId = claim.get().discordId();
        player.sendMessage(Component.text("Checking your Discord membership and roles...", NamedTextColor.GRAY));
        discord.checkEligibility(discordId, result -> {
            if (!isEnabled()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                Player currentPlayer = getServer().getPlayer(playerId);
                if (currentPlayer == null || !restrictedSessions.contains(playerId)) {
                    return;
                }
                if (!result.available()) {
                    currentPlayer.sendMessage(message("unavailable"));
                    return;
                }
                if (!result.eligible()) {
                    currentPlayer.sendMessage(message("denied"));
                    return;
                }

                Optional<AuthStore.Link> existingLink = store.getLink(playerId);
                if (existingLink.isPresent()) {
                    if (!existingLink.get().discordId().equals(discordId)) {
                        currentPlayer.sendMessage(Component.text(
                                "This Minecraft identity is linked to a different Discord account.", NamedTextColor.RED));
                        return;
                    }
                    if (hasNameConflict(currentPlayer.getName(), playerId)) {
                        currentPlayer.sendMessage(Component.text(
                                "That Minecraft name is already linked to another identity. Contact an administrator.",
                                NamedTextColor.RED));
                        return;
                    }
                    try {
                        store.updatePlayerName(playerId, currentPlayer.getName());
                    } catch (IOException exception) {
                        reportStorageFailure(currentPlayer, "update the saved Minecraft name", exception);
                        return;
                    }
                    authorizeSession(currentPlayer, discordId);
                    return;
                }

                Optional<AuthStore.Link> existingPending = store.getPending(playerId);
                if (existingPending.isPresent()) {
                    if (hasNameConflict(currentPlayer.getName(), playerId)) {
                        currentPlayer.sendMessage(Component.text(
                                "That Minecraft name is already linked to another identity. Contact an administrator.",
                                NamedTextColor.RED));
                        return;
                    }
                    if (existingPending.get().discordId().equals(discordId)) {
                        pendingApprovalSessions.put(playerId, discordId);
                        currentPlayer.sendMessage(message("pending-approval"));
                    } else {
                        currentPlayer.sendMessage(Component.text(
                                "This Minecraft identity already has a pending Discord link.", NamedTextColor.RED));
                    }
                    return;
                }

                if (hasNameConflict(currentPlayer.getName(), playerId)) {
                    currentPlayer.sendMessage(Component.text(
                            "That Minecraft name is already linked to another identity. Contact an administrator.",
                            NamedTextColor.RED));
                    return;
                }
                AuthStore.Link newLink = new AuthStore.Link(playerId, currentPlayer.getName(), discordId,
                        Instant.now().toEpochMilli());
                if (getConfig().getBoolean("verification.require-first-link-approval", true)) {
                    try {
                        store.stageLink(newLink);
                    } catch (IOException exception) {
                        reportStorageFailure(currentPlayer, "save the pending account link", exception);
                        return;
                    }
                    pendingApprovalSessions.put(playerId, discordId);
                    currentPlayer.sendMessage(message("pending-approval"));
                    notifyAdmins("First-time Discord link waiting for approval: " + currentPlayer.getName()
                            + " (Discord ID " + discordId + "). Use /auth approve " + currentPlayer.getName() + ".");
                } else {
                    try {
                        store.linkImmediately(newLink);
                    } catch (IOException exception) {
                        reportStorageFailure(currentPlayer, "save the account link", exception);
                        return;
                    }
                    authorizeSession(currentPlayer, discordId);
                }
            });
        });
    }

    private boolean consumeAttempt(UUID playerId) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(30,
                getConfig().getLong("verification.attempt-window-seconds", 120)) * 1000L;
        int maxAttempts = Math.max(1, getConfig().getInt("verification.max-attempts-per-window", 8));
        AttemptWindow current = attemptWindows.get(playerId);
        if (current == null || now - current.startedAt() > windowMillis) {
            attemptWindows.put(playerId, new AttemptWindow(now, 1));
            return true;
        }
        if (current.attempts() >= maxAttempts) {
            return false;
        }
        attemptWindows.put(playerId, new AttemptWindow(current.startedAt(), current.attempts() + 1));
        return true;
    }

    private void authorizeSession(Player player, String discordId) {
        UUID playerId = player.getUniqueId();
        pendingApprovalSessions.remove(playerId);
        restrictedSessions.remove(playerId);
        authorizedSessions.put(playerId, discordId);
        eligibilityLastSuccess.put(playerId, System.currentTimeMillis());
        sessionStartedAt.remove(playerId);
        player.sendMessage(Component.text("✔ ", NamedTextColor.GREEN).append(message("verified")));
        getLogger().log(Level.INFO, "Verified Minecraft session for {0} through Discord.", player.getName());
    }

    private boolean hasNameConflict(String playerName, UUID playerId) {
        return store.findLinkByName(playerName).filter(link -> !link.playerId().equals(playerId)).isPresent()
                || store.findPendingByName(playerName).filter(link -> !link.playerId().equals(playerId)).isPresent();
    }

    private void reportStorageFailure(CommandSender sender, String operation, IOException exception) {
        getLogger().log(Level.SEVERE,
                "Could not " + operation + " in auth-data.yml; the request was not applied.", exception);
        sender.sendMessage(Component.text(
                "The server could not save your account data. Please contact an administrator and try again later.",
                NamedTextColor.RED));
    }

    private void notifyAdmins(String message) {
        for (Player online : getServer().getOnlinePlayers()) {
            if (online.hasPermission(ADMIN_PERMISSION)) {
                online.sendMessage(Component.text("[Auth] ", NamedTextColor.GOLD)
                        .append(Component.text(message)));
            }
        }
        getLogger().info(message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            if (!gateActive) {
                player.sendMessage(Component.text("Discord admission is currently disabled.", NamedTextColor.RED));
                return true;
            }
            if (isRestricted(player)) {
                if (args.length == 1 && args[0].matches("\\d{6}")) {
                    requireAuthenticationCode(player, args[0]);
                } else {
                    player.sendMessage(message("restricted"));
                }
                return true;
            }
            if (isAdminBypass(player)) {
                if (args.length == 1 && args[0].matches("\\d{6}")) {
                    player.sendMessage(Component.text(
                            "Operators and auth admins are exempt from Discord verification.", NamedTextColor.GRAY));
                    return true;
                }
            } else if (!authorizedSessions.containsKey(player.getUniqueId())) {
                player.sendMessage(Component.text("You must complete Discord verification first.", NamedTextColor.RED));
                return true;
            }
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("Use /auth <Discord code> to verify a session.", NamedTextColor.AQUA));
            if (!(sender instanceof Player) || sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage(Component.text(
                        "Admin: /auth pending|approve <name>|deny <name>|unlink <name>", NamedTextColor.GRAY));
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!Set.of("pending", "approve", "deny", "unlink").contains(subcommand)) {
            sender.sendMessage(Component.text("Use /auth <Discord code>.", NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission to manage account links.", NamedTextColor.RED));
            return true;
        }
        if (sender instanceof Player player && !isAdminBypass(player)
                && !authorizedSessions.containsKey(player.getUniqueId())) {
            sender.sendMessage(Component.text("Verify your own session before using admin auth commands.", NamedTextColor.RED));
            return true;
        }
        return handleAdminCommand(sender, subcommand, args);
    }

    private boolean isAdminBypass(Player player) {
        return player.isOp() || player.hasPermission(ADMIN_PERMISSION);
    }

    private boolean handleAdminCommand(CommandSender sender, String subcommand, String[] args) {
        if (subcommand.equals("pending")) {
            List<AuthStore.Link> pending = store.pendingLinks();
            if (pending.isEmpty()) {
                sender.sendMessage(Component.text("There are no pending identity links.", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Pending identity links:", NamedTextColor.GOLD));
                pending.stream()
                        .sorted(Comparator.comparing(
                                link -> Objects.requireNonNullElse(link.playerName(), ""),
                                String.CASE_INSENSITIVE_ORDER))
                        .forEach(link -> {
                            String playerName = Objects.requireNonNullElse(link.playerName(), "<unknown>");
                            sender.sendMessage(Component.text(playerName, NamedTextColor.YELLOW)
                                    .append(Component.text(" — Discord ID " + link.discordId(), NamedTextColor.GRAY)));
                        });
            }
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(Component.text("Usage: /auth " + subcommand + " <player-name>", NamedTextColor.RED));
            return true;
        }

        String name = args[1];
        if (subcommand.equals("approve")) {
            Optional<AuthStore.Link> candidate = store.findPendingByName(name);
            if (candidate.isEmpty()) {
                sender.sendMessage(Component.text("No pending link found for " + name + ".", NamedTextColor.RED));
                return true;
            }
            AuthStore.Link link = candidate.get();
            if (discord == null || !discord.isReady()) {
                sender.sendMessage(Component.text(
                        "Discord verification is unavailable; approval was not saved.", NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text(
                    "Rechecking Discord eligibility before approval...", NamedTextColor.GRAY));
            discord.checkEligibility(link.discordId(), result -> {
                if (!isEnabled()) {
                    return;
                }
                getServer().getScheduler().runTask(this, () -> {
                    if (store.getPending(link.playerId()).filter(link::equals).isEmpty()) {
                        sender.sendMessage(Component.text("That pending request has changed.", NamedTextColor.RED));
                        return;
                    }
                    if (!result.available()) {
                        sender.sendMessage(Component.text(
                                "Discord could not be reached. The request remains pending.", NamedTextColor.RED));
                        return;
                    }
                    if (!result.eligible()) {
                        try {
                            store.deny(link.playerId());
                        } catch (IOException exception) {
                            reportStorageFailure(sender, "remove an ineligible pending link", exception);
                            return;
                        }
                        pendingApprovalSessions.remove(link.playerId());
                        sender.sendMessage(Component.text(
                                "That Discord account is no longer eligible; the request was removed.", NamedTextColor.RED));
                        return;
                    }
                    try {
                        if (!store.approve(link.playerId())) {
                            sender.sendMessage(Component.text(
                                    "That pending request has changed; no link was approved.", NamedTextColor.RED));
                            return;
                        }
                    } catch (IOException exception) {
                        reportStorageFailure(sender, "approve the account link", exception);
                        return;
                    }
                    Player pendingPlayer = getServer().getPlayer(link.playerId());
                    boolean sameSession = link.discordId().equals(pendingApprovalSessions.get(link.playerId()));
                    if (pendingPlayer != null && sameSession && restrictedSessions.contains(link.playerId())) {
                        authorizeSession(pendingPlayer, link.discordId());
                    }
                    sender.sendMessage(Component.text(
                            "Approved the link for " + link.playerName() + ".", NamedTextColor.GREEN));
                });
            });
            return true;
        }

        if (subcommand.equals("deny")) {
            Optional<AuthStore.Link> candidate = store.findPendingByName(name);
            Optional<AuthStore.Link> removed;
            try {
                removed = candidate.isPresent() ? store.deny(candidate.get().playerId()) : Optional.empty();
            } catch (IOException exception) {
                reportStorageFailure(sender, "deny the pending account link", exception);
                return true;
            }
            if (removed.isEmpty()) {
                sender.sendMessage(Component.text("No pending link found for " + name + ".", NamedTextColor.RED));
                return true;
            }
            pendingApprovalSessions.remove(removed.get().playerId());
            Player pendingPlayer = getServer().getPlayer(removed.get().playerId());
            if (pendingPlayer != null) {
                pendingPlayer.kick(Component.text("Your first-time identity link was not approved."));
            }
            sender.sendMessage(Component.text(
                    "Denied the pending link for " + removed.get().playerName() + ".", NamedTextColor.GREEN));
            return true;
        }

        Optional<AuthStore.Link> removed;
        try {
            removed = store.unlinkByName(name);
        } catch (IOException exception) {
            reportStorageFailure(sender, "remove the saved account link", exception);
            return true;
        }
        if (removed.isEmpty()) {
            sender.sendMessage(Component.text("No saved link found for " + name + ".", NamedTextColor.RED));
            return true;
        }
        Player linkedPlayer = getServer().getPlayer(removed.get().playerId());
        if (linkedPlayer != null) {
            authorizedSessions.remove(removed.get().playerId());
            eligibilityLastSuccess.remove(removed.get().playerId());
            linkedPlayer.kick(Component.text("Your Minecraft identity was unlinked. Rejoin to link it again."));
        }
        sender.sendMessage(Component.text("Unlinked " + removed.get().playerName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(ADMIN_PERMISSION)) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("pending", "approve", "deny", "unlink").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && sender.hasPermission(ADMIN_PERMISSION)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            if (sub.equals("approve") || sub.equals("deny")) {
                store.pendingLinks().forEach(link -> names.add(link.playerName()));
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return names.stream().distinct()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
