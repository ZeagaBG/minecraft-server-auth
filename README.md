# Community Auth Gate

Community Auth Gate is a Paper plugin that requires players to verify through a Discord server before they can play. It is intended for mixed Java/Bedrock servers that use offline mode with Geyser and Floodgate.

> **Important:** Offline mode does not authenticate Java accounts with Mojang. Discord verification links a Minecraft identity to a Discord account, but it cannot prove ownership of a Java account. First-link moderator approval helps reduce identity-claiming risk; it does not eliminate the limitations of offline mode.

## Requirements

- Paper 26.3 build 175, or a compatible build
- Java 25 or newer
- Gradle to build the plugin
- A Discord bot with the **Server Members Intent**
- For Bedrock players: Geyser-Spigot and Floodgate-Spigot, with Geyser `auth-type: floodgate`

Keep Floodgate's username prefix enabled to help avoid Java/Bedrock name collisions. The plugin uses the UUID Paper provides; it does not manage `whitelist.json`.

## Player verification flow

1. A joining player starts in a restricted state. Movement, chat, commands other than the verification command, and common interactions are blocked. The session is disconnected if it exceeds the configured authentication timeout.
2. The player runs `/mc-verify` in the configured Discord server. The bot responds privately with a random, single-use code.
3. The player enters `/auth <code>` in Minecraft. The code expires, is consumed once, and the plugin checks current guild membership and role eligibility.
4. A first-time link is saved as pending by default. A moderator reviews it and approves or denies it. On later sessions, the Minecraft identity must verify with the same Discord account.
5. Active sessions are rechecked for Discord eligibility. Losing membership or required roles revokes access. When a check reports Discord as unavailable, `verification.eligibility-check-failure-grace-seconds` controls how long an active session may remain authorized after its last successful check.

The plugin limits the number of unauthenticated sessions and the number of code attempts per player. These safeguards do **not** prevent connection floods against the server or its public tunnel.

## Build

From the repository root, with JDK 25 and Gradle available:

```powershell
gradle clean build
```

The plugin JAR is `build/libs/community-auth-gate-0.1.0.jar`. JDA is bundled in the JAR; the Paper API is provided by the server at runtime. This repository does not include a Gradle wrapper.

## Discord setup

1. Create a Discord application and bot in the Discord Developer Portal.
2. Enable **Server Members Intent** in the bot's privileged gateway intents. The bot needs it to receive member and role changes.
3. Invite the bot to your server with the `bot` and `applications.commands` scopes. It does not need administrator or message permissions.
4. Enable Developer Mode in Discord and copy the server ID and the IDs of the roles that qualify players.
5. Provide the bot token to the Minecraft server process as the `COMMUNITY_AUTH_DISCORD_TOKEN` environment variable. Restart the server after setting it. Never put the token in this repository or in `config.yml`.

If a bot token has ever been committed or exposed, revoke it in the Discord Developer Portal and create a replacement. Removing it from the current files does not remove it from Git history.

## Install and configure

1. Copy the JAR into the server's `plugins/` directory and start the server once. Paper creates `plugins/CommunityAuthGate/config.yml`.
2. Set `discord.guild-id` to your Discord server ID. Configure role IDs in `required-any-role-ids` and/or `required-all-role-ids`:
   - When `required-any-role-ids` is non-empty, a member must have at least one listed role.
   - A member must have every role in `required-all-role-ids`.
   - If both lists are populated, both rules apply.
   - If both lists are empty, nobody qualifies unless `allow-any-guild-member: true`.
3. Check the server setup:
   - Set `online-mode=false` only if that is an intentional server requirement.
   - For Bedrock support, install Geyser-Spigot and Floodgate-Spigot and set Geyser's `auth-type` to `floodgate`.
   - Turn off Paper's vanilla whitelist; otherwise a player not already whitelisted may be rejected before they can use `/auth`.
4. Verify the Discord token is available to the server process. Then set `enabled: true` in the plugin config and restart the server.
5. Test the complete flow with a Java player and a Bedrock player before sharing the address.

The shipped config starts with `enabled: false`. Leave it disabled until the Discord server ID, bot token, and eligibility rules are configured.

## Configuration reference

| Setting | Default | Purpose |
|---|---:|---|
| `enabled` | `false` | Turns the admission gate on or off. |
| `discord.guild-id` | Example ID | Discord server the bot uses for verification. Replace this value. |
| `discord.token-environment-variable` | `COMMUNITY_AUTH_DISCORD_TOKEN` | Name of the environment variable containing the bot token. |
| `discord.eligibility.allow-any-guild-member` | `false` | Allows any guild member only when no role lists are configured. |
| `discord.eligibility.required-any-role-ids` | `[]` | Member must have at least one listed role when populated. |
| `discord.eligibility.required-all-role-ids` | Example role ID | Member must have all listed roles. Replace this value. |
| `verification.code-lifetime-seconds` | `300` | Code lifetime; the plugin enforces a minimum of 60 seconds. |
| `verification.max-attempts-per-window` | `8` | Maximum code submissions allowed per player in an attempt window. |
| `verification.attempt-window-seconds` | `120` | Code-attempt rate-limit window; the plugin enforces a minimum of 30 seconds. |
| `verification.require-first-link-approval` | `true` | Holds new identity links for moderator approval. |
| `verification.eligibility-recheck-seconds` | `60` | Interval for fallback eligibility checks; the plugin enforces a minimum of 30 seconds. |
| `verification.eligibility-check-failure-grace-seconds` | `300` | How long an active session may remain authorized after its last successful check when a check reports Discord unavailable. |
| `verification.authentication-timeout-seconds` | `180` | Time allowed to finish verification; the plugin enforces a minimum of 30 seconds. |
| `verification.max-unauthenticated-players` | `8` | Maximum number of sessions allowed to wait for authentication. |

The plugin reads timing and rate-limit settings at startup. Restart the server after changing the configuration.

## Moderator commands

| Command | Description |
|---|---|
| `/auth pending` | Lists first-time links waiting for review. |
| `/auth approve <player>` | Rechecks the player's Discord eligibility, then approves the pending link. |
| `/auth deny <player>` | Removes a pending link and disconnects that player if online. |
| `/auth unlink <player>` | Removes a saved link and disconnects that player if online. |

Players need `communityauth.admin` **and** a verified session to use moderator commands. Console operators do not need a player session and can perform initial approvals if no moderator has linked an account yet.

## Data, errors, and recovery

`plugins/CommunityAuthGate/auth-data.yml` contains Minecraft UUIDs, player names, Discord user IDs, and link timestamps. It does not contain bot tokens or verification codes.

The plugin writes updates through a temporary file before replacing the data file. If data cannot be loaded at startup, the plugin disables itself rather than continuing with missing account links. If a save fails, the requested change is not applied or reported as successful; check the server log and filesystem permissions before retrying. Back up the server folder, including this data file, and restrict access to it.

If verification is unavailable, check the server log, bot token environment variable, configured guild and role IDs, bot membership in the guild, and the **Server Members Intent** setting. The bot must be running and connected for players to obtain and redeem codes. Do not share Floodgate's `key.pem`.

## Known limitations

- Offline-mode Java usernames are not authenticated by Mojang; use a server mode and identity setup appropriate for your threat model.
- Discord outages can interrupt new verification and eligibility checks.
- The plugin is not a network-level DDoS or connection-flood defense.
- Test role changes, Discord outages, approval/denial, backups, and server restarts on a staging server before relying on it for a public server.
