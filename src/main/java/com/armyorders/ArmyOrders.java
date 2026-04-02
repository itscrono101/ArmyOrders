package com.armyorders;

import com.armyorders.commands.ArmyCommand;
import com.armyorders.data.PlayerData;
import com.armyorders.gui.MainGUI;
import com.armyorders.manager.OrderManager;
import com.armyorders.manager.RankManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ArmyOrders extends JavaPlugin {

    private static ArmyOrders instance;
    
    private RankManager rankManager;
    private OrderManager orderManager;
    private PlayerData playerData;
    private MainGUI mainGUI;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Сохраняем конфиг
        saveDefaultConfig();
        
        // Инициализация менеджеров
        playerData = new PlayerData(this);
        rankManager = new RankManager(this);
        orderManager = new OrderManager(this);
        mainGUI = new MainGUI(this);
        
        // Регистрация команды
        ArmyCommand armyCommand = new ArmyCommand(this);
        getCommand("mo").setExecutor(armyCommand);
        armyCommand.registerTabCompleter();
        
        // Запуск тасков
        orderManager.startTasks();
        
        getLogger().info("========================================");
        getLogger().info("  ArmyOrders v" + getDescription().getVersion() + " загружен!");
        getLogger().info("  Команда: /mo");
        getLogger().info("========================================");
    }
    
    @Override
    public void onDisable() {
        if (orderManager != null) {
            orderManager.stopTasks();
        }
        if (playerData != null) {
            playerData.save();
        }
    }
    
    public static ArmyOrders getInstance() {
        return instance;
    }
    
    public RankManager getRankManager() {
        return rankManager;
    }
    
    public OrderManager getOrderManager() {
        return orderManager;
    }
    
    public PlayerData getPlayerData() {
        return playerData;
    }
    
    public MainGUI getMainGUI() {
        return mainGUI;
    }
}
