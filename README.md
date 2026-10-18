# Community Auth Gate

Paper plugin with an embedded Discord bot for a mixed Java and Bedrock server. It targets Paper 26.3 build 175 and uses Geyser/Floodgate's player UUIDs as presented by Paper. Keep the server in offline mode for TLauncher compatibility and set Geyser's `auth-type` to `floodgate` for Bedrock accounts without Java Edition.

## How admission works

1. Every new Minecraft session starts restricted. It cannot move, chat, use commands, interact with the world, or affect other players. Sessions time out after the configured period.
2. An eligible Discord member runs `/mc-verify` in the configured Discord server. The bot responds ephemerally with a random, single-use code that expires after five minutes.
3. The player enters `/auth <code>` in Minecraft. The plugin checks the code, then independently rechecks guild membership and configured roles.
4. On the first successful link, the Minecraft UUID and Discord user ID are saved as a pending link. A moderator approves it once. Later sessions must verify with the same Discord account.
5. Discord role or membership changes revoke an active session. The plugin listens for member events and also polls active identities every minute.

The plugin is the live admission gate; it does not write to `whitelist.json`. Turn the vanilla whitelist off so it does not block new players before they can verify. The Paper `online-mode=false` setting remains unchanged.

Offline Java usernames are not authenticated by Mojang. First-link moderator approval prevents an unknown player from silently claiming an existing member's name, and requiring the linked Discord account on every session prevents someone from reusing that name alone. This does not stop connection-flooding at the public tunnel, and it cannot prove Java account ownership while the server is in offline mode.

## Build

Build with JDK 25 or newer and Gradle:

```powershell
gradle clean build
```

The plugin jar is `build/libs/community-auth-gate-0.1.0.jar`. JDA is bundled in the jar; Paper is a provided API dependency.

## Discord setup

1. Create a Discord application and bot in the Discord Developer Portal. Copy the bot token once and keep it private.
2. Enable **Server Members Intent** under the bot's privileged gateway intents. The bot needs this to receive role/removal events and revoke access promptly.
3. Invite the bot to the community server with the `bot` and `applications.commands` scopes. It needs no message or administrator permissions.
4. Enable Developer Mode in Discord and copy the server ID and the role IDs used for eligibility.
5. Set `COMMUNITY_AUTH_DISCORD_TOKEN` as a Windows user environment variable, then restart the server process. Do not put the bot token in `config.yml` or commit it.

## Server setup

1. Copy the jar into `plugins/` and start the server once to generate `plugins/CommunityAuthGate/config.yml`.
2. Set `discord.guild-id` to the Discord server ID. Fill in `required-any-role-ids` and/or `required-all-role-ids` with quoted role IDs. If both lists are empty, nobody qualifies unless `allow-any-guild-member` is set to `true`.
   - A member must have at least one role in `required-any-role-ids` when that list is populated.
   - A member must have every role in `required-all-role-ids`.
   - If both are populated, both checks apply.
3. Confirm `online-mode=false`, `auth-type: floodgate`, and that Geyser-Spigot and Floodgate-Spigot are installed. Keep Floodgate's username prefix enabled so Java and Bedrock names cannot collide. Turn the vanilla whitelist off.
4. Set `enabled: true` only after the Discord token, guild ID, and eligibility rules are configured. Restart the server.
5. Test with one Java player and one Bedrock player before sharing the join address widely.

## First-time moderator approval

First-time account links are pending by default because offline-mode names can be claimed. Have the player request a code, join, and run `/auth <code>`. Review requests with `/auth pending`, then approve or deny with `/auth approve <player>` or `/auth deny <player>`. A console operator can run these commands if no moderator has linked an account yet. Use `/auth unlink <player>` to remove a saved link.

Player moderators need the `communityauth.admin` permission **and** a verified Discord session. Console commands do not require a player session.

## Stored data and secrets

`plugins/CommunityAuthGate/auth-data.yml` stores Minecraft UUIDs, player names, Discord user IDs, and link timestamps. It does not store codes or bot tokens. Back it up with the server and restrict access to the server folder. Never share Floodgate's `key.pem`.
