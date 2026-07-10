# Farmer

Farmer is a Paper plugin that collects configured drops inside supported regions and islands, stores them in a virtual inventory, and provides controlled selling, withdrawal, user management, upgrades, and module actions.

## Compatibility

| Component | Support |
| --- | --- |
| Paper 1.21.x | Supported |
| Paper 26.x | Supported and used for compilation |
| Leaf | Supported |
| Folia | Supported |
| Plain Bukkit / Spigot | Not supported; startup is rejected |
| Geyser / Floodgate | Optional native Bedrock forms |

Farmer v6-b115 is compiled against Paper API `26.1.2.build.74-stable` while retaining `api-version: "1.21"`. Server scheduling uses Paper/Folia-aware schedulers for player, region, global, and asynchronous work.

## Features

- Region and island integration with configurable collection rules.
- Virtual per-farmer storage, levels, capacity, tax, and economy support.
- Owner, member, and coop access controls.
- Configurable selling, item withdrawal, user management, and module menus.
- English and Turkish language files, with module-provided locales supported.
- Native Bedrock forms without changing the Java inventory experience.
- Public APIs and Bukkit events for modules and integrations.

## Bedrock Forms

When Floodgate or Geyser is installed, connected Bedrock players automatically receive native Cumulus forms. Java players continue to receive the existing inventory menus.

The form adapter covers the purchase, storage, management, user, and module menus. Items added to the module menu through `FarmerModuleGuiCreateEvent` are included automatically. Inventory page controls are replaced by native form pagination, and only elements explicitly identified as fillers are omitted. Functional items are never hidden merely because they use stained glass or another decorative-looking material.

Form responses are fail-closed and protected by a unique, single-use session. Farmer validates the active session, response index, timeout, cooldown, player state, and menu action before dispatching it on the player's Folia-safe scheduler. Sessions are removed when a form is replaced, a player disconnects, the plugin reloads, or the plugin disables. The adapter does not add click sounds, preventing duplicate inventory/form feedback.

Bedrock behavior is configured under `bedrock-forms` in `config.yml`:

```yaml
bedrock-forms:
  enabled: true
  page-size: 20
  session-timeout-ms: 30000
  click-cooldown-ms: 250
  max-lore-lines: 4
  max-button-length: 180
```

Farmer uses the official [Floodgate API](https://geysermc.org/wiki/floodgate/api/) and [Geyser Forms API](https://geysermc.org/wiki/geyser/forms/). These APIs are optional and are not shaded into the Farmer jar.

## Module Menus

Existing modules that add elements during `FarmerModuleGuiCreateEvent` require no Bedrock-specific changes. Farmer converts those elements and exposes primary, secondary, alternate, and module actions as configurable form choices.

Modules with their own nested `InventoryGui` menus can route them through the same form lifecycle:

```java
InventoryGui gui = createModuleGui(player, farmer);
FarmerBedrockAPI.openModuleMenu(
        player,
        gui,
        () -> openModuleGui(player, farmer)
);
```

The fallback opens the normal Java inventory when the player is not connected through Geyser/Floodgate. Module actions should continue to enforce permissions and validate current Farmer state server-side.

## Localization

Language files are stored in `plugins/Farmer/lang/`. Menu titles, item names, lore, Bedrock form labels, paging controls, action names, command templates, and command/chat responses are configurable there.

Farmer storage entries use the configurable `gui.farmer-gui.items.group-items.name` template and optional per-material `names` map, so servers can localize every displayed stock item instead of relying on client material names.

Notable sections include:

- `messages`: command, chat, economy, validation, reload, and Bedrock form feedback.
- `commands`: complete `/farmer about` and `/farmer info` templates.
- `bedrock-forms`: form content, navigation, stock actions, user actions, and module action labels.
- `gui`: Java inventory titles, names, and lore.

Use `/farmer reload` after editing runtime configuration or language values.

## Installation

1. Run Paper, Leaf, or Folia on a Minecraft version supported by this release.
2. Place `Farmer-v6-b115.jar` in the server's `plugins` directory.
3. Install Vault and a supported economy provider when economy-backed features are enabled.
4. Optionally install Geyser and/or Floodgate for native Bedrock forms.
5. Install a supported region or island plugin and configure allowed worlds.
6. Start the server, review generated configuration, then restart or run `/farmer reload` after changes.

## Integrations

Farmer includes integrations for BentoBox, SuperiorSkyblock2, FabledSkyBlock, Lands, IridiumSkyblock, ASkyBlock, GriefPrevention, UltimateClaims, Towny, RClaim, and other project-supported providers. Availability depends on the provider version installed on the server.

## Building

The project uses Maven:

```bash
mvn clean package
```

The shaded release artifact is written to `target/Farmer-v6-b115.jar`. Geyser and Floodgate APIs are compile-time `provided` dependencies and must remain supplied by the server when Bedrock forms are enabled.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md) before opening a pull request or security report.

## License

Farmer is distributed under the terms in [LICENSE](LICENSE).
