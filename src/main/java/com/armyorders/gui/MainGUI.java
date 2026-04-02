package com.armyorders.gui;

import com.armyorders.ArmyOrders;
import com.armyorders.manager.OrderManager;
import com.armyorders.manager.RankManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemFlag;

import java.util.*;

/**
 * Главное GUI меню ArmyOrders.
 */
public class MainGUI implements Listener {

    private final ArmyOrders plugin;
    private static final String TITLE = "§6⚔ Армейские Приказы";
    
    // Состояние игрока при выборе приказа
    private final Map<UUID, Player> selectingTarget = new HashMap<>();
    private final Map<UUID, Player> selectingOrderType = new HashMap<>();
    private final Map<UUID, GiveOrderData> pendingOrder = new HashMap<>();
    private final Map<UUID, InventoryClickEvent> lastClickEvent = new HashMap<>();
    
    private static class GiveOrderData {
        public Player target;
        public OrderManager.OrderType orderType;
        public int duration;
        
        public GiveOrderData(Player target, OrderManager.OrderType type, int duration) {
            this.target = target;
            this.orderType = type;
            this.duration = duration;
        }
    }
    
    public MainGUI(ArmyOrders plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }
    
    public void openMain(Player player) {
        // Очищаем состояние
        clearState(player);
        
        Inventory inv = Bukkit.createInventory(null, 36, TITLE);
        
        var rankManager = plugin.getRankManager();
        var orderManager = plugin.getOrderManager();
        
        // Заполняем рамку
        fillBorder(inv);
        
        // Проверяем должность
        var position = rankManager.getPosition(player);
        
        if (position == RankManager.MilitaryPosition.NONE) {
            // Нет должности - показываем информацию
            inv.setItem(13, makeItem(Material.BOOK,
                    "§e§l🏅 ВАШЕ ЗВАНИЕ",
                    "§7Звание: §f" + rankManager.getRank(player).getDisplay(),
                    "§7Время службы: §f" + formatServiceTime(rankManager.getServiceTime(player)),
                    "",
                    "§7Вы не состоите в армии.",
                    "§7Обратитесь к Правителю страны."
            ));
            
            player.openInventory(inv);
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.3f);
            return;
        }
        
        // Звание игрока
        var rank = rankManager.getRank(player);
        inv.setItem(4, makeItem(Material.PAPER,
                "§e§l🏅 МОЁ ЗВАНИЕ",
                "§7Должность: §f" + position.getIcon() + " " + position.getDisplay(),
                "§7Звание: §f" + rank.getIcon() + " " + rank.getDisplay(),
                "§7Время службы: §f" + formatServiceTime(rankManager.getServiceTime(player)),
                "§7Следующее: §f" + getNextRankInfo(rank)
        ));
        
        // Статистика
        var stats = orderManager.getStats(player);
        inv.setItem(22, makeItem(Material.BOOK,
                "§b§l📊 СТАТИСТИКА",
                "§7Выполнено приказов: §a" + stats.completed,
                "§7Провалено приказов: §c" + stats.failed,
                "§7Выдано приказов: §f" + stats.given
        ));
        
        // ДАТЬ ПРИКАЗ (только для ОФИЦЕРА и выше)
        if (position.getLevel() >= RankManager.MilitaryPosition.OFFICER.getLevel()) {
            inv.setItem(20, makeItem(Material.COMPASS,
                    "§a§l🎯 ДАТЬ ПРИКАЗ",
                    "§7Выбрать солдата и выдать приказ"
            ));
        }
        
        // АКТИВНЫЕ ПРИКАЗЫ
        var myOrders = orderManager.getOrdersByIssuer(player);
        String activeText = myOrders.isEmpty() ? "§7Нет активных приказов"
                : "§e" + myOrders.size() + " §7активных";
        inv.setItem(24, makeItem(myOrders.isEmpty() ? Material.BLAZE_ROD : Material.REDSTONE,
                "§c§l📋 АКТИВНЫЕ ПРИКАЗЫ",
                activeText
        ));
        
