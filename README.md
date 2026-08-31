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
| **Exit** | `/hs quit` — original gamemode, flight state, position and vanish state all restored |

## Why it's built this way

Neither SUS nor PremiumVanish is bundled, decompiled, or recompiled into this project. Both are hooked at runtime:

- **PremiumVanish** → reflective calls into its public `de.myzelyam.api.vanish.VanishAPI` (`hidePlayer`, `showPlayer`, `isInvisible`).
- **SUS** → it exposes no API and fires no event, so the bridge identifies its GUI by inventory-holder package (`com.simplesetupmc.sus.gui.*`) and reads the flagged player's UUID from the clicked item's public persistent-data container (`target_uuid`), then watches for the teleport SUS performs.

### SUS version compatibility

Verified against **SUS 1.0.7** (and 1.0.5). The holder package, the `target_uuid` PDC key, the plugin name and the `/sus` command are all unchanged between them, so the hook is stable.

The one behavioural change in 1.0.7: the GUI click is now split — **left-click teleports, right-click dismisses the flag** without moving you. `engage.ignore-right-click` (default `true`) makes the bridge skip right-clicks so dismissing a flag doesn't arm an escort. Set it to `false` if you ever run 1.0.5 or older, where every click teleported.

That means this jar compiles and builds in CI with only `paper-api` — no paid jars in your repo — and degrades gracefully: no PremiumVanish means escorts run unvanished, no SUS means you start them with `/hs <player>`.

## Build

```bash
mvn clean package
# → target/HavocSus-1.0.0.jar
```

Requires JDK 21. CI is in `.github/workflows/build.yml` — it builds on every push, uploads the jar as an artifact, and publishes a GitHub release when you push a `v*` tag.

## Install

1. Drop `HavocSus-1.0.0.jar` into `plugins/` alongside SUS and PremiumVanish.
2. Start once to generate `plugins/HavocSus/config.yml`.
3. Tune `leash.radius` (default 150) and `double-sneak.survival-gamemode`, then `/hs reload`.

## Commands

| Command | Permission | |
|---|---|---|
| `/hs quit` | `havocsus.use` | End your session |
| `/hs status` | `havocsus.use` | Current target, distance, mode |
| `/hs <player>` | `havocsus.use` | Start an escort manually |
| `/hs radius <n>` | `havocsus.admin` | Change and save the leash radius |
| `/hs reload` | `havocsus.admin` | Reload config |

Aliases: `/havocsus`, `/hs`. There is no `/escort` — it was removed.

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
| `/hs list` | Same watch list, whoever owns `/sus` |

## Punishments

Running `/sus` again while you're already watching someone opens the **punish screen** for them rather than reopening the list. Reasons are read live from DonutPunishments' own `plugins/DonutPunishments/messages.yml` (`reasons:` block), so whatever the server has configured is what appears — there's no second list to keep in sync.

A real reason list is long (the configured set here is 60), and 60 buttons in one dialog is a wall you can't use. So the screen is **grouped by type** — Bans, Mutes, Kicks, with counts — and each category is **paged at 20 per screen** with Previous/Next. Bans are red, mutes amber, kicks blue, with type and duration in the tooltip. Reasons keep their file order so related ones stay together rather than being alphabetised apart.

Each button dispatches `/punish <player> <reason>` **as the staff member**, so their permissions apply and the punishment is attributed to them rather than to console. Template is `punish.command` if you use a different punishment plugin.

There's a "Stop watching" button on that screen too, since `/escort quit` no longer exists.

If DonutPunishments is missing or its file is unreadable, `punish.fallback-reasons` in HavocSus's config is used instead.

## Where check data comes from

**Live capture is the primary source.** Every supported anti-cheat fires its own Bukkit event when it flags someone. HavocSus hooks those events reflectively from descriptions in `alerts.sources`, so if an alert reaches chat it reaches the checks page — no dependence on SUS's storage being present, populated or shaped as expected.

The SUS database is now only a supplement, supplying history from before the server came up. The two are **not added together**: SUS records the same alerts, so summing would double-count. Live data wins whenever it exists for a player.

### Fixing a source without a rebuild

The class and method names in `alerts.sources` are best-effort — most of these anti-cheats are paid and I can't verify their APIs. So they're config, not code:

```yaml
alerts:
  sources:
    grim:
      event: "ac.grim.grimac.api.events.FlagEvent"
      player: "getPlayer,getUser"     # tried in order
      check: "getCheck,getCheckName"
      violation: "getViolations,getVl"
```

Run **`/hs diag`**. It prints one line per source: `hooked (FlagEvent)`, `not installed`, or the error. If a source says "not installed" while that anti-cheat *is* running, the class name is wrong for your version — correct it and run `/hs reload`. No rebuild.

`check` and `violation` accept a Check object, an enum or a String; the bridge asks it for `getCheckName`/`getName`/`getType` as needed.

## If the checks page is still empty

Run `/hs diag`. It reports whether SUS was found, which database file was opened, which tables were matched, how many rows are cached, and the last error — which turns "nothing shows" into an actual cause.

Two bugs made this fail silently before 1.5.1, both mine:

1. **Resolution ran once, at startup.** SUS creates its database lazily, so if no flag had ever been recorded when HavocSus enabled, alerts stayed off until a restart. It now retries every refresh cycle.
2. **The table probe only checked the table existed, not its columns.** `SELECT * FROM flags LIMIT 1` passes as long as something called `flags` is there, so a wrong guess about column names sailed through and then made every real query throw — visible in game as an empty checks page and nothing else.

