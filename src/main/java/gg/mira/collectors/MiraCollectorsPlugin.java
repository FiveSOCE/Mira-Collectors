package gg.mira.collectors;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.factions.api.MiraFactionsApi;
import gg.mira.collectors.api.event.CollectorSellEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
    private static final long UPGRADE_COST = 100_000L;
    private static final int MAX_TYPES = 54;

    private NamespacedKey itemKey;
    private NamespacedKey collectorIdKey;
    private NamespacedKey ownerKey;
    private NamespacedKey factionKey;
    private NamespacedKey levelKey;
    private NamespacedKey modeKey;
    private NamespacedKey filterKey;
    private NamespacedKey hologramKey;
    private NamespacedKey mobDropKey;

    private final Map<String, CollectorData> collectors = new LinkedHashMap<>();
    private final Map<UUID, List<StoredEntry>> storage = new HashMap<>();

    private File file;
    private Economy economy;
    private MiraCore core;
    private MiraFactionsApi factions;
    private CollectorsApi api;
    private ShopBridge shopBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();

        itemKey = new NamespacedKey(this, "collector_item");
        collectorIdKey = new NamespacedKey(this, "collector_id");
        ownerKey = new NamespacedKey(this, "owner");
        factionKey = new NamespacedKey(this, "faction_id");
        levelKey = new NamespacedKey(this, "level");
        modeKey = new NamespacedKey(this, "mode");
        filterKey = new NamespacedKey(this, "filters");
        hologramKey = new NamespacedKey(this, "collector_hologram");
        mobDropKey = new NamespacedKey(this, "mob_drop");

        file = new File(getDataFolder(), "collectors.yml");
        load();

        var ecoRegistration = getServer().getServicesManager().getRegistration(Economy.class);
        economy = ecoRegistration == null ? null : ecoRegistration.getProvider();
        factions = getServer().getServicesManager().load(MiraFactionsApi.class);
        if (factions == null) throw new IllegalStateException("MiraFactions API is required.");

        Plugin shopPlugin = Bukkit.getPluginManager().getPlugin("MiraShop");
        if (shopPlugin != null && shopPlugin.isEnabled()) shopBridge = new ShopBridge(shopPlugin);

        api = new CollectorsApiImpl();
        getServer().getServicesManager().register(CollectorsApi.class, api, this, ServicePriority.Normal);
        core.services().register(CollectorsApi.class, api);
        core.modules().register(this, "MiraCollectors");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Chunk collectors with virtual capacity, faction ownership and persistent upgrades ready");

        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.max(20L, getConfig().getLong("collector.tick-interval-ticks", 40L));
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, interval);
        getServer().getScheduler().runTask(this, this::reconcileHolograms);

        getLogger().info("MiraCollectors v" + getPluginMeta().getVersion()
                + " enabled with " + collectors.size() + " persisted collector(s).");
    }

    @Override
    public void onDisable() {
        save();
        for (CollectorData data : collectors.values()) removeHologram(data.id(), data.location());
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
                msg(sender, "&eUsage: /collector give <player> [level]");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, "&cPlayer not online.");
                return true;
            }
            int level = 1;
            if (args.length >= 3) {
                try { level = clampLevel(Integer.parseInt(args[2])); }
                catch (NumberFormatException ex) {
                    msg(sender, "&cLevel must be 1-5.");
                    return true;
                }
            }
            ItemStack item = createCollectorItem(level, Mode.STORE, UUID.randomUUID(), Set.of());
            target.getInventory().addItem(item).values().forEach(left ->
                    target.getWorld().dropItemNaturally(target.getLocation(), left));
            msg(sender, "&aGave &f" + target.getName() + " &aa level &f" + level + " &acollector.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers only.");
            return true;
        }

        Barrel barrel = targetedCollector(player);
        if (barrel == null) {
            msg(player, "&cLook at a MiraCollector within 6 blocks.");
            return true;
        }
        CollectorData data = readCollector(barrel, null);
        if (data == null || !sameFaction(player, data)) {
            msg(player, "&cThat collector does not belong to your faction.");
            return true;
        }

        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> {
                long total = totalStored(data.id());
                msg(player, "&6Level " + data.level() + " Collector &7- &f" + formatCount(total)
                        + "&7/&f" + formatCount(capacity(data.level())) + " &7items");
                msg(player, "&7Coverage: &fthis chunk only &7| Mode: &f" + data.mode());
            }
            case "upgrade" -> upgrade(player, barrel, data);
            case "mode" -> {
                if (args.length < 2) {
                    msg(player, "&eUsage: /collector mode <store|sell>");
                    return true;
                }
                Mode mode;
                try { mode = Mode.valueOf(args[1].toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ex) {
                    msg(player, "&cMode must be STORE or SELL.");
                    return true;
                }
                if (mode == Mode.SELL && !sellModeAvailable()) {
                    msg(player, "&cSELL mode requires MiraShop and Vault.");
                    return true;
                }
                barrel.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.name());
                barrel.update(true);
                updateRegistry(barrel.getBlock(), barrel);
                updateHologram(readCollector(barrel, data.id()));
                msg(player, "&aCollector mode set to &f" + mode + "&a.");
            }
            default -> msg(player, "&7/collector <info|upgrade|mode <store|sell>>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("info", "upgrade", "mode"));
            if (sender.hasPermission("miracollectors.admin")) values.add("give");
            return complete(args[0], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) return complete(args[1], List.of("store", "sell"));
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return complete(args[2], List.of("1","2","3","4","5"));
        return List.of();
    }

    private void upgrade(Player player, Barrel barrel, CollectorData data) {
        if (data.level() >= 5) {
            msg(player, "&eThat collector is already max level.");
            return;
        }

        boolean paidFromFaction = factions.factionBankBalance(player.getUniqueId()) >= UPGRADE_COST
                && factions.withdrawFactionBank(player.getUniqueId(), UPGRADE_COST);

        if (!paidFromFaction) {
            if (economy == null || !economy.has(player, UPGRADE_COST)) {
                msg(player, "&cUpgrade costs &f$100,000&c. Your faction bank and player balance are both too low.");
                return;
            }
            if (!economy.withdrawPlayer(player, UPGRADE_COST).transactionSuccess()) {
                msg(player, "&cCould not charge your balance. Upgrade cancelled.");
                return;
            }
        }

        int next = data.level() + 1;
        barrel.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, next);
        barrel.update(true);
        updateRegistry(barrel.getBlock(), barrel);
        CollectorData updated = readCollector(barrel, data.id());
        updateHologram(updated);

        core.audit().record("MiraCollectors", "COLLECTOR_UPGRADED",
                player.getUniqueId(), player.getName(), data.id().toString(), "Collector upgraded",
                Map.of("fromLevel", Integer.toString(data.level()), "toLevel", Integer.toString(next),
                        "cost", Long.toString(UPGRADE_COST), "source", paidFromFaction ? "FACTION_BANK" : "PLAYER"));
        msg(player, "&aCollector upgraded to level &f" + next + "&a. Capacity: &f" + formatCount(capacity(next))
                + "&a. Charged &f" + (paidFromFaction ? "faction bank" : "your balance") + "&a.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!isCollectorItem(inHand) || !(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;

        if (!factions.isOwnClaim(event.getPlayer(), event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cCollectors can only be placed inside your own faction claim.");
            return;
        }

        UUID factionId = factions.factionId(event.getPlayer().getUniqueId()).orElse(null);
        if (factionId == null) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cYou must be in a faction to place a collector.");
            return;
        }

        PersistentDataContainer itemData = inHand.getItemMeta().getPersistentDataContainer();
        UUID id = parseUuid(itemData.get(collectorIdKey, PersistentDataType.STRING));
        if (id == null) id = UUID.randomUUID();
        int level = clampLevel(itemData.getOrDefault(levelKey, PersistentDataType.INTEGER, 1));
        Mode mode = parseMode(itemData.get(modeKey, PersistentDataType.STRING));
        Set<Material> filters = decodeFilters(itemData.get(filterKey, PersistentDataType.STRING));

        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        pdc.set(collectorIdKey, PersistentDataType.STRING, id.toString());
        pdc.set(ownerKey, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
        pdc.set(factionKey, PersistentDataType.STRING, factionId.toString());
        pdc.set(levelKey, PersistentDataType.INTEGER, level);
        pdc.set(modeKey, PersistentDataType.STRING, mode.name());
        pdc.set(filterKey, PersistentDataType.STRING, encodeFilters(filters));
        barrel.update(true);

        storage.putIfAbsent(id, new ArrayList<>());
        updateRegistry(event.getBlockPlaced(), barrel);
        updateHologram(readCollector(barrel, id));
        core.audit().record("MiraCollectors", "COLLECTOR_PLACED",
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id.toString(),
                "Collector placed", Map.of("level", Integer.toString(level), "faction", factionId.toString()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) return;
        CollectorData data = readCollector(barrel, null);
        if (data == null || !sameFaction(event.getPlayer(), data)) {
            event.setCancelled(true);
            msg(event.getPlayer(), "&cOnly members of the owning faction can break this collector.");
            return;
        }

        event.setDropItems(false);
        event.setExpToDrop(0);
        barrel.getInventory().clear();

        long destroyed = totalStored(data.id());
        storage.remove(data.id());
        collectors.remove(key(event.getBlock().getLocation()));
        removeHologram(data.id(), event.getBlock().getLocation());
        save();

        ItemStack returned = createCollectorItem(data.level(), data.mode(), data.id(), data.filters());
        event.getPlayer().getInventory().addItem(returned).values().forEach(left ->
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), left));

        core.audit().record("MiraCollectors", "COLLECTOR_BROKEN",
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), data.id().toString(),
                "Collector broken and returned empty",
                Map.of("level", Integer.toString(data.level()), "destroyedStoredItems", Long.toString(destroyed)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) return;

        event.setCancelled(true);
        CollectorData data = readCollector(barrel, null);
        if (data == null || !sameFaction(event.getPlayer(), data)) {
            msg(event.getPlayer(), "&cThat collector does not belong to your faction.");
            return;
        }
        openStorage(event.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStorageClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StorageHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        CollectorData data = collectors.get(holder.locationKey());
        if (data == null || !data.id().equals(holder.id()) || !sameFaction(player, data)) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        ItemStack shown = event.getCurrentItem();
        if (shown == null || shown.getType().isAir()) return;

        List<StoredEntry> entries = storage.getOrDefault(data.id(), List.of());
        if (slot >= entries.size()) return;
        StoredEntry entry = entries.get(slot);
        int requested = (int)Math.min(entry.count(), Math.max(1, entry.template().getMaxStackSize()));
        ItemStack give = entry.template().clone();
        give.setAmount(requested);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(give);
        int leftover = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        int delivered = requested - leftover;
        if (delivered <= 0) return;

        entry.count(entry.count() - delivered);
        if (entry.count() <= 0) entries.remove(entry);
        save();
        openStorage(player, data);
        updateHologram(data);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStorageDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof StorageHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isCollectorInventory(event.getSource()) || isCollectorInventory(event.getDestination())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDrop(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof LivingEntity) || event.getEntity() instanceof Player) return;
        Item dropped = event.getItemDrop();
        dropped.getPersistentDataContainer().set(mobDropKey, PersistentDataType.BYTE, (byte) 1);
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
    public void onBlockExplode(BlockExplodeEvent event) { event.blockList().removeIf(this::isCollectorBlock); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { event.blockList().removeIf(this::isCollectorBlock); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { if (isCollectorBlock(event.getBlock())) event.setCancelled(true); }

    private void tick() {
        boolean dirty = false;
        for (Map.Entry<String, CollectorData> mapEntry : new ArrayList<>(collectors.entrySet())) {
            CollectorData data = mapEntry.getValue();
            World world = Bukkit.getWorld(data.world());
            if (world == null || !world.isChunkLoaded(data.x() >> 4, data.z() >> 4)) continue;

            Block block = world.getBlockAt(data.x(), data.y(), data.z());
            if (!(block.getState() instanceof Barrel barrel) || !isCollectorBarrel(barrel)) {
                collectors.remove(mapEntry.getKey());
                removeHologram(data.id(), data.location());
                dirty = true;
                continue;
            }

            CollectorData current = readCollector(barrel, data.id());
            if (current == null) continue;
            collectors.put(mapEntry.getKey(), current);

            Chunk chunk = world.getChunkAt(data.x() >> 4, data.z() >> 4);
            for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                if (!(entity instanceof Item dropped) || !dropped.isValid()) continue;
                Byte mobDrop = dropped.getPersistentDataContainer().get(mobDropKey, PersistentDataType.BYTE);
                if (mobDrop == null || mobDrop != (byte) 1) continue;

                ItemStack stack = dropped.getItemStack();
                if (!current.filters().isEmpty() && !current.filters().contains(stack.getType())) continue;

                if (current.mode() == Mode.SELL && sellModeAvailable()) {
                    sellDropped(current, dropped, stack);
                    continue;
                }

                long room = capacity(current.level()) - totalStored(current.id());
                if (room <= 0) break;
                int accepted = (int)Math.min(room, stack.getAmount());
                if (accepted <= 0 || !store(current.id(), stack, accepted)) continue;

                if (accepted >= stack.getAmount()) dropped.remove();
                else {
                    ItemStack remainder = stack.clone();
                    remainder.setAmount(stack.getAmount() - accepted);
                    dropped.setItemStack(remainder);
                }
                dirty = true;
            }
            updateHologram(current);
        }
        if (dirty) save();
    }

    private void sellDropped(CollectorData data, Item dropped, ItemStack stack) {
        try {
            ShopSnapshot snapshot = shopBridge == null ? ShopSnapshot.empty() : shopBridge.snapshot();
            PriceEntry entry = snapshot.find(stack);
            if (entry == null || economy == null) return;
            double money = entry.unitPrice() * stack.getAmount();
            if (!Double.isFinite(money) || money <= 0) return;

            OfflinePlayer owner = Bukkit.getOfflinePlayer(data.owner());
            if (!economy.depositPlayer(owner, money).transactionSuccess()) return;
            shopBridge.recordSell(entry.rawItem(), stack.getAmount(), money);
            dropped.remove();
            Bukkit.getPluginManager().callEvent(new CollectorSellEvent(
                    data.id(), data.owner(), data.location(), stack.getType(), stack.getAmount(), money));
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Collector SELL bridge failed: " + ex.getMessage());
        }
    }

    private boolean store(UUID collectorId, ItemStack stack, int amount) {
        List<StoredEntry> entries = storage.computeIfAbsent(collectorId, ignored -> new ArrayList<>());
        ItemStack template = stack.clone();
        template.setAmount(1);

        for (StoredEntry entry : entries) {
            if (entry.template().isSimilar(template)) {
                entry.count(entry.count() + amount);
                return true;
            }
        }

        if (entries.size() >= MAX_TYPES) return false;
        entries.add(new StoredEntry(template, amount));
        return true;
    }

    private void openStorage(Player player, CollectorData data) {
        List<StoredEntry> entries = storage.getOrDefault(data.id(), List.of());
        int size = Math.max(9, Math.min(54, ((Math.max(1, entries.size()) + 8) / 9) * 9));
        Inventory inventory = Bukkit.createInventory(new StorageHolder(data.id(), key(data.location())),
                size, "Level " + data.level() + " Collector");

        for (int i = 0; i < Math.min(entries.size(), size); i++) {
            StoredEntry entry = entries.get(i);
            ItemStack shown = entry.template().clone();
            shown.setAmount((int)Math.min(entry.count(), Math.max(1, shown.getMaxStackSize())));
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("Stored: " + String.format(Locale.US, "%,d", entry.count()), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            shown.setItemMeta(meta);
            inventory.setItem(i, shown);
        }
        player.openInventory(inventory);
    }

    private void reconcileHolograms() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                String id = display.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
                if (id == null) continue;
                UUID uuid = parseUuid(id);
                boolean valid = uuid != null && collectors.values().stream().anyMatch(data -> data.id().equals(uuid));
                if (!valid) display.remove();
            }
        }
        for (CollectorData data : collectors.values()) updateHologram(data);
    }

    private void updateHologram(CollectorData data) {
        if (data == null) return;
        Location base = data.location();
        World world = base.getWorld();
        if (world == null || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) return;

        TextDisplay found = null;
        for (TextDisplay display : world.getNearbyEntitiesByType(TextDisplay.class, base.clone().add(0.5, 1.8, 0.5), 2.0)) {
            String raw = display.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
            if (data.id().toString().equals(raw)) { found = display; break; }
        }
        if (found == null) {
            found = world.spawn(base.clone().add(0.5, 1.8, 0.5), TextDisplay.class, display -> {
                display.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, data.id().toString());
                display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                display.setShadowed(true);
                display.setSeeThrough(false);
            });
        }

        long stored = totalStored(data.id());
        Component title = Component.text("Level " + data.level() + " Collector", NamedTextColor.LIGHT_PURPLE)
                .decorate(TextDecoration.BOLD);
        Component status = stored >= capacity(data.level())
                ? Component.text("FULL", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD)
                : Component.text(formatCount(stored) + "/" + formatCount(capacity(data.level())) + " Items", NamedTextColor.GRAY);
        found.text(title.append(Component.newline()).append(status));
    }

    private void removeHologram(UUID id, Location around) {
        if (id == null || around == null || around.getWorld() == null) return;
        for (TextDisplay display : around.getWorld().getNearbyEntitiesByType(TextDisplay.class,
                around.clone().add(0.5, 1.8, 0.5), 3.0)) {
            String raw = display.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
            if (id.toString().equals(raw)) display.remove();
        }
    }

    private ItemStack createCollectorItem(int level, Mode mode, UUID id, Set<Material> filters) {
        int safeLevel = clampLevel(level);
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text("Level " + safeLevel + " Collector", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.BYTE, (byte)1);
        pdc.set(collectorIdKey, PersistentDataType.STRING, (id == null ? UUID.randomUUID() : id).toString());
        pdc.set(levelKey, PersistentDataType.INTEGER, safeLevel);
        pdc.set(modeKey, PersistentDataType.STRING, (mode == null ? Mode.STORE : mode).name());
        pdc.set(filterKey, PersistentDataType.STRING, encodeFilters(filters));
        meta.lore(List.of(
                Component.text("Collects dropped items from exactly one chunk.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Capacity: " + formatCount(capacity(safeLevel)) + " items", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Place inside your own faction claim.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Upgrades persist when picked up.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private boolean sameFaction(Player player, CollectorData data) {
        if (player.hasPermission("miracollectors.admin")) return true;
        return factions.factionId(player.getUniqueId()).map(id -> id.equals(data.faction())).orElse(false);
    }

    private long totalStored(UUID id) {
        return storage.getOrDefault(id, List.of()).stream().mapToLong(StoredEntry::count).sum();
    }

    private boolean sellModeAvailable() {
        if (economy == null) {
            var reg = getServer().getServicesManager().getRegistration(Economy.class);
            economy = reg == null ? null : reg.getProvider();
        }
        if (shopBridge == null) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("MiraShop");
            if (plugin != null && plugin.isEnabled()) shopBridge = new ShopBridge(plugin);
        }
        return economy != null && shopBridge != null;
    }

    private Barrel targetedCollector(Player player) {
        Block block = player.getTargetBlockExact(6);
        return block != null && block.getState() instanceof Barrel barrel && isCollectorBarrel(barrel) ? barrel : null;
    }

    private boolean isCollectorItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte)1;
    }

    private boolean isCollectorBlock(Block block) {
        return block != null && block.getState() instanceof Barrel barrel && isCollectorBarrel(barrel);
    }

    private boolean isCollectorBarrel(Barrel barrel) {
        return barrel.getPersistentDataContainer().has(collectorIdKey, PersistentDataType.STRING);
    }

    private boolean isCollectorInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Barrel barrel && isCollectorBarrel(barrel);
    }

    private CollectorData readCollector(Barrel barrel, UUID fallbackId) {
        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        UUID owner = parseUuid(pdc.get(ownerKey, PersistentDataType.STRING));
        UUID faction = parseUuid(pdc.get(factionKey, PersistentDataType.STRING));
        if (owner == null || faction == null) return null;
        UUID id = parseUuid(pdc.get(collectorIdKey, PersistentDataType.STRING));
        if (id == null) id = fallbackId;
        if (id == null) return null;
        Location l = barrel.getLocation();
        return new CollectorData(id, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                owner, faction, clampLevel(pdc.getOrDefault(levelKey, PersistentDataType.INTEGER, 1)),
                parseMode(pdc.get(modeKey, PersistentDataType.STRING)),
                decodeFilters(pdc.get(filterKey, PersistentDataType.STRING)));
    }

    private void updateRegistry(Block block, Barrel barrel) {
        CollectorData data = readCollector(barrel, null);
        if (data != null) {
            collectors.put(key(block.getLocation()), data);
            save();
        }
    }

    private void load() {
        getDataFolder().mkdirs();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("collectors");
        if (root == null) return;

        for (String locationKey : root.getKeys(false)) {
            try {
                String base = "collectors." + locationKey + ".";
                UUID id = UUID.fromString(Objects.requireNonNull(yaml.getString(base + "id")));
                UUID owner = UUID.fromString(Objects.requireNonNull(yaml.getString(base + "owner")));
                UUID faction = UUID.fromString(Objects.requireNonNull(yaml.getString(base + "faction")));
                CollectorData data = new CollectorData(id,
                        Objects.requireNonNull(yaml.getString(base + "world")),
                        yaml.getInt(base + "x"), yaml.getInt(base + "y"), yaml.getInt(base + "z"),
                        owner, faction, clampLevel(yaml.getInt(base + "level", 1)),
                        parseMode(yaml.getString(base + "mode", "STORE")),
                        decodeFilters(String.join(",", yaml.getStringList(base + "filters"))));
                collectors.put(locationKey, data);

                List<StoredEntry> entries = new ArrayList<>();
                ConfigurationSection stored = yaml.getConfigurationSection(base + "storage");
                if (stored != null) {
                    for (String index : stored.getKeys(false)) {
                        ItemStack item = yaml.getItemStack(base + "storage." + index + ".item");
                        long count = yaml.getLong(base + "storage." + index + ".count", 0L);
                        if (item != null && !item.getType().isAir() && count > 0) {
                            item.setAmount(1);
                            entries.add(new StoredEntry(item, count));
                        }
                    }
                }
                storage.put(id, entries);
            } catch (RuntimeException ex) {
                getLogger().warning("Skipped invalid collector record " + locationKey + ": " + ex.getMessage());
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, CollectorData> mapEntry : collectors.entrySet()) {
            CollectorData data = mapEntry.getValue();
            String base = "collectors." + mapEntry.getKey() + ".";
            yaml.set(base + "id", data.id().toString());
            yaml.set(base + "world", data.world());
            yaml.set(base + "x", data.x());
            yaml.set(base + "y", data.y());
            yaml.set(base + "z", data.z());
            yaml.set(base + "owner", data.owner().toString());
            yaml.set(base + "faction", data.faction().toString());
            yaml.set(base + "level", data.level());
            yaml.set(base + "mode", data.mode().name());
            yaml.set(base + "filters", data.filters().stream().map(Material::name).sorted().toList());

            List<StoredEntry> entries = storage.getOrDefault(data.id(), List.of());
            for (int i = 0; i < entries.size(); i++) {
                yaml.set(base + "storage." + i + ".item", entries.get(i).template());
                yaml.set(base + "storage." + i + ".count", entries.get(i).count());
            }
        }
        try { yaml.save(file); }
        catch (IOException ex) { getLogger().severe("Could not save collectors.yml: " + ex.getMessage()); }
    }

    private Set<Material> decodeFilters(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        LinkedHashSet<Material> out = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            Material material = Material.matchMaterial(token.trim());
            if (material != null && !material.isAir()) out.add(material);
        }
        return Set.copyOf(out);
    }

    private String encodeFilters(Set<Material> filters) {
        if (filters == null || filters.isEmpty()) return "";
        return String.join(",", filters.stream().map(Material::name).sorted().toList());
    }

    private void msg(CommandSender sender, String raw) { core.messages().send(sender, raw); }

    private static long capacity(int level) {
        return switch (clampLevel(level)) {
            case 1 -> 1_728L;
            case 2 -> 10_000L;
            case 3 -> 50_000L;
            case 4 -> 100_000L;
            default -> 500_000L;
        };
    }

    private static int clampLevel(int level) { return Math.max(1, Math.min(5, level)); }

    private static Mode parseMode(String raw) {
        try { return raw == null ? Mode.STORE : Mode.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Mode.STORE; }
    }

    private static UUID parseUuid(String raw) {
        try { return raw == null ? null : UUID.fromString(raw); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static String prettyMaterial(Material material, long amount) {
        String lower = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : lower.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        String name = out.toString();
        if (amount == 1) return name;
        if (name.endsWith("s")) return name;
        return name + "s";
    }

    private static String formatCount(long value) {
        if (value >= 1_000_000) return String.format(Locale.US, "%.1fm", value / 1_000_000D);
        if (value >= 1_000) {
            double k = value / 1_000D;
            return Math.abs(k - Math.rint(k)) < 0.05 ? String.format(Locale.US, "%.0fk", k) : String.format(Locale.US, "%.1fk", k);
        }
        return Long.toString(value);
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }

    private record StorageHolder(UUID id, String locationKey) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    public enum Mode { STORE, SELL }

    public record CollectorSnapshot(UUID id, UUID owner, String world, int x, int y, int z, int level, Mode mode) {
        public Location location() {
            World world = Bukkit.getWorld(this.world);
            return world == null ? null : new Location(world, x, y, z);
        }
    }

    public interface CollectorsApi {
        boolean isCollector(Location location);
        Optional<CollectorSnapshot> collectorAt(Location location);
        CollectorSaleResult sellAll(Player actor, Location location, double multiplier);
        int count();
        ItemStack create(int level, Mode mode);
    }

    public record CollectorSaleResult(boolean success, long units, double payout, String label, String message) {
        public static CollectorSaleResult fail(String message) {
            return new CollectorSaleResult(false, 0L, 0D, "Items", message);
        }
        public static CollectorSaleResult success(long units, double payout, String label) {
            return new CollectorSaleResult(true, units, payout, label == null || label.isBlank() ? "Items" : label, "");
        }
    }

    private final class CollectorsApiImpl implements CollectorsApi {
        @Override public boolean isCollector(Location location) { return location != null && collectors.containsKey(key(location)); }
        @Override public Optional<CollectorSnapshot> collectorAt(Location location) {
            CollectorData data = location == null ? null : collectors.get(key(location));
            return data == null ? Optional.empty() : Optional.of(data.snapshot());
        }
        @Override
        public CollectorSaleResult sellAll(Player actor, Location location, double multiplier) {
            if (actor == null || location == null) return CollectorSaleResult.fail("Invalid collector sale.");
            if (!Double.isFinite(multiplier) || multiplier <= 0D) return CollectorSaleResult.fail("Invalid sell wand multiplier.");

            CollectorData data = collectors.get(key(location));
            if (data == null) return CollectorSaleResult.fail("That is not a MiraCollector.");
            if (!sameFaction(actor, data)) return CollectorSaleResult.fail("That collector does not belong to your faction.");
            if (!sellModeAvailable()) return CollectorSaleResult.fail("MiraShop or Vault is currently unavailable.");

            List<StoredEntry> entries = storage.get(data.id());
            if (entries == null || entries.isEmpty()) return CollectorSaleResult.fail("That collector is empty.");

            try {
                ShopSnapshot snapshot = shopBridge.snapshot();
                List<SaleEntry> sellable = new ArrayList<>();
                long units = 0L;
                double base = 0D;

                for (StoredEntry stored : entries) {
                    if (stored.count() <= 0) continue;
                    PriceEntry price = snapshot.find(stored.template());
                    if (price == null || !Double.isFinite(price.unitPrice()) || price.unitPrice() < 0D) continue;

                    double line = price.unitPrice() * stored.count();
                    if (!Double.isFinite(line) || line < 0D) return CollectorSaleResult.fail("Collector sale total was invalid.");
                    base += line;
                    if (!Double.isFinite(base) || base < 0D) return CollectorSaleResult.fail("Collector sale total was invalid.");
                    units += stored.count();
                    if (units < 0L) return CollectorSaleResult.fail("Collector sale count overflowed.");
                    sellable.add(new SaleEntry(stored, price));
                }

                if (sellable.isEmpty() || units <= 0L) {
                    return CollectorSaleResult.fail("That collector has no MiraShop-sellable items.");
                }

                double payout = base * multiplier;
                if (!Double.isFinite(payout) || payout < 0D) return CollectorSaleResult.fail("Collector payout was invalid.");
                if (!economy.depositPlayer(actor, payout).transactionSuccess()) {
                    return CollectorSaleResult.fail("The economy rejected the collector payout.");
                }

                for (SaleEntry sale : sellable) entries.remove(sale.stored());
                save();
                updateHologram(data);

                for (SaleEntry sale : sellable) {
                    int amount = Math.toIntExact(sale.stored().count());
                    double linePayout = sale.price().unitPrice() * amount * multiplier;
                    try {
                        shopBridge.recordSell(sale.price().rawItem(), amount, linePayout);
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        getLogger().warning("Collector sale stats hook failed after successful sale: " + ex.getMessage());
                    }
                    Bukkit.getPluginManager().callEvent(new CollectorSellEvent(
                            data.id(), actor.getUniqueId(), data.location(),
                            sale.stored().template().getType(), amount, linePayout));
                }

                core.audit().record("MiraCollectors", "COLLECTOR_SELL_WAND_SALE",
                        actor.getUniqueId(), actor.getName(), data.id().toString(), "Collector sold with sell wand",
                        Map.of("units", Long.toString(units), "payout", Double.toString(payout),
                                "multiplier", Double.toString(multiplier)));
                String label = sellable.size() == 1
                        ? prettyMaterial(sellable.get(0).stored().template().getType(), units)
                        : "Items";
                return CollectorSaleResult.success(units, payout, label);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                getLogger().warning("Collector sell-all failed: " + ex.getMessage());
                return CollectorSaleResult.fail("Could not price collector contents through MiraShop.");
            }
        }

        @Override public int count() { return collectors.size(); }
        @Override public ItemStack create(int level, Mode mode) {
            return createCollectorItem(level, mode, UUID.randomUUID(), Set.of());
        }
    }

    private record CollectorData(UUID id, String world, int x, int y, int z,
                                 UUID owner, UUID faction, int level, Mode mode, Set<Material> filters) {
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
        CollectorSnapshot snapshot() { return new CollectorSnapshot(id, owner, world, x, y, z, level, mode); }
    }

    private static final class StoredEntry {
        private final ItemStack template;
        private long count;
        StoredEntry(ItemStack template, long count) { this.template = template.clone(); this.template.setAmount(1); this.count = count; }
        ItemStack template() { return template; }
        long count() { return count; }
        void count(long count) { this.count = Math.max(0L, count); }
    }

    private record SaleEntry(StoredEntry stored, PriceEntry price) { }

    private record PriceEntry(Object rawItem, Material material, boolean custom, ItemStack template, double unitPrice) { }

    private record ShopSnapshot(List<PriceEntry> entries) {
        static ShopSnapshot empty() { return new ShopSnapshot(List.of()); }
        PriceEntry find(ItemStack stack) {
            if (stack == null || stack.getType().isAir()) return null;
            for (PriceEntry entry : entries) {
                if (!entry.custom() || entry.material() != stack.getType()) continue;
                ItemStack one = stack.clone(); one.setAmount(1);
                if (one.isSimilar(entry.template())) return entry;
            }
            ItemStack plain = stack.clone(); plain.setAmount(1);
            if (!plain.isSimilar(new ItemStack(stack.getType()))) return null;
            return entries.stream().filter(e -> !e.custom() && e.material() == stack.getType()).findFirst().orElse(null);
        }
    }

    private static final class ShopBridge {
        private final Plugin plugin;
        ShopBridge(Plugin plugin) { this.plugin = plugin; }

        ShopSnapshot snapshot() throws ReflectiveOperationException {
            Object catalog = plugin.getClass().getMethod("catalog").invoke(plugin);
            Object sales = plugin.getClass().getMethod("sales").invoke(plugin);
            Collection<?> sections = (Collection<?>) catalog.getClass().getMethod("sections").invoke(catalog);
            List<PriceEntry> entries = new ArrayList<>();
            for (Object section : sections) {
                Collection<?> items = (Collection<?>) section.getClass().getMethod("items").invoke(section);
                for (Object item : items) {
                    if (!(boolean)item.getClass().getMethod("canSell").invoke(item)) continue;
                    Material material = (Material)item.getClass().getMethod("material").invoke(item);
                    boolean custom = (boolean)item.getClass().getMethod("customTemplate").invoke(item);
                    ItemStack template = ((ItemStack)item.getClass().getMethod("template").invoke(item)).clone();
                    template.setAmount(1);
                    double unit = ((Number)sales.getClass().getMethod("sellPrice", item.getClass()).invoke(sales, item)).doubleValue();
                    if (Double.isFinite(unit) && unit >= 0) entries.add(new PriceEntry(item, material, custom, template, unit));
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