        // МОЙ ПРИКАЗ (если выполняю)
        var currentOrder = orderManager.getOrder(player);
        if (currentOrder != null) {
            inv.setItem(13, makeItem(Material.BEACON,
                    "§e§l🎯 ВЫПОЛНЯЕТЕ ПРИКАЗ",
                    "§7Тип: §f" + currentOrder.type.getDisplay(),
                    "§7Осталось: §e" + OrderManager.formatTime(currentOrder.getRemainingSeconds()),
                    "§7",
                    "§7Ожидайте решения офицера..."
            ));
        }
        
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.3f);
    }
    
    // ─── ВЫБОР СОЛДАТА ─────────────────────────────────────────────────────
    
    public void openSelectTarget(Player player) {
        selectingTarget.put(player.getUniqueId(), player);
        
        Inventory inv = Bukkit.createInventory(null, 54, "§6⚔ Выбор солдата");
        
        fillBorder(inv);
        
        var subordinates = plugin.getRankManager().getSubordinates(player);
        
        if (subordinates.isEmpty()) {
            inv.setItem(22, makeItem(Material.BARRIER,
                    "§c§l✕ НЕТ ПОДЧИНЁННЫХ",
                    "§7У вас нет солдат в подчинении!"
            ));
        } else {
            int slot = 10;
            for (Player sub : subordinates) {
                if (slot > 43) break;
                
                // Пропускаем слоты с рамкой
                while (slot % 9 == 0 || slot % 9 == 8) slot++;
                if (slot > 43) break;
                
                var subPos = plugin.getRankManager().getPosition(sub);
                var subRank = plugin.getRankManager().getRank(sub);
                boolean hasOrder = plugin.getOrderManager().hasOrder(sub);
                
                Material mat = hasOrder ? Material.BEACON : Material.PLAYER_HEAD;
                String status = hasOrder ? " §c(занят)" : " §a(свободен)";
                
                inv.setItem(slot, makeItem(mat,
                        "§f" + sub.getName(),
                        "§7Должность: " + subPos.getIcon() + " " + subPos.getDisplay(),
                        "§7Звание: " + subRank.getIcon() + " " + subRank.getDisplay(),
                        status
                ));
                
                slot++;
            }
        }
        
        // Кнопка назад
        inv.setItem(49, makeItem(Material.ARROW,
                "§e◀ Назад",
                "§7Вернуться в главное меню"
        ));
        
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
    
    // ─── ВЫБОР ТИПА ПРИКАЗА ─────────────────────────────────────────────────
    
    public void openSelectOrderType(Player player, Player target) {
        selectingOrderType.put(player.getUniqueId(), target);
        
        Inventory inv = Bukkit.createInventory(null, 27, "§6⚔ Выбор приказа");
        
        fillBorder(inv);
        
        // Приказы
        OrderManager.OrderType[] types = OrderManager.OrderType.values();
        int[] slots = {11, 13, 15, 20, 22, 24};
        
        for (int i = 0; i < types.length && i < slots.length; i++) {
            OrderManager.OrderType type = types[i];
            inv.setItem(slots[i], makeItem(Material.PAPER,
                    "§e" + type.getIcon() + " " + type.getDisplay(),
                    "§7" + type.getDescription()
            ));
        }
        
        // Кнопка назад
        inv.setItem(22, makeItem(Material.ARROW,
                "§e◀ Назад",
                "§7Выбрать другого солдата"
        ));
        
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
    
    // ─── ВЫБОР ДЛИТЕЛЬНОСТИ ─────────────────────────────────────────────────
    
    public void openSelectDuration(Player player, Player target, OrderManager.OrderType type) {
        pendingOrder.put(player.getUniqueId(), new GiveOrderData(target, type, 600));
        
        Inventory inv = Bukkit.createInventory(null, 27, "§6⚔ Длительность приказа");
        
        fillBorder(inv);
        
        // Время: 5, 10, 15, 30 минут
        int[] durations = {300, 600, 900, 1800};
        int[] slots = {11, 13, 15, 20};
        
        for (int i = 0; i < durations.length; i++) {
            int mins = durations[i] / 60;
            inv.setItem(slots[i], makeItem(Material.CLOCK,
                    "§e⏰ " + mins + " минут",
                    "§7Приказ на " + mins + " минут"
            ));
        }
        
        // Подтвердить
        inv.setItem(22, makeItem(Material.LIME_STAINED_GLASS,
                "§a§l✓ ПОДТВЕРДИТЬ",
                "§7Дать приказ '" + type.getDisplay() + "'",
                "§7Игроку: " + target.getName()
        ));
        
        // Кнопка назад
        inv.setItem(18, makeItem(Material.ARROW,
                "§e◀ Назад",
                "§7Выбрать другой приказ"
        ));
        
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
    
    // ─── АКТИВНЫЕ ПРИКАЗЫ ──────────────────────────────────────────────────
    
    public void openActiveOrders(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§c📋 Активные приказы");
        
        fillBorder(inv);
        
        var myOrders = plugin.getOrderManager().getOrdersByIssuer(player);
        
        if (myOrders.isEmpty()) {
            inv.setItem(22, makeItem(Material.BARRIER,
                    "§c§l✕ НЕТ АКТИВНЫХ ПРИКАЗОВ",
                    "§7Вы не выдавали приказов",
                    "§7или все уже завершены"
            ));
        } else {
            int slot = 10;
            for (OrderManager.ActiveOrder order : myOrders) {
                if (slot > 43) break;
                while (slot % 9 == 0 || slot % 9 == 8) slot++;
                if (slot > 43) break;
                
                Player target = order.getTarget();
                if (target == null) continue;
                
                String remaining = OrderManager.formatTime(order.getRemainingSeconds());
                
                inv.setItem(slot, makeItem(Material.BEACON,
                        "§e" + target.getName(),
                        "§7Приказ: " + order.type.getIcon() + " " + order.type.getDisplay(),
                        "§7Осталось: §c" + remaining,
                        "",
                        "§a§nЛКМ - Выполнен",
                        "§c§nПКМ - Наказание"
                ));
                
                slot++;
            }
        }
        
        // Кнопка назад
        inv.setItem(49, makeItem(Material.ARROW,
                "§e◀ Назад",
                "§7Вернуться в главное меню"
        ));
        
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
    
    // ─── ОБРАБОТКА КЛИКОВ ──────────────────────────────────────────────────
    
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        // Сохраняем событие для использования в других методах
        lastClickEvent.put(player.getUniqueId(), event);
        
        String title = event.getView().getTitle();
        
        // Проверяем наши GUI
        if (!title.equals(TITLE) && !title.equals("§6⚔ Выбор солдата") 
                && !title.equals("§6⚔ Выбор приказа") && !title.equals("§6⚔ Длительность приказа")
                && !title.equals("§c📋 Активные приказы")) return;
        
        event.setCancelled(true);
        
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        
        // Рамка
        if (clicked.getType() == Material.BLACK_STAINED_GLASS_PANE) return;
        
        // Главное меню
        if (title.equals(TITLE)) {
            handleMainClick(player, clicked);
            return;
        }
        
        // Выбор солдата
        if (title.equals("§6⚔ Выбор солдата")) {
            handleSelectTargetClick(player, clicked);
            return;
        }
        
        // Выбор приказа
        if (title.equals("§6⚔ Выбор приказа")) {
            handleSelectOrderClick(player, clicked);
            return;
        }
        
        // Выбор длительности
        if (title.equals("§6⚔ Длительность приказа")) {
            handleSelectDurationClick(player, clicked);
            return;
        }
        
        // Активные приказы
        if (title.equals("§c📋 Активные приказы")) {
            lastClickEvent.put(player.getUniqueId(), event);
            handleActiveOrdersClick(player, event.isRightClick());
            return;
        }
    }
    
    private void handleMainClick(Player player, ItemStack clicked) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        
        Material mat = clicked.getType();
        
        if (mat == Material.COMPASS) {
            // ДАТЬ ПРИКАЗ
            openSelectTarget(player);
        } else if (mat == Material.REDSTONE || mat == Material.BLAZE_ROD) {
            // АКТИВНЫЕ ПРИКАЗЫ
            openActiveOrders(player);
        }
    }
    
    private void handleSelectTargetClick(Player player, ItemStack clicked) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        
        if (clicked.getType() == Material.ARROW) {
            openMain(player);
            return;
        }
        
        Material mat = clicked.getType();
        if (mat == Material.PLAYER_HEAD || mat == Material.BEACON) {
            String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            Player target = Bukkit.getPlayer(name);
            if (target != null) {
                openSelectOrderType(player, target);
            }
        }
    }
    
    private void handleSelectOrderClick(Player player, ItemStack clicked) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        
        if (clicked.getType() == Material.ARROW) {
            openSelectTarget(player);
            return;
        }
        
        if (clicked.getItemMeta().getDisplayName() != null) {
            String displayName = clicked.getItemMeta().getDisplayName();
            
            for (OrderManager.OrderType type : OrderManager.OrderType.values()) {
                if (displayName.contains(type.getDisplay())) {
                    Player target = selectingOrderType.get(player.getUniqueId());
                    if (target != null) {
                        openSelectDuration(player, target, type);
                    }
                    return;
                }
            }
        }
        
        openMain(player);
    }
    
    private void handleSelectDurationClick(Player player, ItemStack clicked) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        
        GiveOrderData data = pendingOrder.get(player.getUniqueId());
        
        if (clicked.getType() == Material.ARROW) {
            Player target = selectingOrderType.remove(player.getUniqueId());
            if (target != null) {
                openSelectOrderType(player, target);
            }
            return;
        }
        
        if (clicked.getType() == Material.LIME_STAINED_GLASS) {
            // Подтверждаем приказ
            if (data != null) {
                plugin.getOrderManager().giveOrder(player, data.target, data.orderType, data.duration);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
            }
            clearState(player);
            openMain(player);
            return;
        }
        
        // Выбор длительности
        int[] durations = {300, 600, 900, 1800};
        int[] slots = {11, 13, 15, 20};
        
        for (int i = 0; i < durations.length; i++) {
            int mins = durations[i] / 60;
            if (clicked.getItemMeta().getDisplayName() != null && 
                    clicked.getItemMeta().getDisplayName().contains(mins + " минут")) {
                if (data != null) {
                    data.duration = durations[i];
                }
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                // Обновляем GUI
                if (data != null) {
                    openSelectDuration(player, data.target, data.orderType);
                }
                return;
            }
        }
    }
    
    private void handleActiveOrdersClick(Player player, boolean isRightClick) {
        // Получаем кликнутый предмет из инвентаря игрока
        InventoryClickEvent recentEvent = lastClickEvent.get(player.getUniqueId());
        ItemStack clicked = recentEvent != null ? recentEvent.getCurrentItem() : null;
        if (clicked == null) return;
        
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        
        if (clicked.getType() == Material.ARROW) {
            openMain(player);
            return;
        }
        
        if (clicked.getType() == Material.BEACON) {
            String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            Player target = Bukkit.getPlayer(name);
            if (target == null) return;
            
            if (isRightClick) {
                // Наказание
                plugin.getOrderManager().punishOrder(player, target);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 0.8f);
            } else {
                // Выполнено
                plugin.getOrderManager().completeOrder(player, target);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            }
            
            openActiveOrders(player);
        }
    }
    
    // ─── ВСПОМОГАТЕЛЬНЫЕ ──────────────────────────────────────────────────
    
    private void clearState(Player player) {
        UUID uuid = player.getUniqueId();
        selectingTarget.remove(uuid);
        selectingOrderType.remove(uuid);
        pendingOrder.remove(uuid);
    }
    
    private void fillBorder(Inventory inv) {
        ItemStack border = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        int size = inv.getSize();
        
        // Верхняя рамка
        for (int i = 0; i < 9; i++) inv.setItem(i, border);
        
        // Боковые рамки (если есть место)
        if (size > 9) inv.setItem(9, border);
        if (size > 17) inv.setItem(17, border);
        if (size > 27) inv.setItem(27, border);
        if (size > 35) inv.setItem(35, border);
        
        // Нижняя рамка (для 36 слотов: 27-35, для 54: 45-53)
        int bottomStart = size - 9;
        for (int i = bottomStart; i < size; i++) inv.setItem(i, border);
    }
    
    private ItemStack makeItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }
    
    private String formatServiceTime(long seconds) {
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        if (hours > 0) {
            return hours + "ч " + mins + "м";
        }
        return mins + " минут";
    }
    
    private String getNextRankInfo(RankManager.MilitaryRank rank) {
        var next = RankManager.MilitaryRank.getNext(rank);
        if (next == null) return "§aМаксимум!";
        
        long remaining = next.getSecondsRequired() - rank.getSecondsRequired();
        return "§f" + next.getIcon() + " " + next.getDisplay() + " (§7+" + formatServiceTime(remaining) + "§f)";
    }
}
