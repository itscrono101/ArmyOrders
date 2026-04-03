package com.armyorders.manager;

import com.armyorders.ArmyOrders;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class RankManager {

    private final ArmyOrders plugin;

    private final Map<UUID, MilitaryRole> playerRoles = new HashMap<>();
    private final Map<UUID, Long> serviceTime = new HashMap<>();
    private final Map<UUID, String> playerCountries = new HashMap<>();

    // ===== MILITARY ROLE (DOLZHNOST) =====
    public enum MilitaryRole {
        NONE("Grazhdanskiy", "", 0, false),
        PRIVATE_INFANTRY("Pekhotinets", "\u26A0", 1, false),
        PRIVATE_MEDIC("Medik", "\u271A", 1, false),
        PRIVATE_ENGINEER("Sapyor", "\u26CF", 1, false),
        PRIVATE_SCOUT("Razvedchik", "\u263D", 1, false),
        PLATOON_LEADER("Komandir vzVoda", "\u2691", 2, true),
        STAFF_OFFICER("Shtabnyy ofitser", "\uD83D\uDCDD", 2, true),
        ARMY_COMMANDER("Komanduyushchiy armiey", "\u269C", 3, true);

        private final String display;
        private final String icon;
        private final int level;
        private final boolean officer;

        MilitaryRole(String display, String icon, int level, boolean officer) {
            this.display = display;
            this.icon = icon;
            this.level = level;
            this.officer = officer;
        }

        public String getDisplay() { return display; }
        public String getIcon() { return icon; }
        public int getLevel() { return level; }
        public boolean isOfficer() { return officer; }
        public boolean isNone() { return this == NONE; }

        public boolean canHaveWithRank(MilitaryRank rank) {
            if (this == NONE) return true;
            if (this.officer) return rank.getLevel() >= MilitaryRank.LIEUTENANT.getLevel();
            return true;
        }
    }

    // ===== MILITARY RANK (ZVANIE) =====
    public enum MilitaryRank {
        PRIVATE("Ryadovyy", "I", 0, 0),
        CORPORAL("Efreytor", "II", 1, 3600),
        SERGEANT("Serzhant", "III", 2, 18000),
        LIEUTENANT("Leytenant", "IV", 3, 43200),
        CAPTAIN("Kapitan", "V", 4, 86400),
        MAJOR("Mayor", "VI", 5, 172800),
        COLONEL("Polkovnik", "VII", 6, 345600),
        COMMANDER_IN_CHIEF("Glavnokomanduyushchiy", "\u2605", 7, Long.MAX_VALUE);

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
            MilitaryRank[] v = values();
            return current.ordinal() + 1 < v.length ? v[current.ordinal() + 1] : null;
        }

        public static MilitaryRank getByTime(long seconds) {
            MilitaryRank result = PRIVATE;
            for (MilitaryRank r : values()) { if (seconds >= r.getSecondsRequired()) result = r; }
            return result;
        }
    }

    public RankManager(ArmyOrders plugin) {
        this.plugin = plugin;
        loadData();
    }

    // ===== STRANA =====
    public String getCountry(Player player) {
        String cached = playerCountries.get(player.getUniqueId());
        if (cached != null) return cached;

        if (player.hasPermission("clp.leader")) {
            String c = getCountryViaReflection(player);
            if (c != null) {
                playerCountries.put(player.getUniqueId(), c);
                if (getRole(player) == MilitaryRole.NONE) setRole(player, MilitaryRole.ARMY_COMMANDER);
            }
            return c;
        }

        String c = getCountryViaReflection(player);
        if (c != null) playerCountries.put(player.getUniqueId(), c);
        return c;
    }

    private String getCountryViaReflection(Player player) {
        try {
            Plugin clp = plugin.getServer().getPluginManager().getPlugin("CountryisLeaderPerms");
            if (clp == null) return null;
            Object cm = clp.getClass().getMethod("getCountryManager").invoke(clp);
            if (cm == null) return null;
            Object profile = cm.getClass().getMethod("getProfile", UUID.class).invoke(cm, player.getUniqueId());
            if (profile == null) return null;
            return (String) profile.getClass().getMethod("getCountryName").invoke(profile);
        } catch (Exception e) { return null; }
    }

    public void setCountry(Player player, String countryName) {
        if (countryName == null) playerCountries.remove(player.getUniqueId());
        else playerCountries.put(player.getUniqueId(), countryName);
    }

    public boolean isSameCountry(Player a, Player b) {
        String ca = getCountry(a), cb = getCountry(b);
        return ca != null && ca.equals(cb);
    }

    public boolean isInArmy(Player player) { return getRole(player) != MilitaryRole.NONE; }

    // ===== ROLE (DOLZHNOST) =====
    public boolean setRole(Player player, MilitaryRole role) {
        MilitaryRank rank = getRank(player);
        if (role != MilitaryRole.NONE && !role.canHaveWithRank(rank)) return false;
        playerRoles.put(player.getUniqueId(), role);
        String country = getCountry(player);
        if (country != null) playerCountries.put(player.getUniqueId(), country);
        saveData();
        updatePlayer(player);
        return true;
    }

    public MilitaryRole getRole(Player player) {
        if (player.hasPermission("clp.leader")) {
            MilitaryRole current = playerRoles.get(player.getUniqueId());
            return (current == null || current == MilitaryRole.NONE) ? MilitaryRole.ARMY_COMMANDER : current;
        }
        return playerRoles.getOrDefault(player.getUniqueId(), MilitaryRole.NONE);
    }

    public boolean hasRole(Player player, MilitaryRole required) {
        return getRole(player).getLevel() >= required.getLevel();
    }

    public boolean canGiveRole(Player giver, MilitaryRole newRole) {
        MilitaryRole giverRole = getRole(giver);
        if (giver.hasPermission("clp.leader")) return true;
        if (giverRole == MilitaryRole.NONE) return false;
        if (giverRole == MilitaryRole.ARMY_COMMANDER) return true;
        if (giverRole.isOfficer()) return !newRole.isOfficer();
        return false;
    }

    public boolean canTakeRole(Player taker, MilitaryRole targetRole) {
        MilitaryRole takerRole = getRole(taker);
        if (takerRole == MilitaryRole.NONE) return false;
        if (taker.hasPermission("clp.leader")) return true;
        return takerRole.getLevel() > targetRole.getLevel();
    }

    // ===== RANK (ZVANIE) =====
    public MilitaryRank getRank(Player player) {
        Long startTime = serviceTime.get(player.getUniqueId());
        if (startTime == null) return MilitaryRank.PRIVATE;
        return MilitaryRank.getByTime((System.currentTimeMillis() - startTime) / 1000);
    }

    public long getServiceTime(Player player) {
        Long t = serviceTime.get(player.getUniqueId());
        return t == null ? 0 : (System.currentTimeMillis() - t) / 1000;
    }

    public void addServiceTime(Player player, long seconds) {
        Long current = serviceTime.get(player.getUniqueId());
        serviceTime.put(player.getUniqueId(), current == null
            ? System.currentTimeMillis() - seconds * 1000 : current - seconds * 1000);
        saveData();
    }

    // ===== FILTRACIYA PO STRANE =====
    public List<Player> getArmyMembers() {
        List<Player> members = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers())
            if (getRole(p) != MilitaryRole.NONE) members.add(p);
        return members;
    }

    public List<Player> getSubordinates(Player officer) {
        String officerCountry = getCountry(officer);
        if (officerCountry == null) return Collections.emptyList();
        MilitaryRole oRole = getRole(officer);
        List<Player> subs = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.equals(officer)) continue;
            String pc = getCountry(p);
            if (!officerCountry.equals(pc)) continue;
            if (oRole.getLevel() > getRole(p).getLevel()) subs.add(p);
        }
        return subs;
    }

    // ===== TITLE =====
    public String getFullTitle(Player player) {
        MilitaryRole role = getRole(player);
        MilitaryRank rank = getRank(player);
        if (role == MilitaryRole.NONE) return rank.getIcon() + " " + rank.getDisplay();
        return role.getIcon() + " " + rank.getIcon() + " " + rank.getDisplay();
    }

    public String getDisplayName(Player player) {
        MilitaryRole role = getRole(player);
        if (role == MilitaryRole.NONE) return player.getName();
        return role.getIcon() + " " + player.getName();
    }

    // ===== SOHRANENIE =====
    private void loadData() {
        var config = plugin.getConfig();
        var roles = config.getConfigurationSection("roles");
        if (roles != null)
            for (String uuid : roles.getKeys(false))
                try { playerRoles.put(UUID.fromString(uuid), MilitaryRole.valueOf(config.getString("roles." + uuid))); } catch (Exception ignored) {}
        var service = config.getConfigurationSection("service-time");
        if (service != null)
            for (String uuid : service.getKeys(false))
                try { serviceTime.put(UUID.fromString(uuid), config.getLong("service-time." + uuid)); } catch (Exception ignored) {}
    }

    private void saveData() {
        var config = plugin.getConfig();
        for (Map.Entry<UUID, MilitaryRole> e : playerRoles.entrySet())
            config.set("roles." + e.getKey(), e.getValue().name());
        for (Map.Entry<UUID, Long> e : serviceTime.entrySet())
            config.set("service-time." + e.getKey(), e.getValue());
        plugin.saveConfig();
    }

    private void updatePlayer(Player player) { player.setPlayerListName(getDisplayName(player)); }
}
