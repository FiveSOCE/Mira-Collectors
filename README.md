# MiraCollectors

Managed collection barrels for Paper 1.21.11 / Java 21.

## Current release

**v0.1.0**

Direct download:
https://github.com/FiveSOCE/Mira-Collectors/releases/download/v0.1.0/MiraCollectors-0.1.0.jar

All releases:
https://github.com/FiveSOCE/Mira-Collectors/releases

## Features

- Special managed collector barrel item
- Persistent owner and location registry
- Five collector levels
- Upgradeable collection radius
- STORE mode for physical item collection
- SELL mode using current MiraShop sell prices and temporary sales
- Vault payouts directly to collector owner
- Exact custom-item matching before generic material pricing
- MiraShop economy analytics integration
- Ownership protection for management and breaking

## Commands

- `/collector give <player>`
- `/collector info`
- `/collector mode <store|sell>`
- `/collector upgrade`

Look at a placed MiraCollector when using its management commands.

## Build

`./gradlew build`

Output: `build/libs/MiraCollectors-0.1.0.jar`
