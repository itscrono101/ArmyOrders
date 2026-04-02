package com.armyorders.commands;

import com.armyorders.ArmyOrders;
import com.armyorders.gui.MainGUI;
import com.armyorders.manager.OrderManager;
import com.armyorders.manager.RankManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ArmyCommand implements CommandExecutor, TabCompleter {

    private final ArmyOrders plugin;
    
    public ArmyCommand(ArmyOrders plugin) {
        this.plugin = plugin;
        // Tab completer устанавливается после регистрации команды
    }
    
    public void registerTabCompleter() {
        var cmd = plugin.getCommand("mo");
        if (cmd != null) {
            cmd.setTabCompleter(this);
        }
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Только для игроков!");
            return true;
        }
        
        if (args.length == 0) {
            // Открываем GUI
            plugin.getMainGUI().openMain(player);
            return true;
        }
        
        String sub = args[0].toLowerCase();
        
        switch (sub) {
            case "help" -> sendHelp(player);
            case "position", "pos" -> handlePosition(player, args);
            case "give" -> handleGivePosition(player, args);
            case "take" -> handleTakePosition(player, args);
            case "stats" -> handleStats(player, args);
            case "reload" -> handleReload(player);
            default -> player.sendMessage(ChatColor.RED + "Неизвестная команда. Используйте /mo help");
        }
        
        return true;
    }
    
    // ─── /mo help ─────────────────────────────────────────────────────────
    
    private void sendHelp(Player player) {
        var position = plugin.getRankManager().getPosition(player);
        boolean isArmyLeader = position == RankManager.MilitaryPosition.ARMY_LEADER;
        boolean isOfficer = position == RankManager.MilitaryPosition.OFFICER;
        
        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "╔══════════════════════════════════════╗");
        player.sendMessage(ChatColor.DARK_GRAY + "║ " + ChatColor.GOLD + "⚔ АРМЕЙСКИЕ ПРИКАЗЫ " + ChatColor.DARK_GRAY + "(/mo)" + " ".repeat(14) + "║");
        player.sendMessage(ChatColor.DARK_GRAY + "╠══════════════════════════════════════╣");
        player.sendMessage(ChatColor.DARK_GRAY + "║ " + ChatColor.WHITE + "Ваша должность: " + getPositionColor(position) + position.getIcon() + " " + position.getDisplay());
        player.sendMessage(ChatColor.DARK_GRAY + "╚══════════════════════════════════════╝");
        player.sendMessage("");
        
        player.sendMessage(ChatColor.GRAY + "──── " + ChatColor.WHITE + "ОСНОВНОЕ" + ChatColor.GRAY + "───────────────────────────");
        player.sendMessage("  " + ChatColor.YELLOW + "/mo" + ChatColor.GRAY + " — открыть меню");
        player.sendMessage("  " + ChatColor.YELLOW + "/mo stats [игрок]" + ChatColor.GRAY + " — статистика");
        player.sendMessage("");
        
        if (isArmyLeader || isOfficer) {
            player.sendMessage(ChatColor.GRAY + "──── " + ChatColor.RED + "ОФИЦЕР" + ChatColor.GRAY + "──────────────────────────");
            player.sendMessage("  " + ChatColor.YELLOW + "/mo gui" + ChatColor.GRAY + " — открыть меню приказов");
        }
        
        if (isArmyLeader) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + "──── " + ChatColor.GOLD + "ГЛАВА АРМИЙ" + ChatColor.GRAY + "────────────────────");
            player.sendMessage("  " + ChatColor.YELLOW + "/mo give <игрок> officer" + ChatColor.GRAY + " — назначить офицером");
            player.sendMessage("  " + ChatColor.YELLOW + "/mo give <игрок> soldier" + ChatColor.GRAY + " — назначить солдатом");
            player.sendMessage("  " + ChatColor.YELLOW + "/mo take <игрок>" + ChatColor.GRAY + " — снять с должности");
        }
        
        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "══════════════════════════════════════════");
    }
    
    // ─── /mo position ─────────────────────────────────────────────────────
    
    private void handlePosition(Player player, String[] args) {
        if (args.length < 2) {
            // Показать свою должность
            var position = plugin.getRankManager().getPosition(player);
            var rank = plugin.getRankManager().getRank(player);
            long serviceTime = plugin.getRankManager().getServiceTime(player);
            
            player.sendMessage(ChatColor.GOLD + "═══ ВАША ДОЛЖНОСТЬ ═══");
            player.sendMessage(ChatColor.GRAY + "Должность: " + getPositionColor(position) + position.getIcon() + " " + position.getDisplay());
            player.sendMessage(ChatColor.GRAY + "Звание: " + rank.getIcon() + " " + rank.getDisplay());
            player.sendMessage(ChatColor.GRAY + "Служба: " + formatTime(serviceTime));
            player.sendMessage(ChatColor.GOLD + "═══════════════════════");
        } else {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Игрок не найден!");
                return;
            }
            
            var targetPos = plugin.getRankManager().getPosition(target);
            var targetRank = plugin.getRankManager().getRank(target);
            
            player.sendMessage(ChatColor.GOLD + "═══ " + target.getName() + " ═══");
            player.sendMessage(ChatColor.GRAY + "Должность: " + getPositionColor(targetPos) + targetPos.getIcon() + " " + targetPos.getDisplay());
            player.sendMessage(ChatColor.GRAY + "Звание: " + targetRank.getIcon() + " " + targetRank.getDisplay());
            player.sendMessage(ChatColor.GOLD + "════════════════════════");
        }
    }
    
    // ─── /mo give ──────────────────────────────────────────────────────────
    
    private void handleGivePosition(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(ChatColor.RED + "Использование: /mo give <игрок> <officer|soldier>");
            return;
        }
        
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Игрок не найден!");
            return;
        }
        
        String posStr = args[2].toLowerCase();
        RankManager.MilitaryPosition newPos;
        
        switch (posStr) {
            case "officer" -> newPos = RankManager.MilitaryPosition.OFFICER;
            case "soldier" -> newPos = RankManager.MilitaryPosition.SOLDIER;
            case "army" -> newPos = RankManager.MilitaryPosition.ARMY_LEADER;
            default -> {
                player.sendMessage(ChatColor.RED + "Неверная должность! officer, soldier, army");
                return;
            }
        }
        
        // Проверяем права
        if (!plugin.getRankManager().canGivePosition(player, newPos)) {
            player.sendMessage(ChatColor.RED + "Вы не можете назначить эту должность!");
            return;
        }
        
        // Назначаем
        plugin.getRankManager().setPosition(target, newPos);
        
        player.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.WHITE + "Вы назначили " 
                + ChatColor.YELLOW + target.getName() + ChatColor.WHITE + " на должность " 
                + ChatColor.YELLOW + newPos.getIcon() + " " + newPos.getDisplay());
        
        target.sendMessage(ChatColor.GOLD + "🎖 ВНИМАНИЕ! " + ChatColor.WHITE + "Вам присвоена должность!");
        target.sendMessage(ChatColor.YELLOW + newPos.getIcon() + " " + newPos.getDisplay());
    }
    
    // ─── /mo take ──────────────────────────────────────────────────────────
    
    private void handleTakePosition(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Использование: /mo take <игрок>");
            return;
        }
        
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Игрок не найден!");
            return;
        }
        
        var targetPos = plugin.getRankManager().getPosition(target);
        
        if (targetPos == RankManager.MilitaryPosition.NONE) {
            player.sendMessage(ChatColor.RED + "Этот игрок не имеет должности!");
            return;
        }
        
        if (!plugin.getRankManager().canTakePosition(player, targetPos)) {
            player.sendMessage(ChatColor.RED + "Вы не можете снять этого игрока!");
            return;
        }
        
        plugin.getRankManager().setPosition(target, RankManager.MilitaryPosition.NONE);
        
        player.sendMessage(ChatColor.YELLOW + "Вы сняли " + target.getName() + " с должности!");
        target.sendMessage(ChatColor.RED + "Вас сняли с должности!");
    }
    
    // ─── /mo stats ────────────────────────────────────────────────────────
    
    private void handleStats(Player player, String[] args) {
        Player target = player;
        if (args.length > 1) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Игрок не найден!");
                return;
            }
        }
        
        var stats = plugin.getOrderManager().getStats(target);
        var position = plugin.getRankManager().getPosition(target);
        var rank = plugin.getRankManager().getRank(target);
        
        player.sendMessage(ChatColor.GOLD + "═══ СТАТИСТИКА: " + ChatColor.WHITE + target.getName() + " ═══");
        player.sendMessage(ChatColor.GRAY + "Должность: " + getPositionColor(position) + position.getIcon() + " " + position.getDisplay());
        player.sendMessage(ChatColor.GRAY + "Звание: " + rank.getIcon() + " " + rank.getDisplay());
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "✓ Выполнено приказов: " + stats.completed);
        player.sendMessage(ChatColor.RED + "✗ Провалено приказов: " + stats.failed);
        player.sendMessage(ChatColor.WHITE + "📋 Выдано приказов: " + stats.given);
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
    }
    
    // ─── /mo reload ──────────────────────────────────────────────────────
    
    private void handleReload(Player player) {
        if (!player.hasPermission("armyorders.admin")) {
            player.sendMessage(ChatColor.RED + "Нет прав!");
            return;
        }
        
        plugin.reloadConfig();
        player.sendMessage(ChatColor.GREEN + "✓ Конфигурация перезагружена!");
    }
    
    // ─── TAB COMPLETER ────────────────────────────────────────────────────
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> subs = Arrays.asList("help", "stats", "position");
            
            Player player = (Player) sender;
            var pos = plugin.getRankManager().getPosition(player);
            
            if (pos.getLevel() >= RankManager.MilitaryPosition.OFFICER.getLevel()) {
                subs = new ArrayList<>(subs);
                subs.add("give");
            }
            
            if (pos == RankManager.MilitaryPosition.ARMY_LEADER) {
                subs.add("take");
            }
            
            if (player.hasPermission("armyorders.admin")) {
                subs.add("reload");
            }
            
            StringUtil.copyPartialMatches(args[0], subs, completions);
        }
        
        return completions;
    }
    
    // ─── ВСПОМОГАТЕЛЬНЫЕ ─────────────────────────────────────────────────
    
    private ChatColor getPositionColor(RankManager.MilitaryPosition pos) {
        return switch (pos) {
            case ARMY_LEADER -> ChatColor.GOLD;
            case OFFICER -> ChatColor.RED;
            case SOLDIER -> ChatColor.YELLOW;
            default -> ChatColor.GRAY;
        };
    }
    
    private String formatTime(long seconds) {
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        if (hours > 0) {
            return hours + "ч " + mins + "м";
        }
        return mins + "м";
    }
}
