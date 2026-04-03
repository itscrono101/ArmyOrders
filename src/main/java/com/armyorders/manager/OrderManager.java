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
 * Upravlenie prikazami.
 * Oficer vidaet prikaz soldatu na opredelennoe vremya.
 */
public class OrderManager {

    private final ArmyOrders plugin;

    // Aktivnye prikazy (target UUID -> Order)
    private final Map<UUID, ActiveOrder> activeOrders = new HashMap<>();

    // Tipy prikazov
    public enum OrderType {
        SIT("Prisest", "🎯", "Sest na zemlyu (Shift)"),
        STAND("Stoyat smirno", "🪖", "Stoyat ne dvigayas"),
        MARCH("Marshirovat", "🚶", "Hodit vokrug"),
        RUN("Begat", "🏃", "Begat na meste");

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

    // Rezultat prikaza
    public enum OrderResult {
        COMPLETED("Vypolneno", "✅"),
        PUNISHED("Nakazan", "❌"),
        TIMEOUT("Vremya vyshlo", "⏰"),
        CANCELLED("Otmanyon", "🚫");

        private final String display;
        private final String icon;

        OrderResult(String display, String icon) {
            this.display = display;
            this.icon = icon;
        }

        public String getDisplay() { return display; }
        public String getIcon() { return icon; }
    }

    // Aktivnyy prikaz
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

    // Statistika igroka
    public static class PlayerStats {
        public int completed = 0;
        public int failed = 0;
        public int given = 0;
    }

    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    private BukkitTask checkTask;
    private BukkitTask updateTask;

    public OrderManager(ArmyOrders plugin) {
        this.plugin = plugin;
        loadData();
    }

