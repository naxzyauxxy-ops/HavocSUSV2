# HavocSus

A bridge plugin for Purpur/Paper **1.21.x** that sits between **SUS** (the flagged-player staff GUI) and **PremiumVanish**.

Staff clicks a cheater in the SUS GUI → SUS teleports them → HavocSus takes over:

| | |
|---|---|
| **Vanished spectator** | PremiumVanish hides them, gamemode goes to spectator |
| **150-block leash** | They cannot get further than 150 blocks from that player |
| **Spectator menu locked** | They can only spectate the suspect, not tour the map |
| **Double-shift** | Toggle spectator ↔ survival, still vanished, still leashed |
| **No building** | Break and place are refused for the entire session, in both modes |
| **Command whitelist** | Only `/punish` and `/sus` go through — everything else is refused |
| **Vanish locked on** | Can't un-vanish mid-escort by command, toggle item, or API |
| **Free spectate** | `/escort spec` — vanished spectator, no leash, watch anyone |
| **Exit** | `/escort quit` — original gamemode, flight state, position and vanish state all restored |

## Why it's built this way

Neither SUS nor PremiumVanish is bundled, decompiled, or recompiled into this project. Both are hooked at runtime:

- **PremiumVanish** → reflective calls into its public `de.myzelyam.api.vanish.VanishAPI` (`hidePlayer`, `showPlayer`, `isInvisible`).
- **SUS** → it exposes no API and fires no event, so the bridge identifies its GUI by inventory-holder package (`com.simplesetupmc.sus.gui.*`) and reads the flagged player's UUID from the clicked item's public persistent-data container (`target_uuid`), then watches for the teleport SUS performs.

### SUS version compatibility

Verified against **SUS 1.0.7** (and 1.0.5). The holder package, the `target_uuid` PDC key, the plugin name and the `/sus` command are all unchanged between them, so the hook is stable.

The one behavioural change in 1.0.7: the GUI click is now split — **left-click teleports, right-click dismisses the flag** without moving you. `engage.ignore-right-click` (default `true`) makes the bridge skip right-clicks so dismissing a flag doesn't arm an escort. Set it to `false` if you ever run 1.0.5 or older, where every click teleported.

That means this jar compiles and builds in CI with only `paper-api` — no paid jars in your repo — and degrades gracefully: no PremiumVanish means escorts run unvanished, no SUS means you start them with `/escort <player>`.

## Build

```bash
mvn clean package
# → target/HavocSus-1.0.0.jar
```

Requires JDK 21. CI is in `.github/workflows/build.yml` — it builds on every push, uploads the jar as an artifact, and publishes a GitHub release when you push a `v*` tag.

## Install

1. Drop `HavocSus-1.0.0.jar` into `plugins/` alongside SUS and PremiumVanish.
2. Start once to generate `plugins/HavocSus/config.yml`.
3. Tune `leash.radius` (default 150) and `double-sneak.survival-gamemode`, then `/escort reload`.

## Commands

| Command | Permission | |
|---|---|---|
| `/escort spec [player]` | `havocsus.patrol` | Free vanished spectate, no leash |
| `/escort quit` | `havocsus.use` | End your session |
| `/escort status` | `havocsus.use` | Current target, distance, mode |
| `/escort <player>` | `havocsus.use` | Start an escort manually |
| `/escort radius <n>` | `havocsus.admin` | Change and save the leash radius |
| `/escort reload` | `havocsus.admin` | Reload config |

Aliases: `/havocsus`, `/hs`.

## Permissions

- `havocsus.use` — can be escorted, can use quit/status
- `havocsus.admin` — reload, radius
- `havocsus.bypass` — never gets put into an escort at all (for owners who want raw SUS behaviour)
- `havocsus.bypass.leash` — escorted, but distance-unlimited
- `havocsus.bypass.commands` — ignores the command whitelist mid-escort (but *not* `always-denied-commands`)
- `havocsus.bypass.vanish` — may un-vanish during an escort

## The `/sus` command

HavocSus registers `/sus` itself **only if no other plugin already provides it**, checked at enable time. That check matters: declaring `sus` as an alias in `plugin.yml` would be a coin flip on load order, and losing it would exile SUS's own command to `sus:sus` and break its GUI. So:

- **SUS installed** → SUS keeps `/sus`. HavocSus intercepts it (see below).
- **SUS not installed** → HavocSus provides `/sus` itself.

Either way the command exists. Disable with `sus-command.register-if-absent: false`.

| | |
|---|---|
| `/sus` | Opens the watch list |
| `/sus <player>` | Teleports straight to them and starts watching |
| `/escort list` | Same watch list, whoever owns `/sus` |

## The watch list dialog

