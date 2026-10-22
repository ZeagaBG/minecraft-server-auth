package community.auth.gate;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.GatewayIntent;
import javax.annotation.Nonnull;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

final class DiscordAuthService extends ListenerAdapter implements AutoCloseable {
    record CodeClaim(String discordId) {
    }

    record EligibilityResult(boolean eligible, boolean available) {
        static EligibilityResult pass() {
            return new EligibilityResult(true, true);
        }

        static EligibilityResult fail() {
            return new EligibilityResult(false, true);
        }

        static EligibilityResult unavailableResult() {
            return new EligibilityResult(false, false);
        }
    }

    private record PendingCode(String discordId, long expiresAt) {
    }

    private static final int CODE_LENGTH = 6;

    private final CommunityAuthGate plugin;
    private final String guildId;
    private final int codeLifetimeSeconds;
    private final boolean allowAnyGuildMember;
    private final Set<String> requiredAnyRoleIds;
    private final Set<String> requiredAllRoleIds;
    private final Set<String> minecraftCommandRoleIds;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingCode> codes = new HashMap<>();
    private final Map<String, String> activeCodeByDiscordId = new HashMap<>();
    private volatile JDA jda;
    private volatile boolean ready;

    DiscordAuthService(CommunityAuthGate plugin) {
        this.plugin = plugin;
        this.guildId = Objects.requireNonNullElse(plugin.getConfig().getString("discord.guild-id"), "");
        this.codeLifetimeSeconds = Math.max(60,
                plugin.getConfig().getInt("verification.code-lifetime-seconds", 300));
        this.allowAnyGuildMember = plugin.getConfig().getBoolean(
                "discord.eligibility.allow-any-guild-member", false);
        this.requiredAnyRoleIds = stringSet(
                plugin.getConfig().getStringList("discord.eligibility.required-any-role-ids"));
        this.requiredAllRoleIds = stringSet(
                plugin.getConfig().getStringList("discord.eligibility.required-all-role-ids"));
        this.minecraftCommandRoleIds = stringSet(
                plugin.getConfig().getStringList("discord.minecraft-command-role-ids"));
    }