    public void startTasks() {
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            checkExpiredOrders();
            updateBossBars();
        }, 20L, 20L);

        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            saveData();
        }, 1200L, 1200L);
    }

    public void stopTasks() {
        if (checkTask != null) checkTask.cancel();
        if (updateTask != null) updateTask.cancel();
        saveData();
    }

    // VidaCHa PRIKAZOV

    public boolean giveOrder(Player issuer, Player target, OrderType type, int durationSeconds) {
        if (!plugin.getRankManager().isSameCountry(issuer, target)) {
            issuer.sendMessage(ChatColor.RED + "✗ Nelzya otdavat prikazy igrokam iz drugih stran!");
            return false;
        }

        var issuerRole = plugin.getRankManager().getRole(issuer);
        var targetRole = plugin.getRankManager().getRole(target);

        if (issuerRole.getLevel() <= targetRole.getLevel()) {
            issuer.sendMessage(ChatColor.RED + "✗ Nelzya otdavat prikazy igrokam vyshe vas po dolzhnosti!");
            return false;
        }

        if (activeOrders.containsKey(target.getUniqueId())) {
            activeOrders.remove(target.getUniqueId());
        }

        ActiveOrder order = new ActiveOrder(target.getUniqueId(), issuer.getUniqueId(), type, durationSeconds);
        activeOrders.put(target.getUniqueId(), order);

        getStats(issuer).given++;

        issuer.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.WHITE + "Prikaz '" + type.getDisplay()
                + "' vydan igroku " + target.getName());
        target.sendMessage(ChatColor.YELLOW + "🎖 VNIMANIE! " + ChatColor.WHITE + "Vam postupil prikaz!");
        target.sendMessage(ChatColor.YELLOW + "Tip: " + type.getDisplay());
        target.sendMessage(ChatColor.YELLOW + "Vremya: " + formatTime(durationSeconds));
        target.sendMessage(ChatColor.GRAY + "Ozhidayte resheniya ofitsera...");

        saveData();
        return true;
    }

    // VYPOLNENIE / NAKAZANIE

    public boolean completeOrder(Player issuer, Player target) {
        ActiveOrder order = activeOrders.get(target.getUniqueId());
        if (order == null) return false;

        var issuerRole = plugin.getRankManager().getRole(issuer);
        var orderIssuerRole = plugin.getRankManager().getRole(order.getIssuer());

        if (!issuer.equals(order.getIssuer()) && issuerRole.getLevel() <= orderIssuerRole.getLevel()) {
            return false;
        }

        order.completed = true;
        order.result = OrderResult.COMPLETED;

        plugin.getRankManager().addServiceTime(target, 3600);

        getStats(target).completed++;
        getStats(order.getIssuer()).given++;

        issuer.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.WHITE + "Prikaz vypolnen! "
                + ChatColor.GREEN + "+1 chas sluzhby dlya " + target.getName());
        target.sendMessage(ChatColor.GREEN + "✅ PRIKAZ VYPOLNEN!");
        target.sendMessage(ChatColor.GREEN + "+1 chas k vasheyu zvaniyu!");

        activeOrders.remove(target.getUniqueId());
        saveData();
        return true;
    }

    public boolean punishOrder(Player issuer, Player target) {
        ActiveOrder order = activeOrders.get(target.getUniqueId());
        if (order == null) return false;

        var issuerRole = plugin.getRankManager().getRole(issuer);
        var orderIssuerRole = plugin.getRankManager().getRole(order.getIssuer());

        if (!issuer.equals(order.getIssuer()) && issuerRole.getLevel() <= orderIssuerRole.getLevel()) {
            return false;
        }

        order.completed = true;
        order.result = OrderResult.PUNISHED;

        getStats(target).failed++;
        getStats(order.getIssuer()).given++;

        issuer.sendMessage(ChatColor.RED + "✗ " + ChatColor.WHITE + "Prikaz ne vypolnen! " + target.getName() + " nakazan.");
        target.sendMessage(ChatColor.RED + "❌ PRIKAZ NE VYPOLNEN!");
        target.sendMessage(ChatColor.RED + "Vy poluchili nakazanie.");

        activeOrders.remove(target.getUniqueId());
        saveData();
        return true;
    }

    public boolean cancelOrder(Player issuer, Player target) {
        ActiveOrder order = activeOrders.get(target.getUniqueId());
        if (order == null) return false;

        if (!issuer.equals(order.getIssuer())) {
            var issuerRole = plugin.getRankManager().getRole(issuer);
            var orderIssuerRole = plugin.getRankManager().getRole(order.getIssuer());
            if (issuerRole.getLevel() <= orderIssuerRole.getLevel()) {
                return false;
            }
        }

        activeOrders.remove(target.getUniqueId());
        issuer.sendMessage(ChatColor.YELLOW + "Prikaz otmenyon.");
        target.sendMessage(ChatColor.YELLOW + "Vash prikaz byl otmenyon.");
        return true;
    }

    // PROVERKI

    private void checkExpiredOrders() {
        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, ActiveOrder> entry : activeOrders.entrySet()) {
            ActiveOrder order = entry.getValue();
            if (order.isExpired() && !order.completed) {
                order.completed = true;
                order.result = OrderResult.TIMEOUT;

                if (order.getIssuer() != null) {
                    order.getIssuer().sendMessage(ChatColor.YELLOW + "⏰ Vremya prikaza dlya "
                            + order.getTarget().getName() + " vyshlo!");
                }
                if (order.getTarget() != null) {
                    order.getTarget().sendMessage(ChatColor.YELLOW + "⏰ Vash prikaz istyek!");
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

            long remaining = order.getRemainingSeconds();
            String message = order.type.getIcon() + " PRIKAZ: " + order.type.getDisplay()
                    + " | Ostavalos: " + formatTime((int) remaining);

            target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(ChatColor.YELLOW + message));
        }
    }

    // GETTERS

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

    // FORMATIROVANIE

    public static String formatTime(long seconds) {
        long mins = seconds / 60;
        long secs = seconds % 60;
        return String.format("%d:%02d", mins, secs);
    }

    // SOKHRANENIE

    private void loadData() {
        var config = plugin.getConfig();

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
