package community.auth.gate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class AuthStore {
    record Link(UUID playerId, String playerName, String discordId, long linkedAt) {
    }

    private final CommunityAuthGate plugin;
    private final File file;
    private final Map<UUID, Link> links = new LinkedHashMap<>();
    private final Map<UUID, Link> pending = new LinkedHashMap<>();

    AuthStore(CommunityAuthGate plugin) throws IOException, InvalidConfigurationException {
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

    synchronized void stageLink(Link link) throws IOException {
        Map<UUID, Link> newPending = new LinkedHashMap<>(pending);
        newPending.put(link.playerId(), link);
        persist(links, newPending);
        pending.clear();
        pending.putAll(newPending);
    }

    synchronized boolean approve(UUID playerId) throws IOException {
        Link link = pending.get(playerId);
        if (link == null) {
            return false;
        }
        Map<UUID, Link> newLinks = new LinkedHashMap<>(links);
        Map<UUID, Link> newPending = new LinkedHashMap<>(pending);
        newPending.remove(playerId);
        newLinks.put(playerId, link);
        persist(newLinks, newPending);
        links.clear();
        links.putAll(newLinks);
        pending.clear();
        pending.putAll(newPending);
        return true;
    }

    synchronized void linkImmediately(Link link) throws IOException {
        Map<UUID, Link> newLinks = new LinkedHashMap<>(links);
        newLinks.put(link.playerId(), link);
        persist(newLinks, pending);
        links.clear();
        links.putAll(newLinks);
    }

    synchronized void updatePlayerName(UUID playerId, String playerName) throws IOException {
        Link link = links.get(playerId);
        if (link != null && !link.playerName().equals(playerName)) {
            Map<UUID, Link> newLinks = new LinkedHashMap<>(links);
            newLinks.put(playerId, new Link(playerId, playerName, link.discordId(), link.linkedAt()));
            persist(newLinks, pending);
            links.clear();
            links.putAll(newLinks);
        }
    }

    synchronized Optional<Link> deny(UUID playerId) throws IOException {
        Link removed = pending.get(playerId);
        if (removed != null) {
            Map<UUID, Link> newPending = new LinkedHashMap<>(pending);
            newPending.remove(playerId);
            persist(links, newPending);
            pending.clear();
            pending.putAll(newPending);
        }
        return Optional.ofNullable(removed);
    }

    synchronized Optional<Link> unlinkByName(String name) throws IOException {
        Optional<Link> found = findLinkByName(name);
        if (found.isPresent()) {
            Map<UUID, Link> newLinks = new LinkedHashMap<>(links);
            newLinks.remove(found.get().playerId());
            persist(newLinks, pending);
            links.clear();
            links.putAll(newLinks);
        }
        return found;
    }

    private void load() throws IOException, InvalidConfigurationException {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        ConfigurationSection linkSection = yaml.getConfigurationSection("links");
        ConfigurationSection pendingSection = yaml.getConfigurationSection("pending");
        if ((yaml.contains("links") && linkSection == null)
                || (yaml.contains("pending") && pendingSection == null)) {
            throw new IOException("auth-data.yml contains an invalid links or pending section");
        }
        readSection(linkSection, links);
        readSection(pendingSection, pending);
    }

    private void readSection(ConfigurationSection section, Map<UUID, Link> destination) throws IOException {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                throw new IOException("Invalid identity entry in auth-data.yml: " + key);
            }
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid Minecraft UUID in auth-data.yml: " + key, exception);
            }
            String name = entry.getString("player-name");
            String discordId = entry.getString("discord-id");
            long linkedAt = entry.getLong("linked-at");
            if (name == null || name.isBlank() || discordId == null || !discordId.matches("\\d{17,20}")
                    || linkedAt <= 0) {
                throw new IOException("Incomplete or invalid identity entry in auth-data.yml: " + key);
            }
            destination.put(playerId, new Link(playerId, name, discordId, linkedAt));
        }
    }

    private void persist(Map<UUID, Link> newLinks, Map<UUID, Link> newPending) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        writeSection(yaml, "links", newLinks);
        writeSection(yaml, "pending", newPending);

        Path directory = plugin.getDataFolder().toPath();
        Files.createDirectories(directory);
        Path temporaryFile = Files.createTempFile(directory, "auth-data-", ".yml.tmp");
        try {
            yaml.save(temporaryFile.toFile());
            try {
                Files.move(temporaryFile, file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
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
