package com.armyorders.manager;

import com.armyorders.ArmyOrders;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Управление приказами.
 * Офицер выдаёт приказ солдату на определённое время.
 */
public class OrderManager {

    private final ArmyOrders plugin;
    
    // Активные приказы (target UUID -> Order)
    private final Map<UUID, ActiveOrder> activeOrders = new HashMap<>();
    
    // Типы приказов
    public enum OrderType {
        SIT("Присесть", "🎯", "Сесть на землю (Shift)"),
        STAND("Стоять смирно", "🪖", "Стоять не двигаясь"),
        MARCH("Маршировать", "🚶", "Ходить вокруг"),
        RUN("Бегать", "🏃", "Бегать на месте");
        
        private final String display;
        private final String icon;
        private final String description;
        
        OrderType(String display, String icon, String description) {
            this.display = display;
            this.icon = icon;
            this.description = description;
        }
        
        public String getDisplay() { return display; }
        public String getIcon() { return icon; }
        public String getDescription() { return description; }
    }
    
    // Результат приказа
    public enum OrderResult {
        COMPLETED("Выполнено", "✅"),
        PUNISHED("Наказан", "❌"),
        TIMEOUT("Время вышло", "⏰"),
        CANCELLED("Отменён", "🚫");
        
        private final String display;
        private final String icon;
        
        OrderResult(String display, String icon) {
            this.display = display;
            this.icon = icon;
        }
        
        public String getDisplay() { return display; }
        public String getIcon() { return icon; }
    }
    
    // Активный приказ
    public static class ActiveOrder {
        public final UUID targetUUID;
        public final UUID issuerUUID;
        public final OrderType type;
        public final long startTime;
        public final int durationSeconds;
        public boolean completed;
        public OrderResult result;
        
        public ActiveOrder(UUID target, UUID issuer, OrderType type, int durationSeconds) {
            this.targetUUID = target;
            this.issuerUUID = issuer;
            this.type = type;
            this.startTime = System.currentTimeMillis();
            this.durationSeconds = durationSeconds;
            this.completed = false;
            this.result = null;
        }
        
        public long getRemainingSeconds() {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            return Math.max(0, durationSeconds - elapsed);
        }
        
        public boolean isExpired() {
            return getRemainingSeconds() <= 0;
        }
        
        public Player getTarget() {
            return Bukkit.getPlayer(targetUUID);
        }
        
        public Player getIssuer() {
            return Bukkit.getPlayer(issuerUUID);
        }
    }
    
    // Статистика игрока
    public static class PlayerStats {
        public int completed = 0;
        public int failed = 0;
        public int given = 0;
    }
    
    private final Map<UUID, PlayerStats> stats = new HashMap<>();
    
    // Таск для проверки приказов
    private BukkitTask checkTask;
    private BukkitTask updateTask;
    
    public OrderManager(ArmyOrders plugin) {
        this.plugin = plugin;
        loadData();
    }
    
