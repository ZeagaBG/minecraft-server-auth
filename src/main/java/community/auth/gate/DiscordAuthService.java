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

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;

    private final CommunityAuthGate plugin;
    private final String guildId;
    private final int codeLifetimeSeconds;
    private final boolean allowAnyGuildMember;
    private final Set<String> requiredAnyRoleIds;
    private final Set<String> requiredAllRoleIds;
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
        guild.updateCommands()
                .addCommands(Commands.slash("mc-verify", "Create a one-time Minecraft verification code"))
                .queue(ignored -> {
                    ready = true;
                    plugin.getLogger().log(Level.INFO, "Discord bot is ready for guild {0}.", guildId);
                }, error -> plugin.getLogger().log(Level.SEVERE,
                        "Could not register the /mc-verify Discord command", error));
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("mc-verify")) {
            return;
        }
        Guild eventGuild = event.getGuild();
        if (eventGuild == null || !eventGuild.getId().equals(guildId)) {
            event.reply("Use this command in the configured community server.")
                    .setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (member == null || !isEligible(member)) {
            event.reply("Your Discord account does not currently meet the server's access requirements.")
                    .setEphemeral(true).queue();
            return;
        }

        String code = issueCode(event.getUser().getId());
        event.reply("Minecraft verification code: **" + code + "**\n"
                        + "It expires in " + codeLifetimeSeconds + " seconds. Enter it in Minecraft as `/auth " + code + "`.")
                .setEphemeral(true).queue();
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
                error -> callback.accept(EligibilityResult.unavailableResult()));
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
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (codes.containsKey(code));

        codes.put(code, new PendingCode(discordId,
                System.currentTimeMillis() + codeLifetimeSeconds * 1000L));
        activeCodeByDiscordId.put(discordId, code);
        return code;
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
