# Community Auth Gate v1.0.0

Community Auth Gate is a Paper plugin that gates player access through Discord membership and role checks. It is designed for servers that need Java and Bedrock players to join through Paper with Geyser and Floodgate.

## Features

- Discord slash command `/mc-verify` sends a single-use six-digit verification code to the player's Discord DMs.
- Players enter the code in Minecraft with `/auth <code>`.
- Discord guild membership and configured role requirements are checked during verification and rechecked while the player is online.
- First-time Minecraft-to-Discord account links can require moderator approval.
- Operators and players with `communityauth.admin` bypass player verification and can use moderator commands.
- Moderators can review pending links and approve, deny, or unlink accounts with `/auth` commands.
- Unauthenticated sessions are restricted and subject to configurable timeouts and attempt limits.
- An optional startup check reports when a newer stable GitHub release is available. Updates are installed manually; the plugin does not replace its own JAR.

## Requirements

- Paper 26.3 build 175 or a compatible build
- Java 25 or newer
- A Discord bot with the Server Members Intent enabled
- Geyser-Spigot and Floodgate-Spigot for Bedrock support; configure Geyser with `auth-type: floodgate`

## Installation

1. Download `community-auth-gate-1.0.0.jar` and place it in the server's `plugins/` folder.
2. Start Paper once to generate `plugins/CommunityAuthGate/config.yml`.
3. Set the Discord guild ID and qualifying role IDs in that config. Keep the bot token out of the config and provide it to the Paper process using the configured environment variable (default: `COMMUNITY_AUTH_DISCORD_TOKEN`).
4. Invite the bot with the `bot` and `applications.commands` scopes, enable Server Members Intent, and ensure it is in the configured Discord server.
5. After configuration, set `enabled: true` and restart Paper. Test Java and Bedrock connections before opening the server to players.

See [README.md](README.md) for the full setup guide, configuration reference, and moderator command details.

## Important limitations

- With `online-mode=false`, Minecraft does not authenticate Java accounts with Mojang. Discord verification does not prove ownership of a Java account.
- The plugin is an admission gate; it does not prevent connection floods or DDoS attacks against the server or its tunnel.
- Discord must be reachable for new verification and eligibility checks.

Back up the server data before installing or updating plugins. Account links are stored in `plugins/CommunityAuthGate/auth-data.yml`.
