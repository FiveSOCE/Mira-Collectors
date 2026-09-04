package gg.mira.collectors;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import gg.mira.collectors.api.event.CollectorSellEvent;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class MiraCollectorsPlugin extends JavaPlugin implements Listener {
    private NamespacedKey itemKey;
    private NamespacedKey collectorIdKey;
    private NamespacedKey ownerKey;
    private NamespacedKey levelKey;
    private NamespacedKey modeKey;

    private final Map<String, CollectorData> collectors = new LinkedHashMap<>();
    private File file;
    private Economy economy;
    private MiraCore core;
    private CollectorsApi api;
    private ShopBridge shopBridge;
    private long lastShopWarning;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();

        itemKey = new NamespacedKey(this, "collector_item");
        collectorIdKey = new NamespacedKey(this, "collector_id");
        ownerKey = new NamespacedKey(this, "owner");
        levelKey = new NamespacedKey(this, "level");
        modeKey = new NamespacedKey(this, "mode");

        file = new File(getDataFolder(), "collectors.yml");
        load();

        var registration = getServer().getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();

        Plugin shopPlugin = Bukkit.getPluginManager().getPlugin("MiraShop");
        if (shopPlugin != null && shopPlugin.isEnabled()) shopBridge = new ShopBridge(shopPlugin);

        api = new CollectorsApiImpl();
        getServer().getServicesManager().register(CollectorsApi.class, api, this, ServicePriority.Normal);
        core.services().register(CollectorsApi.class, api);
        core.modules().register(this, "MiraCollectors");
        core.modules().setHealth(this,
                economy != null && shopBridge != null ? ModuleHealth.HEALTHY : ModuleHealth.DEGRADED,
                economy != null && shopBridge != null
                        ? "STORE and transactional MiraShop SELL collection ready"
                        : "STORE mode ready; SELL mode is waiting for Vault economy and/or MiraShop");

        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.max(20L, getConfig().getLong("collector.tick-interval-ticks", 100L));
        getServer().getScheduler().runTaskTimer(this, this::tick, interval, interval);

        getLogger().info("MiraCollectors v" + getPluginMeta().getVersion()
                + " enabled with " + collectors.size() + " persisted collector(s).");
    }

    @Override
    public void onDisable() {
        save();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (api != null) core.services().unregister(CollectorsApi.class, api);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("miracollectors.admin")) {
                msg(sender, "&cYou do not have permission.");
                return true;
            }
            if (args.length < 2) {
                msg(sender, "&eUsage: /collector give <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, "&cPlayer not online.");
                return true;
            }

            UUID collectorId = UUID.randomUUID();
            ItemStack item = createCollectorItem(1, Mode.STORE, collectorId);
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
            leftovers.values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));

            core.audit().record("MiraCollectors", "COLLECTOR_GRANTED",
                    sender instanceof Player player ? player.getUniqueId() : null,
                    sender.getName(), collectorId.toString(), "Collector item granted",
                    Map.of("target", target.getUniqueId().toString(), "targetName", target.getName()));
            msg(sender, "&aCollector &f" + shortId(collectorId) + " &agiven to &f" + target.getName() + "&a.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers only.");
            return true;
        }
        if (!player.hasPermission("miracollectors.use")) {
            msg(player, "&cYou do not have permission.");
            return true;
        }

        Barrel barrel = targetedCollector(player);
        if (barrel == null) {
            msg(player, "&cLook at a MiraCollector within 6 blocks.");
            return true;
        }

        CollectorData data = readCollector(barrel, null);
        if (data == null) {
            msg(player, "&cThat collector has invalid ownership data.");
            return true;
        }
        if (!data.owner().equals(player.getUniqueId()) && !player.hasPermission("miracollectors.admin")) {
            msg(player, "&cThat collector is not yours.");
            return true;
        }

        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> {
                msg(player, "&6Collector &f" + shortId(data.id())
                        + " &7Level &f" + data.level()
                        + " &7Radius &f" + radius(data.level())
                        + " &7Mode &f" + data.mode());
                msg(player, "&7Stored slots used: &f" + usedSlots(barrel.getInventory())
                        + "&7/&f" + barrel.getInventory().getSize());
            }
            case "mode" -> {
                if (args.length < 2) {
                    msg(player, "&eUsage: /collector mode <store|sell>");
                    return true;
                }
                Mode mode;
                try {
                    mode = Mode.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    msg(player, "&cMode must be STORE or SELL.");
                    return true;
                }

                if (mode == Mode.SELL && !sellModeAvailable()) {
                    msg(player, "&cSELL mode requires MiraShop and an active Vault economy provider.");
                    return true;
                }

                setBlockMode(barrel, mode);
                updateRegistry(barrel.getBlock(), barrel);
                core.audit().record("MiraCollectors", "COLLECTOR_MODE_CHANGED",
                        player.getUniqueId(), player.getName(), data.id().toString(), "Collector mode changed",
                        Map.of("from", data.mode().name(), "to", mode.name()));
                msg(player, "&aCollector mode set to &f" + mode + "&a.");
            }
            case "upgrade" -> {
                int level = data.level();
                if (level >= 5) {
                    msg(player, "&eCollector is already max level.");
                    return true;
                }

                int cost = level * 8;
                if (!player.hasPermission("miracollectors.admin")) {
                    ItemStack price = new ItemStack(Material.DIAMOND, cost);
                    if (!player.getInventory().containsAtLeast(price, cost)) {
                        msg(player, "&cUpgrade requires &f" + cost + " diamonds&c.");
                        return true;
                    }
                    Map<Integer, ItemStack> failed = player.getInventory().removeItem(price);
                    if (!failed.isEmpty()) {
                        msg(player, "&cCould not safely remove the upgrade cost. Nothing changed.");
                        return true;
                    }
                }

                int next = level + 1;
                barrel.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, next);
                barrel.update(true);
                updateRegistry(barrel.getBlock(), barrel);
                core.audit().record("MiraCollectors", "COLLECTOR_UPGRADED",
                        player.getUniqueId(), player.getName(), data.id().toString(), "Collector upgraded",
                        Map.of("fromLevel", Integer.toString(level), "toLevel", Integer.toString(next)));
                msg(player, "&aCollector upgraded to level &f" + next
                        + " &a(radius &f" + radius(next) + "&a).");
            }
            default -> msg(player, "&7/collector <info|mode <store|sell>|upgrade>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("info", "mode", "upgrade"));
            if (sender.hasPermission("miracollectors.admin")) values.add("give");
            return complete(args[0], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return complete(args[1], List.of("store", "sell"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private ItemStack createCollectorItem(int level, Mode mode, UUID collectorId) {
        int safeLevel = clampLevel(level);
        Mode safeMode = mode == null ? Mode.STORE : mode;
        UUID safeId = collectorId == null ? UUID.randomUUID() : collectorId;

        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text("Mira Collector"));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(collectorIdKey, PersistentDataType.STRING, safeId.toString());
        pdc.set(levelKey, PersistentDataType.INTEGER, safeLevel);
        pdc.set(modeKey, PersistentDataType.STRING, safeMode.name());
        meta.lore(List.of(
                Component.text("Collects nearby dropped items."),
                Component.text("Level: " + safeLevel + " | Radius: " + radius(safeLevel)),
                Component.text("Mode: " + safeMode),
                Component.text("ID: " + shortId(safeId))
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!isCollectorItem(inHand)) return;
        if (!(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;

        PersistentDataContainer itemData = inHand.getItemMeta().getPersistentDataContainer();
        UUID id = parseUuid(itemData.get(collectorIdKey, PersistentDataType.STRING));
        if (id == null) id = UUID.randomUUID();
        int level = clampLevel(itemData.getOrDefault(levelKey, PersistentDataType.INTEGER, 1));
        Mode mode = parseMode(itemData.get(modeKey, PersistentDataType.STRING));

        PersistentDataContainer blockData = barrel.getPersistentDataContainer();
        blockData.set(collectorIdKey, PersistentDataType.STRING, id.toString());
        blockData.set(ownerKey, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
        blockData.set(levelKey, PersistentDataType.INTEGER, level);
        blockData.set(modeKey, PersistentDataType.STRING, mode.name());
        barrel.update(true);

        updateRegistry(event.getBlockPlaced(), barrel);
        core.audit().record("MiraCollectors", "COLLECTOR_PLACED",
                event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                id.toString(), "Collector placed",
                locationAudit(event.getBlockPlaced().getLocation(), Map.of(
                        "level", Integer.toString(level), "mode", mode.name())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) return;

        CollectorData data = readCollector(barrel, null);
        if (data == null) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cThat collector has invalid data and was protected from breaking.");
            return;
        }
        if (!data.owner().equals(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("miracollectors.admin")) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cThat collector is not yours.");
            return;
        }

        ItemStack[] stored = cloneContents(barrel.getInventory().getContents());
        barrel.getInventory().clear();

        collectors.remove(key(event.getBlock().getLocation()));
        save();

        event.setDropItems(false);
        event.getBlock().getWorld().dropItemNaturally(
                event.getBlock().getLocation(),
                createCollectorItem(data.level(), data.mode(), data.id()));
        for (ItemStack stack : stored) {
            if (stack != null && !stack.getType().isAir()) {
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), stack);
            }
        }

        core.audit().record("MiraCollectors", "COLLECTOR_BROKEN",
                event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                data.id().toString(), "Collector broken",
                locationAudit(event.getBlock().getLocation(), Map.of(
                        "level", Integer.toString(data.level()), "mode", data.mode().name())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) return;

        UUID owner = ownerOf(barrel);
        if (owner == null) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cThat collector has invalid ownership data.");
            return;
        }

        if (!owner.equals(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("miracollectors.admin")) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cThat collector is not yours.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isCollectorInventory(event.getSource()) || isCollectorInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isCollectorBlock)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isCollectorBlock)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isCollectorBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isCollectorBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isCollectorBlock(event.getBlock())) event.setCancelled(true);
    }

    private void tick() {
        ShopSnapshot shopSnapshot = shopBridge == null ? ShopSnapshot.empty() : shopSnapshot();
        boolean dirty = false;

        for (Map.Entry<String, CollectorData> entry : new ArrayList<>(collectors.entrySet())) {
            CollectorData persisted = entry.getValue();
            World world = Bukkit.getWorld(persisted.world());
            if (world == null || !world.isChunkLoaded(persisted.x() >> 4, persisted.z() >> 4)) continue;

            Block block = world.getBlockAt(persisted.x(), persisted.y(), persisted.z());
            if (!(block.getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) {
                collectors.remove(entry.getKey());
                dirty = true;
                continue;
            }

            CollectorData data = readCollector(barrel, persisted.id());
            if (data == null) {
                collectors.remove(entry.getKey());
                dirty = true;
                continue;
            }

            if (!data.equals(persisted)) {
                collectors.put(entry.getKey(), data);
                dirty = true;
            }

            int r = radius(data.level());
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            for (Item entity : world.getNearbyEntitiesByType(Item.class, center, r, r, r)) {
                if (!entity.isValid() || entity.getPickupDelay() > 20) continue;
                if (data.mode() == Mode.SELL) sell(entity, data, shopSnapshot);
                else store(entity, barrel);
            }
        }

        if (dirty) save();
    }

    private void store(Item entity, Barrel barrel) {
        ItemStack original = entity.getItemStack().clone();
        Map<Integer, ItemStack> leftovers = barrel.getInventory().addItem(original);
        if (leftovers.isEmpty()) {
            entity.remove();
            return;
        }

        ItemStack remainder = leftovers.values().iterator().next().clone();
        entity.setItemStack(remainder);
    }

    private void sell(Item entity, CollectorData collector, ShopSnapshot prices) {
        if (economy == null || prices.entries().isEmpty()) return;

        ItemStack stack = entity.getItemStack();
        PriceEntry price = prices.find(stack);
        if (price == null) return;

        double money = safeTotal(price.unitPrice(), stack.getAmount());
        if (money < 0D) return;

        EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(collector.owner()), money);
        if (response == null || !response.transactionSuccess()) return;

        int amount = stack.getAmount();
        Material material = stack.getType();
        entity.remove();

        if (shopBridge != null) {
            try {
                shopBridge.recordSell(price.rawItem(), amount, money);
            } catch (ReflectiveOperationException exception) {
                warnShop("Could not record MiraShop collector analytics: " + exception.getMessage());
            }
        }

        Bukkit.getPluginManager().callEvent(new CollectorSellEvent(
                collector.id(), collector.owner(), collector.location(), material, amount, money));

        if (getConfig().getBoolean("audit.successful-sales", false)) {
            core.audit().record("MiraCollectors", "COLLECTOR_SALE",
                    collector.owner(), "collector", collector.id().toString(), "Collector sold dropped items",
                    locationAudit(collector.location(), Map.of(
                            "material", material.name(),
                            "units", Integer.toString(amount),
                            "payout", Double.toString(money))));
        }
    }

    private ShopSnapshot shopSnapshot() {
        try {
            return shopBridge.snapshot();
        } catch (ReflectiveOperationException exception) {
            warnShop("MiraShop collector pricing integration failed: " + exception.getMessage());
            return ShopSnapshot.empty();
        }
    }

    private void warnShop(String message) {
        long now = System.currentTimeMillis();
        if (now - lastShopWarning < 60_000L) return;
        lastShopWarning = now;
        getLogger().warning(message);
    }

    private boolean sellModeAvailable() {
        if (economy == null) {
            var registration = getServer().getServicesManager().getRegistration(Economy.class);
            economy = registration == null ? null : registration.getProvider();
        }
        if (shopBridge == null) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("MiraShop");
            if (plugin != null && plugin.isEnabled()) shopBridge = new ShopBridge(plugin);
        }
        return economy != null && shopBridge != null;
    }

    private Barrel targetedCollector(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) return null;
        return barrel;
    }

    private boolean isCollectorItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private boolean isCollectorBlock(Block block) {
        return block != null && block.getState() instanceof Barrel barrel && isCollectorBarrel(barrel);
    }

    private boolean isCollectorBarrel(Barrel barrel) {
        return barrel.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private boolean isCollectorInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Barrel barrel && isCollectorBarrel(barrel);
    }

    private CollectorData readCollector(Barrel barrel, UUID fallbackId) {
        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        UUID owner = parseUuid(pdc.get(ownerKey, PersistentDataType.STRING));
        if (owner == null) return null;

        UUID id = parseUuid(pdc.get(collectorIdKey, PersistentDataType.STRING));
        if (id == null) {
            id = fallbackId == null ? UUID.randomUUID() : fallbackId;
            pdc.set(collectorIdKey, PersistentDataType.STRING, id.toString());
            barrel.update(true);
        }

        int level = clampLevel(pdc.getOrDefault(levelKey, PersistentDataType.INTEGER, 1));
        Mode mode = parseMode(pdc.get(modeKey, PersistentDataType.STRING));
        Location location = barrel.getLocation();

        return new CollectorData(id, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                owner, level, mode);
    }

    private UUID ownerOf(Barrel barrel) {
        return parseUuid(barrel.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    private void setBlockMode(Barrel barrel, Mode mode) {
        barrel.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.name());
        barrel.update(true);
    }

    private void updateRegistry(Block block, Barrel barrel) {
        CollectorData data = readCollector(barrel, null);
        if (data == null) return;
        collectors.put(key(block.getLocation()), data);
        save();
    }

    private void load() {
        getDataFolder().mkdirs();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var root = yaml.getConfigurationSection("collectors");
        if (root == null) return;

        for (String locationKey : root.getKeys(false)) {
            try {
                String base = locationKey + ".";
                UUID id = parseUuid(root.getString(base + "id"));
                if (id == null) id = UUID.randomUUID();
                UUID owner = UUID.fromString(Objects.requireNonNull(root.getString(base + "owner")));
                CollectorData data = new CollectorData(
                        id,
                        Objects.requireNonNull(root.getString(base + "world")),
                        root.getInt(base + "x"),
                        root.getInt(base + "y"),
                        root.getInt(base + "z"),
                        owner,
                        clampLevel(root.getInt(base + "level", 1)),
                        parseMode(root.getString(base + "mode", "STORE")));
                collectors.put(locationKey, data);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, CollectorData> entry : collectors.entrySet()) {
            CollectorData data = entry.getValue();
            String base = "collectors." + entry.getKey() + ".";
            yaml.set(base + "id", data.id().toString());
            yaml.set(base + "world", data.world());
            yaml.set(base + "x", data.x());
            yaml.set(base + "y", data.y());
            yaml.set(base + "z", data.z());
            yaml.set(base + "owner", data.owner().toString());
            yaml.set(base + "level", data.level());
            yaml.set(base + "mode", data.mode().name());
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            getLogger().severe("Could not save collectors.yml: " + exception.getMessage());
        }
    }

    private void msg(CommandSender sender, String raw) {
        core.messages().send(sender, raw);
    }

    private static int radius(int level) {
        return 4 + clampLevel(level) * 2;
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(5, level));
    }

    private static Mode parseMode(String raw) {
        if (raw == null) return Mode.STORE;
        try {
            return Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Mode.STORE;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static int usedSlots(Inventory inventory) {
        int used = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) used++;
        }
        return used;
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    private static double safeTotal(double price, int amount) {
        if (!Double.isFinite(price) || price < 0D || amount <= 0) return -1D;
        double total = price * amount;
        return Double.isFinite(total) && total >= 0D ? total : -1D;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    private static Map<String, String> locationAudit(Location location, Map<String, String> extra) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("world", location.getWorld().getName());
        values.put("x", Integer.toString(location.getBlockX()));
        values.put("y", Integer.toString(location.getBlockY()));
        values.put("z", Integer.toString(location.getBlockZ()));
        values.putAll(extra);
        return Map.copyOf(values);
    }

    public enum Mode { STORE, SELL }

    public record CollectorSnapshot(UUID id, UUID owner, String world, int x, int y, int z,
                                    int level, Mode mode) {
        public Location location() {
            World resolved = Bukkit.getWorld(world);
            return resolved == null ? null : new Location(resolved, x, y, z);
        }
    }

    public interface CollectorsApi {
        boolean isCollector(Location location);
        Optional<CollectorSnapshot> collectorAt(Location location);
        List<CollectorSnapshot> ownedBy(UUID owner);
        int count();
        ItemStack create(int level, Mode mode);
    }

    private final class CollectorsApiImpl implements CollectorsApi {
        @Override
        public boolean isCollector(Location location) {
            return location != null && collectors.containsKey(key(location));
        }

        @Override
        public Optional<CollectorSnapshot> collectorAt(Location location) {
            if (location == null) return Optional.empty();
            CollectorData data = collectors.get(key(location));
            return data == null ? Optional.empty() : Optional.of(data.snapshot());
        }

        @Override
        public List<CollectorSnapshot> ownedBy(UUID owner) {
            return collectors.values().stream()
                    .filter(data -> data.owner().equals(owner))
                    .map(CollectorData::snapshot)
                    .toList();
        }

        @Override public int count() { return collectors.size(); }

        @Override
        public ItemStack create(int level, Mode mode) {
            return createCollectorItem(level, mode, UUID.randomUUID());
        }
    }

    private record CollectorData(UUID id, String world, int x, int y, int z,
                                 UUID owner, int level, Mode mode) {
        Location location() {
            World resolved = Bukkit.getWorld(world);
            return resolved == null ? new Location(Bukkit.getWorlds().getFirst(), x, y, z)
                    : new Location(resolved, x, y, z);
        }

        CollectorSnapshot snapshot() {
            return new CollectorSnapshot(id, owner, world, x, y, z, level, mode);
        }
    }

    private record PriceEntry(Object rawItem, Material material, boolean custom,
                              ItemStack template, double unitPrice) { }

    private record ShopSnapshot(List<PriceEntry> entries) {
        static ShopSnapshot empty() { return new ShopSnapshot(List.of()); }

        PriceEntry find(ItemStack stack) {
            if (stack == null || stack.getType().isAir()) return null;

            for (PriceEntry entry : entries) {
                if (!entry.custom() || entry.material() != stack.getType()) continue;
                ItemStack one = stack.clone();
                one.setAmount(1);
                if (one.isSimilar(entry.template())) return entry;
            }

            ItemStack plain = stack.clone();
            plain.setAmount(1);
            if (!plain.isSimilar(new ItemStack(stack.getType()))) return null;

            for (PriceEntry entry : entries) {
                if (!entry.custom() && entry.material() == stack.getType()) return entry;
            }
            return null;
        }
    }

    private static final class ShopBridge {
        private final Plugin plugin;

        ShopBridge(Plugin plugin) {
            this.plugin = plugin;
        }

        ShopSnapshot snapshot() throws ReflectiveOperationException {
            Object catalog = plugin.getClass().getMethod("catalog").invoke(plugin);
            Object sales = plugin.getClass().getMethod("sales").invoke(plugin);
            Collection<?> sections = (Collection<?>) catalog.getClass().getMethod("sections").invoke(catalog);

            List<PriceEntry> entries = new ArrayList<>();
            for (Object section : sections) {
                Collection<?> items = (Collection<?>) section.getClass().getMethod("items").invoke(section);
                for (Object item : items) {
                    boolean canSell = (boolean) item.getClass().getMethod("canSell").invoke(item);
                    if (!canSell) continue;

                    Material material = (Material) item.getClass().getMethod("material").invoke(item);
                    boolean custom = (boolean) item.getClass().getMethod("customTemplate").invoke(item);
                    ItemStack template = (ItemStack) item.getClass().getMethod("template").invoke(item);
                    double unit = ((Number) sales.getClass()
                            .getMethod("sellPrice", item.getClass()).invoke(sales, item)).doubleValue();
                    if (!Double.isFinite(unit) || unit < 0D) continue;

                    ItemStack safeTemplate = template.clone();
                    safeTemplate.setAmount(1);
                    entries.add(new PriceEntry(item, material, custom, safeTemplate, unit));
                }
            }
            return new ShopSnapshot(List.copyOf(entries));
        }

        void recordSell(Object item, int amount, double money) throws ReflectiveOperationException {
            Object stats = plugin.getClass().getMethod("stats").invoke(plugin);
            Method method = stats.getClass().getMethod("recordSell", item.getClass(), int.class, double.class);
            method.invoke(stats, item, amount, money);
        }
    }
}