    void start(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Discord bot token must not be blank.");
        }
        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(this)
                .build();
    }

    boolean isReady() {
        return ready;
    }

    @Override
    public void onReady(@Nonnull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().log(Level.SEVERE, "Discord bot is not in configured guild {0}.", guildId);
            return;
        }
        Set<String> eligibilityRoleIds = new HashSet<>(requiredAnyRoleIds);
        eligibilityRoleIds.addAll(requiredAllRoleIds);
        Set<String> configuredRoleIds = new HashSet<>(eligibilityRoleIds);
        configuredRoleIds.addAll(minecraftCommandRoleIds);
        for (String roleId : configuredRoleIds) {
            if (guild.getRoleById(roleId) == null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Configured Discord role {0} does not exist in guild {1}; check role ID settings.",
                        new Object[]{roleId, guildId});
            }
        }
        if (eligibilityRoleIds.isEmpty() && !allowAnyGuildMember) {
            plugin.getLogger().severe(
                    "No Discord eligibility roles are configured and allow-any-guild-member is false; nobody can verify.");
        }
        if (minecraftCommandRoleIds.isEmpty()) {
            plugin.getLogger().warning(
                    "Discord /mc is locked: configure discord.minecraft-command-role-ids with trusted role IDs.");
        }
        guild.upsertCommand(Commands.slash(
                        "mc-verify", "Create a one-time Minecraft verification code"))
                .flatMap(ignored -> guild.upsertCommand(
                        Commands.slash("mc", "Run a Minecraft server command")
                                .addOption(OptionType.STRING, "command",
                                        "Minecraft command to run, without the leading slash", true)))
                .queue(ignored -> {
                    ready = true;
                    plugin.getLogger().log(Level.INFO, "Discord bot is ready for guild {0}.", guildId);
                }, error -> plugin.getLogger().log(Level.SEVERE,
                        "Could not register the /mc-verify Discord command", error));
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (event.getName().equals("mc")) {
            handleMinecraftCommand(event);
            return;
        }
        if (!event.getName().equals("mc-verify")) {
            return;
        }
        Guild eventGuild = event.getGuild();
        if (eventGuild == null || !eventGuild.getId().equals(guildId)) {
            replyEphemerally(event, "Use this command in the configured community server.");
            return;
        }
        Member member = event.getMember();
        if (member == null || !isEligible(member)) {
            replyEphemerally(event,
                    "Your Discord account does not currently meet the server's access requirements.");
            return;
        }

        event.deferReply(true).queue(hook -> event.getUser().openPrivateChannel().queue(
                privateChannel -> {
                    String code = issueCode(event.getUser().getId());
                    privateChannel.sendMessage("Minecraft verification code: **" + code + "**\n"
                                    + "It expires in " + codeLifetimeSeconds
                                    + " seconds. Enter it in Minecraft as `/auth " + code + "`.")
                            .queue(ignored -> hook.editOriginal("I sent your verification code in a DM.").queue(),
                                    error -> {
                                        discardCode(code);
                                        plugin.getLogger().log(Level.WARNING,
                                                "Could not DM a Minecraft verification code.", error);
                                        hook.editOriginal("I couldn't DM you. Enable direct messages from server members, "
                                                        + "then run `/mc-verify` again.")
                                                .queue();
                                    });
                }, error -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not open a DM channel for a Minecraft verification code.", error);
                    hook.editOriginal("I couldn't DM you. Enable direct messages from server members, "
                                    + "then run `/mc-verify` again.")
                            .queue();
                }), error -> plugin.getLogger().log(Level.WARNING,
                "Could not acknowledge the Discord verification command.", error));
    }

    private void handleMinecraftCommand(SlashCommandInteractionEvent event) {
        Guild eventGuild = event.getGuild();
        if (eventGuild == null || !eventGuild.getId().equals(guildId)) {
            replyEphemerally(event, "Use this command in the configured community server.");
            return;
        }

        Member member = event.getMember();
        if (member == null || minecraftCommandRoleIds.isEmpty()
                || member.getRoles().stream().noneMatch(role -> minecraftCommandRoleIds.contains(role.getId()))) {
            replyEphemerally(event, "You do not have a role allowed to run Minecraft commands.");
            return;
        }

        var commandOption = event.getOption("command");
        String command = commandOption == null ? "" : commandOption.getAsString().strip();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isBlank() || command.length() > 256
                || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\0') >= 0) {
            replyEphemerally(event, "Enter one Minecraft command, up to 256 characters, without line breaks.");
            return;
        }

        String finalCommand = command;
        String discordName = member.getEffectiveName().replaceAll("[\\r\\n\\t]", " ");
        event.deferReply(true).queue(hook -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) {
                        hook.editOriginal("The Minecraft plugin is shutting down; the command was not run.").queue();
                        return;
                    }

                    boolean handled;
                    try {
                        handled = plugin.getServer().dispatchCommand(
                                plugin.getServer().getConsoleSender(), finalCommand);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Minecraft command from Discord failed: /" + finalCommand, exception);
                        hook.editOriginal("The Minecraft command failed. Check the server console for details.").queue();
                        return;
                    }
                    if (!handled) {
                        hook.editOriginal("Minecraft did not recognize that command; nothing was announced in game.").queue();
                        return;
                    }

                    plugin.getServer().broadcast(net.kyori.adventure.text.Component.text(
                            "[" + discordName + "]: /" + finalCommand));
                    plugin.getLogger().info("Discord user " + discordName
                            + " ran Minecraft command: /" + finalCommand);
                    hook.editOriginal("Ran `" + finalCommand + "` on the Minecraft server.").queue();
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not schedule a Minecraft command requested through Discord.", exception);
                hook.editOriginal("The Minecraft server could not schedule that command.").queue();
            }
        }, error -> plugin.getLogger().log(Level.WARNING,
                "Could not acknowledge a Discord Minecraft command.", error));
    }

    @Override
    public void onGuildMemberUpdate(@Nonnull GuildMemberUpdateEvent event) {
        Guild eventGuild = event.getGuild();
        if (eventGuild != null && eventGuild.getId().equals(guildId)) {
            plugin.onDiscordMemberChanged(event.getUser().getId());
        }
    }

    @Override
    public void onGuildMemberRemove(@Nonnull GuildMemberRemoveEvent event) {
        Guild eventGuild = event.getGuild();
        if (eventGuild != null && eventGuild.getId().equals(guildId)) {
            plugin.onDiscordMemberRemoved(event.getUser().getId());
        }
    }

    synchronized Optional<CodeClaim> consumeCode(String rawCode) {
        String code = rawCode.toUpperCase(Locale.ROOT);
        PendingCode pending = codes.remove(code);
        if (pending == null) {
            return Optional.empty();
        }
        activeCodeByDiscordId.remove(pending.discordId(), code);
        if (pending.expiresAt() <= System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(new CodeClaim(pending.discordId()));
    }

    void checkEligibility(String discordId, Consumer<EligibilityResult> callback) {
        if (discordId == null || discordId.isBlank()) {
            callback.accept(EligibilityResult.unavailableResult());
            return;
        }
        JDA currentJda = jda;
        Guild guild = currentJda == null ? null : currentJda.getGuildById(guildId);
        if (!ready || guild == null) {
            callback.accept(EligibilityResult.unavailableResult());
            return;
        }
        guild.retrieveMemberById(discordId).queue(
                member -> callback.accept(member == null
                        ? EligibilityResult.unavailableResult()
                        : isEligible(member) ? EligibilityResult.pass() : EligibilityResult.fail()),
                error -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Discord eligibility lookup failed; treating the result as unavailable.", error);
                    callback.accept(EligibilityResult.unavailableResult());
                });
    }

    private void replyEphemerally(SlashCommandInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).queue(
                ignored -> {
                },
                error -> plugin.getLogger().log(Level.WARNING,
                        "Could not send a response to the Discord verification command.", error));
    }

    private synchronized String issueCode(String discordId) {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        activeCodeByDiscordId.entrySet().removeIf(entry -> !codes.containsKey(entry.getValue()));
        String oldCode = activeCodeByDiscordId.remove(discordId);
        if (oldCode != null) {
            codes.remove(oldCode);
        }
        String code;
        do {
            code = String.format(Locale.ROOT, "%0" + CODE_LENGTH + "d", random.nextInt(1_000_000));
        } while (codes.containsKey(code));

        codes.put(code, new PendingCode(discordId,
                System.currentTimeMillis() + codeLifetimeSeconds * 1000L));
        activeCodeByDiscordId.put(discordId, code);
        return code;
    }

    private synchronized void discardCode(String code) {
        PendingCode pending = codes.remove(code);
        if (pending != null) {
            activeCodeByDiscordId.remove(pending.discordId(), code);
        }
    }

    private boolean isEligible(Member member) {
        List<String> memberRoleIds = new ArrayList<>();
        for (Role role : member.getRoles()) {
            memberRoleIds.add(role.getId());
        }
        Set<String> memberRoles = new HashSet<>(memberRoleIds);

        if (!memberRoles.containsAll(requiredAllRoleIds)) {
            return false;
        }
        if (!requiredAnyRoleIds.isEmpty()
                && requiredAnyRoleIds.stream().noneMatch(memberRoles::contains)) {
            return false;
        }
        if (!requiredAnyRoleIds.isEmpty() || !requiredAllRoleIds.isEmpty()) {
            return true;
        }
        return allowAnyGuildMember;
    }

    private static Set<String> stringSet(List<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public void close() {
        ready = false;
        JDA currentJda = jda;
        jda = null;
        if (currentJda != null) {
            currentJda.shutdownNow();
        }
    }
}