    public void startTasks() {
        // Проверка приказов каждую секунду
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            checkExpiredOrders();
            updateBossBars();
        }, 20L, 20L); // каждую секунду
        
        // Обновление каждую минуту (для GUI)
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Сохраняем статистику
            saveData();
        }, 1200L, 1200L); // каждую минуту
    }
    
    public void stopTasks() {
        if (checkTask != null) checkTask.cancel();
        if (updateTask != null) updateTask.cancel();
        saveData();
    }
    
    // ─── ВЫДАЧА ПРИКАЗОВ ───────────────────────────────────────────────────
    
    public boolean giveOrder(Player issuer, Player target, OrderType type, int durationSeconds) {
        // Проверяем страну — можно приказывать только своим
        if (!plugin.getRankManager().isSameCountry(issuer, target)) {
            issuer.sendMessage(ChatColor.RED + "✗ Нельзя отдавать приказы игрокам из других стран!");
            return false;
        }

        // Проверяем что цель не в списке выше по должности
        var issuerPos = plugin.getRankManager().getPosition(issuer);
        var targetPos = plugin.getRankManager().getPosition(target);

        if (issuerPos.getLevel() <= targetPos.getLevel()) {
            return false;
        }
        
        // Если уже есть активный приказ - заменяем
        if (activeOrders.containsKey(target.getUniqueId())) {
            activeOrders.remove(target.getUniqueId());
        }
        
        // Создаём новый приказ
        ActiveOrder order = new ActiveOrder(target.getUniqueId(), issuer.getUniqueId(), type, durationSeconds);
        activeOrders.put(target.getUniqueId(), order);
        
        // Обновляем статистику
        getStats(issuer).given++;
        
        // Уведомляем
        issuer.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.WHITE + "Приказ '" + type.getDisplay() 
                + "' выдан игроку " + target.getName());
        target.sendMessage(ChatColor.YELLOW + "🎖 ВНИМАНИЕ! " + ChatColor.WHITE + "Вам поступил приказ!");
        target.sendMessage(ChatColor.YELLOW + "Тип: " + type.getDisplay());
        target.sendMessage(ChatColor.YELLOW + "Время: " + formatTime(durationSeconds));
        target.sendMessage(ChatColor.GRAY + "Ожидайте решения офицера...");
        
        saveData();
        return true;
    }
    
    // ─── ВЫПОЛНЕНИЕ/НАКАЗАНИЕ ──────────────────────────────────────────────
    
    public boolean completeOrder(Player issuer, Player target) {
        ActiveOrder order = activeOrders.get(target.getUniqueId());
        if (order == null) return false;
        
        // Проверяем что issuer это тот кто выдал или выше
        var issuerPos = plugin.getRankManager().getPosition(issuer);
        var orderIssuerPos = plugin.getRankManager().getPosition(order.getIssuer());
        
        if (!issuer.equals(order.getIssuer()) && issuerPos.getLevel() <= orderIssuerPos.getLevel()) {
            return false;
        }
        
        order.completed = true;
        order.result = OrderResult.COMPLETED;
        
        // Награждаем званием (+1 час к службе)
        plugin.getRankManager().addServiceTime(target, 3600);
        
        // Статистика
        getStats(target).completed++;
        getStats(order.getIssuer()).given++;
        
        // Уведомляем
        issuer.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.WHITE + "Приказ выполнен! " 
                + ChatColor.GREEN + "+1 час службы для " + target.getName());
        target.sendMessage(ChatColor.GREEN + "✅ ПРИКАЗ ВЫПОЛНЕН!");
        target.sendMessage(ChatColor.GREEN + "+1 час к вашему званию!");
        
        activeOrders.remove(target.getUniqueId());
        saveData();
        return true;
    }
    
    public boolean punishOrder(Player issuer, Player target) {
        ActiveOrder order = activeOrders.get(target.getUniqueId());
        if (order == null) return false;
        
        var issuerPos = plugin.getRankManager().getPosition(issuer);
        var orderIssuerPos = plugin.getRankManager().getPosition(order.getIssuer());
        
        if (!issuer.equals(order.getIssuer()) && issuerPos.getLevel() <= orderIssuerPos.getLevel()) {
            return false;
        }
        
        order.completed = true;
        order.result = OrderResult.PUNISHED;
        
        // Статистика
        getStats(target).failed++;
        getStats(order.getIssuer()).given++;
        
        // Уведомляем
        issuer.sendMessage(ChatColor.RED + "✗ " + ChatColor.WHITE + "Приказ не выполнен! " + target.getName() + " наказан.");
        target.sendMessage(ChatColor.RED + "❌ ПРИКАЗ НЕ ВЫПОЛНЕН!");
        target.sendMessage(ChatColor.RED + "Вы получили наказание.");
        
        activeOrders.remove(target.getUniqueId());
        saveData();
        return true;
    }
    
    public boolean cancelOrder(Player issuer, Player target) {
        ActiveOrder order = activeOrders.get(target.getUniqueId());
        if (order == null) return false;
        
        if (!issuer.equals(order.getIssuer())) {
            var issuerPos = plugin.getRankManager().getPosition(issuer);
            var orderIssuerPos = plugin.getRankManager().getPosition(order.getIssuer());
            if (issuerPos.getLevel() <= orderIssuerPos.getLevel()) {
                return false;
            }
        }
        
        activeOrders.remove(target.getUniqueId());
        issuer.sendMessage(ChatColor.YELLOW + "Приказ отменён.");
        target.sendMessage(ChatColor.YELLOW + "Ваш приказ был отменён.");
        return true;
    }
    
    // ─── ПРОВЕРКИ ──────────────────────────────────────────────────────────
    
    private void checkExpiredOrders() {
        List<UUID> toRemove = new ArrayList<>();
        
        for (Map.Entry<UUID, ActiveOrder> entry : activeOrders.entrySet()) {
            ActiveOrder order = entry.getValue();
            if (order.isExpired() && !order.completed) {
                order.completed = true;
                order.result = OrderResult.TIMEOUT;
                
                // Уведомляем
                if (order.getIssuer() != null) {
                    order.getIssuer().sendMessage(ChatColor.YELLOW + "⏰ Время приказа для " 
                            + order.getTarget().getName() + " вышло!");
                }
                if (order.getTarget() != null) {
                    order.getTarget().sendMessage(ChatColor.YELLOW + "⏰ Ваш приказ истёк!");
                }
                
                getStats(order.getTarget()).failed++;
                toRemove.add(entry.getKey());
            }
        }
        
        for (UUID uuid : toRemove) {
            activeOrders.remove(uuid);
        }
    }
    
    private void updateBossBars() {
        for (ActiveOrder order : activeOrders.values()) {
            if (order.completed) continue;
            Player target = order.getTarget();
            if (target == null || !target.isOnline()) continue;
            
            // Показываем BossBar с приказом
            long remaining = order.getRemainingSeconds();
            String message = order.type.getIcon() + " ПРИКАЗ: " + order.type.getDisplay() 
                    + " | Осталось: " + formatTime((int) remaining);
            
            // Action bar
            target.spigot().sendMessage(ChatMessageType.ACTION_BAR, 
                    TextComponent.fromLegacyText(ChatColor.YELLOW + message));
        }
    }
    
    // ─── GETTERS ──────────────────────────────────────────────────────────
    
    public ActiveOrder getOrder(Player target) {
        return activeOrders.get(target.getUniqueId());
    }
    
    public boolean hasOrder(Player target) {
        return activeOrders.containsKey(target.getUniqueId());
    }
    
    public Collection<ActiveOrder> getActiveOrders() {
        return activeOrders.values();
    }
    
    public Collection<ActiveOrder> getOrdersByIssuer(Player issuer) {
        return activeOrders.values().stream()
                .filter(o -> o.getIssuer() != null && o.getIssuer().equals(issuer))
                .toList();
    }
    
    public PlayerStats getStats(Player player) {
        return stats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats());
    }
    
    // ─── ФОРМАТИРОВАНИЕ ────────────────────────────────────────────────────
    
    public static String formatTime(long seconds) {
        long mins = seconds / 60;
        long secs = seconds % 60;
        return String.format("%d:%02d", mins, secs);
    }
    
    public static String formatTimeFull(long seconds) {
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%dч %dм %dс", hours, mins, secs);
        } else if (mins > 0) {
            return String.format("%dм %dс", mins, secs);
        } else {
            return String.format("%dс", secs);
        }
    }
    
    // ─── СОХРАНЕНИЕ ───────────────────────────────────────────────────────
    
    private void loadData() {
        var config = plugin.getConfig();
        
        // Загружаем статистику (с проверкой на null)
        var statsSection = config.getConfigurationSection("stats");
        if (statsSection != null) {
            for (String uuidStr : statsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    PlayerStats s = new PlayerStats();
                    s.completed = config.getInt("stats." + uuidStr + ".completed", 0);
                    s.failed = config.getInt("stats." + uuidStr + ".failed", 0);
                    s.given = config.getInt("stats." + uuidStr + ".given", 0);
                    stats.put(uuid, s);
                } catch (Exception ignored) {}
            }
        }
    }
    
    private void saveData() {
        var config = plugin.getConfig();
        
        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            String path = "stats." + entry.getKey().toString();
            config.set(path + ".completed", entry.getValue().completed);
            config.set(path + ".failed", entry.getValue().failed);
            config.set(path + ".given", entry.getValue().given);
        }
        
        plugin.saveConfig();
    }
}
