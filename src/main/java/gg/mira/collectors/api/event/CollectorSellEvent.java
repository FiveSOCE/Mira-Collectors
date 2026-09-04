package gg.mira.collectors.api.event;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class CollectorSellEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID collectorId;
    private final UUID owner;
    private final Location collectorLocation;
    private final Material material;
    private final int units;
    private final double payout;

    public CollectorSellEvent(UUID collectorId, UUID owner, Location collectorLocation,
                              Material material, int units, double payout) {
        this.collectorId = collectorId;
        this.owner = owner;
        this.collectorLocation = collectorLocation.clone();
        this.material = material;
        this.units = units;
        this.payout = payout;
    }

    public UUID collectorId() { return collectorId; }
    public UUID owner() { return owner; }
    public Location collectorLocation() { return collectorLocation.clone(); }
    public Material material() { return material; }
    public int units() { return units; }
    public double payout() { return payout; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
