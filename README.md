# MiraCollectors

MiraCollectors provides protected persistent collection barrels for the Mira Paper server suite. Collectors gather nearby dropped items into physical storage or automatically sell safe eligible drops using current MiraShop pricing.

## Download

[**Download MiraCollectors v0.1.2**](https://github.com/FiveSOCE/Mira-Collectors/releases/download/v0.1.2/MiraCollectors-0.1.2.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Collectors/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- Vault
- A Vault-compatible economy provider for SELL mode
- MiraShop for SELL mode

STORE mode remains available if MiraShop is absent.
- MiraCosmetics optional for centralized audio effects

## Collector Identity and Persistence

Every collector has a persistent UUID, owner, level and mode. Breaking a collector now preserves its UUID, upgrade level and mode on the dropped collector item. Replacing it assigns ownership to the new placer while keeping the collector's upgraded state.

Older v0.1.0 collectors without a UUID are migrated automatically when loaded/touched.

## STORE Mode

STORE collectors pull nearby dropped items into their barrel inventory. Owner protection prevents other players from opening the collector. Hopper insertion/extraction is blocked so external automation cannot bypass ownership or silently drain stored items.

When an owner/admin breaks a collector, its stored contents are safely dropped alongside the collector item instead of being discarded.

## SELL Mode

SELL mode uses current MiraShop sell pricing, including active sale events.

The sale order is transaction-safe:

1. Match the dropped item against current MiraShop pricing.
2. Reject modified generic items unless they match an explicit MiraShop custom template.
3. Ask Vault to deposit the complete payout to the collector owner.
4. Only remove the dropped entity after Vault reports transaction success.
5. Record the successful sale into MiraShop economy analytics.

If Vault rejects the payout, the dropped item remains in the world.

## Physical Protection

MiraCollectors protects managed collector barrels against:

- non-owner interaction/breaking
- hopper inventory movement
- piston movement
- block/entity explosions
- burning

Normal server protection plugins still get first say because collector listeners respect already-cancelled events.

## Commands

Look directly at a placed MiraCollector for management commands.

| Command | Permission | What it does |
| --- | --- | --- |
| `/collector give <player>` | `miracollectors.admin` | Gives a new level-1 STORE collector. |
| `/collector info` | `miracollectors.use` | Shows collector ID, level, radius, mode and storage usage. |
| `/collector mode <store|sell>` | `miracollectors.use` | Changes the targeted owned collector mode. |
| `/collector upgrade` | `miracollectors.use` | Upgrades the collector up to level 5. |

Upgrade cost remains 8, 16, 24 and 32 diamonds for levels 2 through 5.

## API / Events

`CollectorsApi` is registered through Bukkit ServicesManager and MiraCore. It exposes collector lookup by location/owner, current snapshots, count and safe collector-item creation.

A typed `CollectorSellEvent` fires after a successful automated sale.

Administrative grants and collector place/break/mode/upgrade changes are written to MiraCore audit history. High-frequency successful sale auditing is configurable and disabled by default to avoid flooding the audit log.

## Configuration

`config.yml` controls the collector tick interval and optional successful-sale auditing.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.

## MiraCosmetics Audio Integration (0.1.2)

MiraCosmetics audio hooks cover successful collector auto-sales. MiraCosmetics applies a per-player cooldown to prevent rapid collector sales from becoming sound spam.
