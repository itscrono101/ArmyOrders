package com.armyorders.data;

import com.armyorders.ArmyOrders;
import com.armyorders.manager.RankManager;
import com.armyorders.manager.RankManager.MilitaryRole;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Управление данными игроков.
 * Контракты сохраняются в playerdata/{uuid}.yml
 */
public class PlayerData {

    private final ArmyOrders plugin;
    private final File dataFolder;

    // Время контракта: 7 реальных дней в секундах
    private static final long CONTRACT_DURATION_SECONDS = 7 * 24 * 3600L;
    // Предупреждение за 1 час до окончания
    private static final long CONTRACT_WARNING_SECONDS = 3600L;

    // Данные контрактов в памяти
    private final Map<UUID, ContractInfo> contracts = new HashMap<>();

    public static class ContractInfo {
        public RankManager.MilitaryRole role;
        public long startTime;
        public long expiryTime;
        public boolean warned1h;

        public ContractInfo(RankManager.MilitaryRole role, long startTime) {
            this.role = role;
            this.startTime = startTime;
            this.expiryTime = startTime + CONTRACT_DURATION_SECONDS * 1000;
            this.warned1h = false;
        }

        public ContractInfo(RankManager.MilitaryRole role, long startTime, long expiryTime, boolean warned1h) {
            this.role = role;
            this.startTime = startTime;
            this.expiryTime = expiryTime;
            this.warned1h = warned1h;
        }

        public long getRemainingSeconds() {
            long remaining = (expiryTime - System.currentTimeMillis()) / 1000;
            return Math.max(0, remaining);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }

        public boolean needsWarning() {
            return !warned1h && getRemainingSeconds() <= CONTRACT_WARNING_SECONDS;
        }

        public String getRemainingFormatted() {
            long totalSec = getRemainingSeconds();
            long days = totalSec / (24 * 3600);
            long hours = (totalSec % (24 * 3600)) / 3600;
            long mins = (totalSec % 3600) / 60;

            if (days > 0) return days + "д " + hours + "ч";
            if (hours > 0) return hours + "ч " + mins + "м";
            return mins + "м";
        }
    }

    public PlayerData(ArmyOrders plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        this.dataFolder.mkdirs();
    }

    // ─── КОНТРАКТЫ ─────────────────────────────────────────────────────

    /**
     * Подписать контракт: игрок становится на указанную роль.
     * Применяется автоматически в RankManager.
     */
    public void signContract(Player player, RankManager.MilitaryRole role) {
        long now = System.currentTimeMillis();
        ContractInfo contract = new ContractInfo(role, now);
        contracts.put(player.getUniqueId(), contract);

        // Назначаем роль
        plugin.getRankManager().setRole(player, role);

        // Старт службы (если новый)
        Long existing = plugin.getRankManager().getServiceTime(player) > 0
                ? null : now; // не трогаем если уже служил
        if (existing == null) {
            // Инициализируем время службы
            plugin.getConfig().set("service-time." + player.getUniqueId(), now);
        }

        savePlayerData(player);
        player.sendMessage(net.md_5.bungee.api.ChatColor.GREEN + "✓ "
                + "Вы подписали контракт на должность "
                + net.md_5.bungee.api.ChatColor.YELLOW + role.getIcon() + " " + role.getDisplay());
        player.sendMessage(net.md_5.bungee.api.ChatColor.GRAY + "Срок действия: "
                + net.md_5.bungee.api.ChatColor.WHITE + contract.getRemainingFormatted());
    }

    /**
     * Продлить контракт текущей роли.
     */
    public void renewContract(Player player) {
        ContractInfo existing = contracts.get(player.getUniqueId());
        if (existing == null) {
            // Нет контракта — создаём новый на текущую роль
            RankManager.MilitaryRole role = plugin.getRankManager().getRole(player);
            if (role != RankManager.MilitaryRole.NONE) {
                signContract(player, role);
            }
            return;
        }

        long now = System.currentTimeMillis();
        existing.expiryTime = now + CONTRACT_DURATION_SECONDS * 1000;
        existing.warned1h = false;

        savePlayerData(player);
        player.sendMessage(net.md_5.bungee.api.ChatColor.GREEN + "✓ "
                + "Контракт продлён на "
                + net.md_5.bungee.api.ChatColor.YELLOW + existing.getRemainingFormatted());
    }

