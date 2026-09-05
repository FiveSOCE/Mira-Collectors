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
}