Tables are now identified by the **columns they contain** rather than by name, so a table prefix, a rename, or a schema shuffle is handled. If SUS only has the per-check table, per-player counts are folded up from it.

Verified against SUS 1.0.5 and 1.0.7 — their database layer is byte-identical, so either works.

## Alert counts and checks

HavocSus reads alert data straight out of SUS's own database (`plugins/Sus/flags.db`), read-only. SUS exposes no API, but its SQL schema is unobfuscated — `flags` holds one row per player (`amount`, `anti_cheat`, `last_check`, `last_violation_level`) and `flag_history` holds one row per check. Column names survive obfuscation, which makes this far more durable than reflecting into their classes.

All queries run **off the main thread** on a timer into a cache; the dialogs never touch JDBC while rendering.

- **Watch list** is sorted worst-offender-first, with the alert count in the button label — no hovering needed to find the problem player. Green under 15, amber under 50, red above.
- **Tooltips** show the anti-cheat and the top four checks with hit counts and violation levels.
- **Checks screen** gives the full per-check breakdown for whoever you're watching.
- **Top alerts** is a leaderboard across everyone on record, online or not; online names get a button to jump straight to them.

## The dialog menu

`/sus` (or `/hs menu`) opens the hub, and everything hangs off it:

| | |
|---|---|
| Watch list | Online players, worst alerts first |
| Top alerts | Highest counts on record, including offline |
| Ban list | Active bans, paged |
| Punish a player | Pick anyone online — no need to watch them first |
| Checks / Punish / Stop watching | Shown only while you're watching someone |

The ban list is read reflectively from DonutPunishments' `BanCache` (unobfuscated, public fields). If that ever fails, the button falls back to running `/banlist` in chat rather than breaking.

## A note on `havocsus.hidefromlist`

It is an **opt-out** node, and it is ignored unless you set `watch-list.respect-hide-permission: true`.

The reason: any admin holding a wildcard (`*` or `havocsus.*` in LuckPerms) matches that node, disappears from every list, and the lists come back empty on a server where the only people online are staff. Leave it off unless you have deliberately configured the node.

## If a punishment doesn't apply

The dialog now echoes the exact command it ran and logs it, so a refusal is visible instead of looking like a dead button. Two things account for most failures:

- **The staff member lacks `punishments.punish`.** Set `punish.run-as-console: true` if you'd rather not grant it, at the cost of the punishment being attributed to console.
- **The target holds `punishments.exempt`.** This bites the same way `havocsus.hidefromlist` did: any wildcard (`*` or `punishments.*` in LuckPerms) matches it, so testing on another staff account will silently refuse the ban. Test on a non-staff account, or check the target's permissions.

## The PremiumVanish sidebar

`restrictions.hide-vanish-scoreboard` (on by default) clears the sidebar during a session, which hides PV's vanish scoreboard. It clears whatever is in that slot, not only PV's, so if you run another sidebar plugin staff lose it for the duration. The permanent alternative is `ScoreboardOptions.Enable: false` in PremiumVanish's own config.

Avoid the `pv.scoreboard` permission route — `ScoreboardOptions.Permission: true` only helps if your staff *don't* hold a wildcard, and yours do.

## Vanish timing

Staff are vanished **before** the teleport, not after it lands. Vanishing a tick later meant the suspect got a frame or two of someone popping in beside them.

The "was already vanished" snapshot is taken before that early vanish and carried into the session. Reading it afterwards would always say "already vanished" and leave staff stuck invisible when the session ended.

`engage.unvanish-on-join` (on by default) un-vanishes staff when they join, 20 ticks after login so it lands after PremiumVanish restores its own state. Without it, a staffer who logged out mid-escort comes back invisible with no session running.

## Elytra follow

When the watched player starts gliding, HavocSus snaps to them and attaches **spectator POV** — you see exactly what they see, which is also the best angle for judging flight cheats. The leash would otherwise just rubber-band you while they flew off.

It attaches once per glide. Detaching manually with shift is respected rather than re-attached a tick later; their next takeoff re-arms it. Landing releases POV automatically. `elytra.force-spectator` switches you out of solid mode on takeoff.

## Leaving spectator

Double-shifting out of spectator **does not move you**. It used to scan downward for ground and teleport there, which from any height dropped you at the bottom of the world, and inside caves or over the void left you somewhere useless. If you're in mid-air when you switch, `double-sneak.hover-if-airborne` keeps you hovering rather than falling.

## Commands still work normally

The dialogs are a convenience, not a replacement. `/punish`, `/ban`, `/kick`, `/mute`, `/banlist`, `/alts`, `/banhistory` and the rest are all on the command whitelist, so you can type them by hand at any time, including mid-session. Only vanish commands are hard-denied.

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

## Restrictions while escorting

**Commands are a whitelist, not a blacklist.** `restrictions.allowed-commands` in the config lists what gets through; everything else is refused, including namespaced forms like `/essentials:tp` (the namespace is stripped before the check, so that dodge doesn't work). Default list:

```yaml
allowed-commands:
  - punish
  - sus
  - suspicious
  - havocsus   # \
  - escort     #  |- so /hs quit still works
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