    /**
     * Расторгнуть контракт (увольнение из армии).
     */
    public void terminateContract(Player player) {
        contracts.remove(player.getUniqueId());
        plugin.getRankManager().setRole(player, RankManager.MilitaryRole.NONE);
        savePlayerData(player);
        player.sendMessage(net.md_5.bungee.api.ChatColor.YELLOW + "Контракт расторгнут. Вы уволены из армии.");
    }

    public ContractInfo getContract(Player player) {
        return contracts.get(player.getUniqueId());
    }

    public boolean hasContract(Player player) {
        return contracts.containsKey(player.getUniqueId());
    }

    // ─── ЗАГРУЗКА/СОХРАНЕНИЕ ───────────────────────────────────────────

    public void loadAll() {
        contracts.clear();
        if (!dataFolder.exists()) return;

        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                String uuidStr = file.getName().replace(".yml", "");
                UUID uuid = UUID.fromString(uuidStr);

                List<String> lines = Files.readAllLines(file.toPath());
                ContractInfo info = parseContract(lines);
                if (info != null) {
                    contracts.put(uuid, info);
                }
            } catch (Exception e) {
                // пропускаем
            }
        }
    }

    private ContractInfo parseContract(List<String> lines) {
        RankManager.MilitaryRole role = null;
        long startTime = 0;
        long expiryTime = 0;
        boolean warned = false;

        for (String line : lines) {
            if (line.startsWith("role: ")) {
                try {
                    role = RankManager.MilitaryRole.valueOf(line.substring(6).trim());
                } catch (Exception e) {}
            } else if (line.startsWith("start: ")) {
                try { startTime = Long.parseLong(line.substring(7).trim()); } catch (Exception e) {}
            } else if (line.startsWith("expiry: ")) {
                try { expiryTime = Long.parseLong(line.substring(8).trim()); } catch (Exception e) {}
            } else if (line.startsWith("warned: ")) {
                warned = line.substring(8).trim().equals("true");
            }
        }

        if (role != null && startTime > 0 && expiryTime > 0) {
            return new ContractInfo(role, startTime, expiryTime, warned);
        }
        return null;
    }

    private void savePlayerData(Player player) {
        File file = getPlayerFile(player.getUniqueId());
        StringBuilder sb = new StringBuilder();
        sb.append("uuid: ").append(player.getUniqueId().toString()).append("\n");

        ContractInfo info = contracts.get(player.getUniqueId());
        if (info != null) {
            sb.append("role: ").append(info.role.name()).append("\n");
            sb.append("start: ").append(info.startTime).append("\n");
            sb.append("expiry: ").append(info.expiryTime).append("\n");
            sb.append("warned: ").append(info.warned1h).append("\n");
        }

        try {
            Files.writeString(file.toPath(), sb.toString());
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить данные контракта: " + e.getMessage());
        }
    }

    public File getPlayerFile(UUID uuid) {
        return new File(dataFolder, uuid.toString() + ".yml");
    }

    // ─── ПРОВЕРКА ИСТЕКШИХ КОНТРАКТОВ ──────────────────────────────────

    /**
     * Вызывать периодически (раз в минуту).
     * - Предупреждает за 1 час до истечения
     * - Автоматически увольняет при истечении
     */
    public void checkExpiredContracts() {
        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, ContractInfo> entry : contracts.entrySet()) {
            ContractInfo info = entry.getValue();
            UUID uuid = entry.getKey();

            if (info.isExpired()) {
                // Контракт истёк — увольняем
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(net.md_5.bungee.api.ChatColor.RED + "⚠ "
                            + "Ваш контракт истёк. Вы уволены из армии.");
                    plugin.getRankManager().setRole(player, RankManager.MilitaryRole.NONE);
                }
                toRemove.add(uuid);
            } else if (info.needsWarning()) {
                // Предупреждение за 1 час
                info.warned1h = true;
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(net.md_5.bungee.api.ChatColor.YELLOW + "⏰ "
                            + "Ваш контракт истекает через "
                            + net.md_5.bungee.api.ChatColor.RED + info.getRemainingFormatted()
                            + net.md_5.bungee.api.ChatColor.YELLOW + "! "
                            + "Продлите: " + net.md_5.bungee.api.ChatColor.WHITE + "/mo renew");
                }
                // Сохраняем что предупредили
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) savePlayerData(p);
            }
        }

        for (UUID uuid : toRemove) {
            contracts.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) savePlayerData(player);
        }
    }

    // ─── КОНСТАНТЫ ─────────────────────────────────────────────────────

    public static long getContractDurationSeconds() {
        return CONTRACT_DURATION_SECONDS;
    }

    public static String getContractDurationFormatted() {
        return "7 дней";
    }
}
