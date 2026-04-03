package com.armyorders.manager;

import com.armyorders.ArmyOrders;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class RankManager {

    private final ArmyOrders plugin;

    private final Map<UUID, MilitaryPosition> playerPositions = new HashMap<>();
    private final Map<UUID, Long> serviceTime = new HashMap<>();
    private final Map<UUID, String> playerCountries = new HashMap<>();

    public enum MilitaryPosition {
        NONE("Host", "O", 0),
        SOLDIER("Soldier", "V", 1),
        OFFICER("Officer", "V", 2),
        ARMY_LEADER("Army Leader", "S", 3);

        private final String display;
        private final String icon;
        private final int level;

        MilitaryPosition(String display, String icon, int level) {
            this.display = display;
            this.icon = icon;
            this.level = level;
        }

        public String getDisplay() { return display; }
        public String getIcon() { return icon; }
        public int getLevel() { return level; }
    }

    public enum MilitaryRank {
        PRIVATE("Рядовой", "I", 0, 0),
        CORPORAL("Ефрейтор", "II", 1, 3600),
        SERGEANT("Сержант", "III", 2, 18000),
        LIEUTENANT("Лейтенант", "IV", 3, 43200),
        CAPTAIN("Капитан", "V", 4, 86400),
        MAJOR("Майор", "VI", 5, 172800),
        COLONEL("Полковник", "VII", 6, 345600),
        COMMANDER_IN_CHIEF("Главнокомандующий", "★", 7, Long.MAX_VALUE);

        private final String display;
        private final String icon;
        private final int level;
        private final long secondsRequired;

        MilitaryRank(String display, String icon, int level, long secondsRequired) {
            this.display = display;
            this.icon = icon;
            this.level = level;
            this.secondsRequired = secondsRequired;
        }

        public String getDisplay() { return display; }
        public String getIcon() { return icon; }
        public int getLevel() { return level; }
        public long getSecondsRequired() { return secondsRequired; }

        public static MilitaryRank getNext(MilitaryRank current) {
            MilitaryRank[] values = MilitaryRank.values();
            int nextIndex = current.ordinal() + 1;
            return nextIndex < values.length ? values[nextIndex] : null;
        }

        public static MilitaryRank getByTime(long seconds) {
            MilitaryRank[] values = MilitaryRank.values();
            MilitaryRank result = PRIVATE;
            for (MilitaryRank rank : values) {
                if (seconds >= rank.getSecondsRequired()) {
                    result = rank;
                }
            }
            return result;
        }
    }

    public RankManager(ArmyOrders plugin) {
        this.plugin = plugin;
        loadData();
    }

    // ─── СТРАНА ────────────────────────────────────────────────────────────

    /**
     * Получить страну игрока через CountryisLeaderPerms (reflection).
     * Возвращает null если CLP не установлен или игрок не в стране.
     */
    public String getCountry(Player player) {
        // Сначала проверяем кэш
        String cached = playerCountries.get(player.getUniqueId());
        if (cached != null) return cached;

        // Если игрок — правитель (clp.leader) — он ARMYY_LEADER автоматически
        if (player.hasPermission("clp.leader")) {
            String country = getCountryViaReflection(player);
            if (country != null) {
                playerCountries.put(player.getUniqueId(), country);
                // Авто-назначение правителя как главы армии
                if (getPosition(player) == MilitaryPosition.NONE) {
                    setPosition(player, MilitaryPosition.ARMY_LEADER);
                }
            }
            return country;
        }

        String country = getCountryViaReflection(player);
        if (country != null) {
            playerCountries.put(player.getUniqueId(), country);
        }
        return country;
    }

    /**
     * Получает страну через reflection к CountryisLeaderPerms.
     */
    private String getCountryViaReflection(Player player) {
        try {
            Plugin clp = plugin.getServer().getPluginManager().getPlugin("CountryisLeaderPerms");
            if (clp == null) return null;

            Object countryManager = clp.getClass().getMethod("getCountryManager").invoke(clp);
            if (countryManager == null) return null;

            Object profile = countryManager.getClass().getMethod("getProfile", UUID.class)
                    .invoke(countryManager, player.getUniqueId());
            if (profile == null) return null;

            return (String) profile.getClass().getMethod("getCountryName").invoke(profile);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Установить страну в кэш вручную.
     */
    public void setCountry(Player player, String countryName) {
        if (countryName == null) {
            playerCountries.remove(player.getUniqueId());
        } else {
            playerCountries.put(player.getUniqueId(), countryName);
        }
    }

    /**
     * Проверяет, все ли игроки из одной страны.
     */
    public boolean isSameCountry(Player a, Player b) {
        String ca = getCountry(a);
        String cb = getCountry(b);
        if (ca == null || cb == null) return false;
        return ca.equals(cb);
    }

    // ─── ДОЛЖНОСТЬ ────────────────────────────────────────────────────────

    public void setPosition(Player player, MilitaryPosition position) {
        playerPositions.put(player.getUniqueId(), position);
        // Синхронизируем страну при назначении
        String country = getCountry(player);
        if (country != null) {
            playerCountries.put(player.getUniqueId(), country);
        }
        saveData();
        updatePlayer(player);
    }

    public MilitaryPosition getPosition(Player player) {
        // Правитель автоматически имеет высший ранг
        if (player.hasPermission("clp.leader")) {
            MilitaryPosition current = playerPositions.get(player.getUniqueId());
            if (current == null || current == MilitaryPosition.NONE) {
                return MilitaryPosition.ARMY_LEADER;
            }
            return current;
        }
        return playerPositions.getOrDefault(player.getUniqueId(), MilitaryPosition.NONE);
    }

    public boolean hasPosition(Player player, MilitaryPosition required) {
        return getPosition(player).getLevel() >= required.getLevel();
    }

    public boolean canGivePosition(Player giver, MilitaryPosition target) {
        MilitaryPosition giverPos = getPosition(giver);

        if (giverPos == MilitaryPosition.ARMY_LEADER) {
            return target == MilitaryPosition.OFFICER || target == MilitaryPosition.SOLDIER;
        }

        if (giverPos == MilitaryPosition.OFFICER) {
            return target == MilitaryPosition.SOLDIER;
        }

        return false;
    }

    public boolean canTakePosition(Player taker, MilitaryPosition target) {
        return getPosition(taker).getLevel() > target.getLevel();
    }

    public MilitaryRank getRank(Player player) {
        Long startTime = serviceTime.get(player.getUniqueId());
        if (startTime == null) return MilitaryRank.PRIVATE;

        long seconds = (System.currentTimeMillis() - startTime) / 1000;
        return MilitaryRank.getByTime(seconds);
    }

    public long getServiceTime(Player player) {
        Long startTime = serviceTime.get(player.getUniqueId());
        if (startTime == null) return 0;
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    public void addServiceTime(Player player, long seconds) {
        Long current = serviceTime.get(player.getUniqueId());
        if (current == null) {
            current = System.currentTimeMillis() - (seconds * 1000);
        } else {
            current -= (seconds * 1000);
        }
        serviceTime.put(player.getUniqueId(), current);
        saveData();
    }

    // ─── ФИЛЬТРАЦИЯ ПО СТРАНЕ ─────────────────────────────────────────────

    /**
     * Все солдаты ВСЕХ стран.
     */
    public List<Player> getArmyMembers() {
        List<Player> members = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (getPosition(player) != MilitaryPosition.NONE) {
                members.add(player);
            }
        }
        return members;
    }

    /**
     * Все солдаты ТОЛЬКО страны офицера/главы.
     */
    public List<Player> getSubordinates(Player officer) {
        String officerCountry = getCountry(officer);
        if (officerCountry == null) return Collections.emptyList();

        MilitaryPosition pos = getPosition(officer);
        List<Player> subs = new ArrayList<>();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.equals(officer)) continue;

            String playerCountry = getCountry(player);
            if (!officerCountry.equals(playerCountry)) continue;

            MilitaryPosition pPos = getPosition(player);

            if (pos == MilitaryPosition.ARMY_LEADER && pPos.getLevel() < MilitaryPosition.ARMY_LEADER.getLevel()) {
                subs.add(player);
            } else if (pos == MilitaryPosition.OFFICER && pPos == MilitaryPosition.SOLDIER) {
                subs.add(player);
            }
        }
        return subs;
    }

    // ─── ОТОБРАЖЕНИЕ ────────────────────────────────────────────────────────

    public String getFullTitle(Player player) {
        MilitaryPosition pos = getPosition(player);
        MilitaryRank rank = getRank(player);

        if (pos == MilitaryPosition.NONE) {
            return rank.getIcon() + " " + rank.getDisplay();
        }

        return pos.getIcon() + " " + rank.getIcon() + " " + rank.getDisplay();
    }

    public String getDisplayName(Player player) {
        MilitaryPosition pos = getPosition(player);
        MilitaryRank rank = getRank(player);

        if (pos == MilitaryPosition.NONE) {
            return rank.getIcon() + " " + player.getName();
        }

        return pos.getIcon() + " " + player.getName();
    }

    // ─── СОХРАНЕНИЕ ────────────────────────────────────────────────────────

    private void loadData() {
        var config = plugin.getConfig();

        var positionsSection = config.getConfigurationSection("positions");
        if (positionsSection != null) {
            for (String uuidStr : positionsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String posName = config.getString("positions." + uuidStr);
                    MilitaryPosition pos = MilitaryPosition.valueOf(posName);
                    playerPositions.put(uuid, pos);
                } catch (Exception ignored) {}
            }
        }

        var serviceSection = config.getConfigurationSection("service-time");
        if (serviceSection != null) {
            for (String uuidStr : serviceSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    long startTime = config.getLong("service-time." + uuidStr);
                    serviceTime.put(uuid, startTime);
                } catch (Exception ignored) {}
            }
        }
    }

    private void saveData() {
        var config = plugin.getConfig();

        for (Map.Entry<UUID, MilitaryPosition> entry : playerPositions.entrySet()) {
            config.set("positions." + entry.getKey().toString(), entry.getValue().name());
        }

        for (Map.Entry<UUID, Long> entry : serviceTime.entrySet()) {
            config.set("service-time." + entry.getKey().toString(), entry.getValue());
        }

        plugin.saveConfig();
    }

    private void updatePlayer(Player player) {
        player.setPlayerListName(getDisplayName(player));
    }
}
