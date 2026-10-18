package community.auth.gate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

final class AuthStore {
    record Link(UUID playerId, String playerName, String discordId, long linkedAt) {
    }

    private final CommunityAuthGate plugin;
    private final File file;
    private final Map<UUID, Link> links = new LinkedHashMap<>();
    private final Map<UUID, Link> pending = new LinkedHashMap<>();

    AuthStore(CommunityAuthGate plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth-data.yml");
        load();
    }

    synchronized Optional<Link> getLink(UUID playerId) {
        return Optional.ofNullable(links.get(playerId));
    }

    synchronized Optional<Link> getPending(UUID playerId) {
        return Optional.ofNullable(pending.get(playerId));
    }

    synchronized Optional<Link> findLinkByName(String name) {
        return links.values().stream()
                .filter(link -> link.playerName().equalsIgnoreCase(name))
                .findFirst();
    }

    synchronized Optional<Link> findPendingByName(String name) {
        return pending.values().stream()
                .filter(link -> link.playerName().equalsIgnoreCase(name))
                .findFirst();
    }

    synchronized List<Link> pendingLinks() {
        return new ArrayList<>(pending.values());
    }

    synchronized void stageLink(Link link) {
        pending.put(link.playerId(), link);
        save();
    }

    synchronized void approve(UUID playerId) {
        Link link = pending.remove(playerId);
        if (link != null) {
            links.put(playerId, link);
            save();
        }
    }

    synchronized void updatePlayerName(UUID playerId, String playerName) {
        Link link = links.get(playerId);
        if (link != null && !link.playerName().equals(playerName)) {
            links.put(playerId, new Link(playerId, playerName, link.discordId(), link.linkedAt()));
            save();
        }
    }

    synchronized Optional<Link> deny(UUID playerId) {
        Link removed = pending.remove(playerId);
        if (removed != null) {
            save();
        }
        return Optional.ofNullable(removed);
    }

    synchronized Optional<Link> unlinkByName(String name) {
        Optional<Link> found = findLinkByName(name);
        found.ifPresent(link -> {
            links.remove(link.playerId());
            save();
        });
        return found;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        readSection(yaml.getConfigurationSection("links"), links);
        readSection(yaml.getConfigurationSection("pending"), pending);
    }

    private void readSection(ConfigurationSection section, Map<UUID, Link> destination) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                UUID playerId = UUID.fromString(key);
                String name = entry.getString("player-name");
                String discordId = entry.getString("discord-id");
                long linkedAt = entry.getLong("linked-at");
                if (name != null && discordId != null) {
                    destination.put(playerId, new Link(playerId, name, discordId, linkedAt));
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Ignoring invalid identity key in auth-data.yml: {0}", key);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        writeSection(yaml, "links", links);
        writeSection(yaml, "pending", pending);
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new IOException("Could not create plugin data directory");
            }
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save auth-data.yml", exception);
        }
    }

    private void writeSection(YamlConfiguration yaml, String sectionName, Map<UUID, Link> values) {
        for (Link link : values.values()) {
            String path = sectionName + "." + link.playerId();
            yaml.set(path + ".player-name", link.playerName());
            yaml.set(path + ".discord-id", link.discordId());
            yaml.set(path + ".linked-at", link.linkedAt());
        }
    }
}
