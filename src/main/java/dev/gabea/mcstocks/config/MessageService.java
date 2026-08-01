package dev.gabea.mcstocks.config;

import dev.gabea.mcstocks.util.Text;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class MessageService {
    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String key) {
        return Text.color(messages.getString("prefix", "") + messages.getString(key, key));
    }

    public String format(String key, Map<String, String> replacements) {
        String value = get(key);
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            value = value.replace("%" + replacement.getKey() + "%", replacement.getValue());
        }
        return value;
    }
}
