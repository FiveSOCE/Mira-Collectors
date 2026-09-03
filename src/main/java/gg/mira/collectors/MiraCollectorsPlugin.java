package gg.mira.collectors;

import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MiraCollectorsPlugin extends JavaPlugin implements Listener {
    private static final String PREFIX = "&5&lMira &8>> &r";
    private NamespacedKey itemKey, ownerKey, levelKey, modeKey;
    private final Map<String, CollectorData> collectors = new HashMap<>();
    private File file;
    private Economy economy;

    @Override public void onEnable() {
        itemKey = new NamespacedKey(this, "collector_item"); ownerKey = new NamespacedKey(this, "owner");
        levelKey = new NamespacedKey(this, "level"); modeKey = new NamespacedKey(this, "mode");
        file = new File(getDataFolder(), "collectors.yml"); load();
        var reg = getServer().getServicesManager().getRegistration(Economy.class); economy = reg == null ? null : reg.getProvider();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 100L, 100L);
    }

    @Override public void onDisable() { save(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("miracollectors.admin")) { msg(sender, "&cNo permission."); return true; }
            if (args.length < 2) { msg(sender, "&cUsage: /collector give <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[1]); if (target == null) { msg(sender, "&cPlayer not online."); return true; }
            target.getInventory().addItem(createCollectorItem()); msg(sender, "&aCollector given."); return true;
        }
        if (!(sender instanceof Player player)) { msg(sender, "&cPlayers only."); return true; }
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Barrel barrel) || !barrel.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            msg(player, "&cLook at a MiraCollector within 6 blocks."); return true;
        }
        UUID owner = UUID.fromString(barrel.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
        if (!owner.equals(player.getUniqueId()) && !player.hasPermission("miracollectors.admin")) { msg(player, "&cThat collector is not yours."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            int level = barrel.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1);
            String mode = barrel.getPersistentDataContainer().getOrDefault(modeKey, PersistentDataType.STRING, "STORE");
            msg(player, "&6Collector &7Level &f" + level + " &7Radius &f" + radius(level) + " &7Mode &f" + mode);
            return true;
        }
        if (args[0].equalsIgnoreCase("mode")) {
            if (args.length < 2 || (!args[1].equalsIgnoreCase("store") && !args[1].equalsIgnoreCase("sell"))) { msg(player, "&cUsage: /collector mode <store|sell>"); return true; }
            String mode = args[1].toUpperCase(Locale.ROOT);
            if (mode.equals("SELL") && (economy == null || Bukkit.getPluginManager().getPlugin("MiraShop") == null)) { msg(player, "&cSELL mode requires Vault and MiraShop."); return true; }
            barrel.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode); barrel.update(true);
            updateRegistry(block, barrel); msg(player, "&aCollector mode set to " + mode + "."); return true;
        }
        if (args[0].equalsIgnoreCase("upgrade")) {
            int level = barrel.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1);
            if (level >= 5) { msg(player, "&eCollector is already max level."); return true; }
            int cost = level * 8;
            if (!player.hasPermission("miracollectors.admin")) {
                ItemStack price = new ItemStack(Material.DIAMOND, cost);
                if (!player.getInventory().containsAtLeast(price, cost)) { msg(player, "&cUpgrade requires " + cost + " diamonds."); return true; }
                player.getInventory().removeItem(price);
            }
            level++; barrel.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level); barrel.update(true);
            updateRegistry(block, barrel); msg(player, "&aCollector upgraded to level " + level + " (radius " + radius(level) + ")."); return true;
        }
        msg(player, "&7/collector info, mode <store|sell>, upgrade"); return true;
    }

    private ItemStack createCollectorItem() {
        ItemStack item = new ItemStack(Material.BARREL); ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text("Mira Collector")); meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte)1);
        meta.lore(List.of(Component.text("Collects nearby dropped items."), Component.text("Place, then use /collector while looking at it.")));
        item.setItemMeta(meta); return item;
    }

    @EventHandler public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!inHand.hasItemMeta() || !inHand.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE)) return;
        if (!(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;
        barrel.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
        barrel.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, 1);
        barrel.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, "STORE"); barrel.update(true);
        updateRegistry(event.getBlockPlaced(), barrel);
    }

    @EventHandler public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Barrel barrel) || !barrel.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) return;
        String owner = barrel.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (!event.getPlayer().getUniqueId().toString().equals(owner) && !event.getPlayer().hasPermission("miracollectors.admin")) {
            event.setCancelled(true); msg(event.getPlayer(), "&cThat collector is not yours."); return;
        }
        collectors.remove(key(event.getBlock().getLocation())); save();
        event.setDropItems(false); event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), createCollectorItem());
    }

    private void tick() {
        for (CollectorData data : new ArrayList<>(collectors.values())) {
            World world = Bukkit.getWorld(data.world); if (world == null || !world.isChunkLoaded(data.x >> 4, data.z >> 4)) continue;
            Block block = world.getBlockAt(data.x, data.y, data.z); if (!(block.getState() instanceof Barrel barrel)) continue;
            int r = radius(data.level);
            for (Item entity : world.getNearbyEntitiesByType(Item.class, block.getLocation().add(0.5,0.5,0.5), r, r, r)) {
                if (!entity.isValid() || entity.getPickupDelay() > 20) continue;
                if (data.mode.equals("SELL")) sell(entity, data.owner); else store(entity, barrel);
            }
        }
    }

    private void store(Item entity, Barrel barrel) {
        Map<Integer, ItemStack> left = barrel.getInventory().addItem(entity.getItemStack().clone());
        if (left.isEmpty()) entity.remove(); else entity.setItemStack(left.values().iterator().next());
    }

    private void sell(Item entity, UUID owner) {
        if (economy == null) return; Plugin shop = Bukkit.getPluginManager().getPlugin("MiraShop"); if (shop == null || !shop.isEnabled()) return;
        try {
            Object item = findShopItem(shop, entity.getItemStack()); if (item == null) return;
            Object sales = shop.getClass().getMethod("sales").invoke(shop); Object stats = shop.getClass().getMethod("stats").invoke(shop);
            double unit = ((Number) sales.getClass().getMethod("sellPrice", item.getClass()).invoke(sales, item)).doubleValue(); if (unit < 0) return;
            int amount = entity.getItemStack().getAmount(); double money = unit * amount;
            economy.depositPlayer(Bukkit.getOfflinePlayer(owner), money);
            stats.getClass().getMethod("recordSell", item.getClass(), int.class, double.class).invoke(stats, item, amount, money);
            entity.remove();
        } catch (ReflectiveOperationException ex) { getLogger().warning("MiraShop collector integration failed: " + ex.getMessage()); }
    }

    private Object findShopItem(Plugin shop, ItemStack stack) throws ReflectiveOperationException {
        Object catalog = shop.getClass().getMethod("catalog").invoke(shop); Collection<?> sections = (Collection<?>) catalog.getClass().getMethod("sections").invoke(catalog); Object generic = null;
        for (Object section : sections) for (Object item : (Collection<?>) section.getClass().getMethod("items").invoke(section)) {
            Material material = (Material)item.getClass().getMethod("material").invoke(item); if (material != stack.getType()) continue;
            boolean canSell = (boolean)item.getClass().getMethod("canSell").invoke(item); if (!canSell) continue;
            boolean custom = (boolean)item.getClass().getMethod("customTemplate").invoke(item);
            if (custom) { ItemStack template = (ItemStack)item.getClass().getMethod("template").invoke(item); if (stack.isSimilar(template)) return item; }
            else if (generic == null) generic = item;
        }
        return generic;
    }

    private void msg(CommandSender sender, String raw) { sender.sendMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + raw)); }
    private static int radius(int level) { return 4 + Math.max(1, Math.min(5, level)) * 2; }
    private void updateRegistry(Block block, Barrel barrel) {
        collectors.put(key(block.getLocation()), new CollectorData(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                UUID.fromString(barrel.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)),
                barrel.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1),
                barrel.getPersistentDataContainer().getOrDefault(modeKey, PersistentDataType.STRING, "STORE"))); save();
    }
    private static String key(Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }

    private void load() {
        getDataFolder().mkdirs(); YamlConfiguration y = YamlConfiguration.loadConfiguration(file); var root = y.getConfigurationSection("collectors"); if (root == null) return;
        for (String id : root.getKeys(false)) try { String b=id+"."; CollectorData d=new CollectorData(root.getString(b+"world"),root.getInt(b+"x"),root.getInt(b+"y"),root.getInt(b+"z"),UUID.fromString(root.getString(b+"owner")),root.getInt(b+"level",1),root.getString(b+"mode","STORE")); collectors.put(id,d); } catch(Exception ignored) {}
    }
    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration(); for (var e:collectors.entrySet()) { CollectorData d=e.getValue(); String b="collectors."+e.getKey()+"."; y.set(b+"world",d.world); y.set(b+"x",d.x); y.set(b+"y",d.y); y.set(b+"z",d.z); y.set(b+"owner",d.owner.toString()); y.set(b+"level",d.level); y.set(b+"mode",d.mode); }
        try { y.save(file); } catch(IOException ex) { getLogger().severe("Could not save collectors.yml: "+ex.getMessage()); }
    }
    private record CollectorData(String world,int x,int y,int z,UUID owner,int level,String mode) {}
}
