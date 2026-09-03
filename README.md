# MiraCollectors

MiraCollectors provides managed collection barrels for the Mira Paper server suite. Collector blocks gather nearby dropped items and can either store those items physically or sell eligible drops automatically using MiraShop pricing.

## Download

[**Download MiraCollectors v0.1.0**](https://github.com/FiveSOCE/Mira-Collectors/releases/download/v0.1.0/MiraCollectors-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- Vault optional for economy payouts
- A Vault-compatible economy provider when using SELL mode
- MiraShop optional/recommended for live sell pricing and analytics
- MiraFactions optional integration

## How MiraCollectors Works

A MiraCollector is a special managed barrel with persistent ownership and location data. Collectors have five upgrade levels that increase their collection radius. In `STORE` mode, eligible nearby item drops are moved into the collector's physical inventory. In `SELL` mode, eligible drops are valued using current MiraShop sell prices, including temporary sales, and the proceeds are paid to the collector owner through Vault.

Custom item/template matches are checked before generic material prices. Collector management and breaking are ownership-protected so other players cannot freely reconfigure or remove someone else's collector.

## Commands

Look directly at a placed MiraCollector when using management commands.

| Command | Permission | What it does |
| --- | --- | --- |
| `/collector give <player>` | `miracollectors.admin` | Gives a MiraCollector item to a player. |
| `/collector info` | `miracollectors.use` | Shows information about the collector you are looking at. |
| `/collector mode <store|sell>` | `miracollectors.use` | Changes the targeted collector between physical storage and automatic selling. |
| `/collector upgrade` | `miracollectors.use` | Upgrades the targeted collector when upgrade requirements are met. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miracollectors.use` | Everyone | Allows normal owned-collector management. |
| `miracollectors.admin` | OP | Allows administrative collector actions such as giving collectors. |
