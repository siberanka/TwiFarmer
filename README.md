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

Farmer v6-b119 is compiled against Paper API `26.1.2.build.74-stable` while retaining `api-version: "1.21"`. Server scheduling uses Paper/Folia-aware schedulers for player, region, global, and asynchronous work.

The runtime bundles XSeries `v13.7.1` under Farmer's historical GLib namespace. This preserves module binary compatibility while supporting Minecraft's 26.x version format and updated material, sound, profile, reflection, and inventory APIs. Startup preflights these compatibility surfaces before commands, listeners, modules, or cached stock are published. An incompatible external module is isolated and skipped with bounded diagnostics instead of disabling Farmer.

## Features

- Region and island integration with configurable collection rules.
- Virtual per-farmer storage, levels, capacity, tax, and economy support.
- Owner, member, and coop access controls.
- Configurable selling, item withdrawal, user management, and module menus.
- English and Turkish language files, with module-provided locales supported.
- Native Bedrock forms without changing the Java inventory experience.
- Public APIs and Bukkit events for modules and integrations.

## Market Pricing

Farmer can resolve sell values from a server's existing global market instead of maintaining a second price list. The default `pricing.source: auto` selects the first available provider in `pricing.auto-priority`. If no supported market is installed, `auto` safely uses the manual values in `items.yml`.

Built-in pricing adapters:

| Provider ID | Plugin |
| --- | --- |
| `ultimateshop` | UltimateShop |
| `economyshopgui` | EconomyShopGUI and EconomyShopGUI Premium |
| `shopguiplus` | ShopGUIPlus |
| `excellentshop` | ExcellentShop |
| `zshop` | zShop |
| `guishop` | GUIShop |
| `essentials` | EssentialsX worth prices |
| `cmi` | CMI worth prices |
| `manual` | `items.yml` |

```yaml
pricing:
  source: auto
  auto-priority:
    - ultimateshop
    - economyshopgui
    - shopguiplus
    - excellentshop
    - zshop
    - guishop
    - essentials
    - cmi
```

Set `source` to a provider ID to require that market, or to `manual` to always use `items.yml`. An explicitly selected provider fails closed when unavailable, and items without a valid positive price cannot be sold. Player-specific multipliers and dynamic values are resolved when the menu is rendered and again before payment. At settlement, Farmer passes the exact stocked quantity to market APIs that support quantity-aware quotes, so volume tiers and dynamic sell rules determine the payout; providers without a dynamic quantity API retain their configured static price. Farmer accepts only the provider's Vault-compatible price where a market supports multiple currencies, preventing a points or item price from being deposited as money.

Optional market APIs are not shaded into Farmer. Integrations are loaded only after their plugin is enabled, reflection bindings are resolved once, invalid/non-finite values are rejected, and API failures are logged once per provider to avoid hot-path log flooding. Farmer requests a quote only: it does not invoke a market's inventory-removal or payout transaction, preventing duplicate payouts against Farmer's virtual storage. QuickShop, Shopkeepers, and other player-listing systems are intentionally not auto-selected because they do not expose one authoritative global server sell price.

Modules and external plugins can add a provider through `FarmerPricingAPI.registerProvider(SellPriceProvider)`. Registered IDs can be selected directly or placed in `auto-priority`; the same provider then drives Java menus, Bedrock forms, and final sale settlement.

## Official Modules

| Module | Description |
| --- | --- |
| [AutoHarvest](https://github.com/siberanka/TwiFarmer-AutoHarvest) | Harvests mature crops inside managed Farmer areas, replants supported crops, and transfers produce into Farmer storage. |
| [AutoSell](https://github.com/siberanka/TwiFarmer-AutoSell) | Automatically sells eligible stocked items when Farmer capacity rules are reached and deposits the earnings through the configured economy. |
| [SpawnerKiller](https://github.com/siberanka/TwiFarmer-SpawnerKiller) | Processes mobs created by active spawners, applies configurable entity filters, and transfers validated drops through the Farmer workflow. |

Download each module from its repository's Releases page and place its JAR in `plugins/Farmer/modules/`. Review the module README for its Farmer version requirement, optional integrations, and configuration before enabling it.

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

## Update Checker

Farmer checks only the fixed `siberanka/TwiFarmer` GitHub repository using asynchronous HTTPS at startup and every six hours by default. `update-checker.enable` defaults to `true`; connection/request timeouts, response size, `v6-bN` tags, and release URLs are bounded and validated. Reload or disable cancels or invalidates pending checks.

When a newer release exists, the console and each operator or player with `farmer.admin` receive one localized message per release. The message contains the Farmer plugin name, installed/latest versions, and a validated direct JAR download link.

```yaml
update-checker:
  enable: true
  check-interval-hours: 6
  connect-timeout-seconds: 5
  request-timeout-seconds: 8
```

## Configuration Health

Farmer validates `config.yml` and every built-in core language file before loading them at startup and during `/farmer reload`. The validator generates its canonical schema directly from the current configuration classes, so newly introduced entries are added automatically without a separately maintained migration list.

When repair is required, Farmer first stores the original file under `plugins/Farmer/backups/config-repair/<timestamp>/`. It then performs an atomic replacement after applying these checks:

- Missing entries are restored from current defaults.
- Unknown or retired entries are removed.
- Wrong YAML types, `null` values, required blank strings, oversized values, and invalid list entries are corrected.
- Unsupported languages, invalid database settings, unsafe numeric ranges, and malformed GUI layouts are restored to safe defaults.
- Malformed or oversized YAML files are backed up and regenerated rather than partially loaded.

The newest 20 backup sets are retained to keep disk usage bounded. Symbolic links and paths outside Farmer's data directory are rejected. Core config and language objects are published together after a successful reload, so running region threads never observe a half-updated pair.

Languages installed through `FarmerModule.setLang(...)` use the same backup-first repair lifecycle. Modules with custom YAML files can opt in through `FarmerConfigurationAPI.repairModuleFile(file, bundledDefaults)`.

## Installation

1. Run Paper, Leaf, or Folia on a Minecraft version supported by this release.
2. Place `Farmer-v6-b119.jar` in the server's `plugins` directory.
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

The shaded release artifact is written to `target/Farmer-v6-b119.jar`. Geyser, Floodgate, and optional market APIs are not bundled and must remain supplied by the server when their integrations are enabled.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md) before opening a pull request or security report.

## License

Farmer is distributed under the terms in [LICENSE](LICENSE).
