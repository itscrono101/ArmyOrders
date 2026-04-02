package com.armyorders.data;

import com.armyorders.ArmyOrders;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.UUID;

/**
 * Управление данными игроков (сохранение в файлы).
 */
public class PlayerData {

    private final ArmyOrders plugin;
    private final File dataFolder;
    
    public PlayerData(ArmyOrders plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        this.dataFolder.mkdirs();
    }
    
    public void save() {
        plugin.getRankManager(); // инициируем сохранение
    }
    
    public File getPlayerFile(UUID uuid) {
        return new File(dataFolder, uuid.toString() + ".yml");
    }
    
    public void loadPlayer(Player player) {
        // Данные загружаются через RankManager из config.yml
    }
    
    public boolean hasData(UUID uuid) {
        return getPlayerFile(uuid).exists();
    }
}