On 1.21.7+ the list is a real dialog screen (Minecraft's dialog feature, not a chest GUI): one button per online player, tooltips showing their world, plus a Free spectate button.

All Dialog API usage is confined to `WatchDialog`, which is only loaded after a `Class.forName` check. On older servers, or if building the dialog ever throws, it falls back to a clickable chat list with identical behaviour.

Dialog callbacks arrive off the main thread, so every click hops back onto the server thread before teleporting or changing gamemode.

## `/sus <player>` — direct teleport

SUS's own `/sus <player>` opens that player's **history GUI**; it doesn't teleport (its player branch calls `GuiManager`, not `TeleportManager`). Only a left-click on a head in the main GUI teleports. So there was no way to go straight to someone without opening a menu.

`sus-command.direct-teleport` fixes that. `/sus <online player>` now teleports you to them and starts a watch session immediately. HavocSus doesn't register a competing `/sus` command — it intercepts the chat event before SUS's executor runs, so there's no command conflict and SUS keeps ownership.

Everything else falls through untouched:

| | |
|---|---|
| `/sus` | SUS's main GUI — clicking a head still teleports and escorts |
| `/sus reload`, `/sus clear` | SUS admin commands |
| `/sus <offline or misspelled name>` | SUS's history GUI, so lookups still work |

## Two session types

**Escort** (SUS-driven) is leashed to one suspect and spectate-locked to them. That lock is what stops the bubble becoming a free map tour, so it stays.

**Patrol** (`/escort spec`) is the free version: vanished spectator, no leash, no target lock, so you can watch anyone on the server whether or not they're flagged. What it *keeps* is the lockdown — no breaking, no placing, no looting, and the same command whitelist. It's the safe replacement for dropping into spectator by hand.

Both leave the same way, with `/escort quit`, and both restore your gamemode, flight state, position and vanish state.

## Restrictions while escorting

**Commands are a whitelist, not a blacklist.** `restrictions.allowed-commands` in the config lists what gets through; everything else is refused, including namespaced forms like `/essentials:tp` (the namespace is stripped before the check, so that dodge doesn't work). Default list:

```yaml
allowed-commands:
  - punish
  - sus
  - suspicious
  - havocsus   # \
  - escort     #  |- so /escort quit still works
  - hs         # /
```

Delete the last three if you want them blocked too — but then double-shift and disconnecting are the only ways out of a session.

**Vanish is locked on.** `/v`, `/vanish`, `/pv` and friends are in `always-denied-commands`, which is checked *before* `havocsus.bypass.commands` — so even a staffer with the bypass permission can't drop vanish mid-escort. On top of that, `lock-vanish` cancels PremiumVanish's `PlayerShowEvent` outright, which catches the routes a command blacklist misses: the hotbar toggle item, staff GUIs, and other plugins calling the API. Bypass is `havocsus.bypass.vanish`, off by default. Ending the session normally still un-vanishes you — the session is removed from the map before the un-vanish fires, so the lock doesn't block its own cleanup.

**Building is blocked outright**, in spectator *and* solid mode, via `BlockBreakEvent` / `BlockPlaceEvent` at `HIGHEST`.

**Container access** is blocked in solid mode, but only for *real world containers* — chests, barrels, furnaces, hoppers, minecarts, horses. Plugin menus (holder is null or a custom `InventoryHolder`) are deliberately left alone, otherwise `/punish` would open its GUI and have it instantly slammed shut.

## Do not hot-reload this plugin

Use a full server restart. Do not use PlugManX (`/plm restart`, `/plm reload`) or any other reload manager on HavocSus — or, honestly, on any plugin on Paper/Purpur.

Reload managers close the plugin's jar handle but leave its classloader registered in the server's classloader group. Two things then break:

1. `getResource()` returns null, so `saveDefaultConfig()` throws and the plugin fails to enable. (Guarded against as of 1.0.1 — it now falls back to the config on disk or built-in defaults.)
2. Worse, and **not** something this plugin can guard against: any *other* plugin that triggers a class lookup walks the classloader group, reaches the dead HavocSus loader, and gets `IllegalStateException: zip file closed`. That failure surfaces inside the unrelated plugin, not this one.

If you see `zip file closed` naming HavocSus in another plugin's stack trace, the fix is a full restart. Nothing is wrong with the jar.

## Notes

- Purpur is a Paper fork, so `paper-api` is the correct compile target. `api-version: '1.21'` covers the whole 1.21 line.
- **Not Folia-safe.** SUS advertises Folia support; this plugin uses the Bukkit scheduler and would need the region scheduler to run on Folia. Purpur isn't Folia, so this only matters if you migrate.
- The leash is a *soft wall*: movement that would increase distance past the limit is refused, so it feels like a barrier rather than rubber-banding. The tick task only hard-teleports when the suspect themselves outruns the radius or something teleports staff out of it.
