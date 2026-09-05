package gg.mira.collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class CosmeticsBridge {
    private CosmeticsBridge() { }

    static void play(Player player, String eventId, Location location) {
        if (player == null || eventId == null) return;
        Plugin cosmetics = Bukkit.getPluginManager().getPlugin("MiraCosmetics");
        if (cosmetics == null || !cosmetics.isEnabled()) return;
        Location at = location == null ? player.getLocation() : location;
        try {
            cosmetics.getClass().getMethod("playEvent", Player.class, String.class, Location.class)
                    .invoke(cosmetics, player, eventId, at);
        } catch (NoSuchMethodException ignored) {
            try {
                cosmetics.getClass().getMethod("playVisualEvent", Player.class, String.class, Location.class)
                        .invoke(cosmetics, player, eventId, at);
            } catch (ReflectiveOperationException ignoredToo) { }
        } catch (ReflectiveOperationException ignored) { }
    }

    static void playNearby(Location location, String eventId, double radius) {
        if (location == null || location.getWorld() == null || eventId == null) return;
        double radiusSquared = Math.max(0D, radius) * Math.max(0D, radius);
        for (Player viewer : location.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) <= radiusSquared) {
                play(viewer, eventId, location);
            }
        }
    }
}
