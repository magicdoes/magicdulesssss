# MagicDuels 1.8.0

MagicSMP duel plugin for Paper 26.2 / Java 25.

## Duel flow
1. `/duel <player>` or `/duel <player> <wager>` sends a request.
2. The challenged player sees the wager before accepting.
3. `/duel accept` opens the gamemode GUI.
4. The challenged player clicks a gamemode.
5. Both players' real inventories are saved and replaced with the selected gamemode kit.
6. Both players teleport to the arena, fight, and then get their original inventory/location back.

## Default gamemodes
- Sword PvP
- Axe PvP
- Archer
- Netherite
- Crystal PvP

All gamemode icons, GUI slots, armor and inventory items can be edited in `config.yml`.

## Player commands
- `/duel <player>`
- `/duel <player> <wager>`
- `/duel accept`
- `/duel deny`
- `/duel status`

Wagers accept `1k`, `25k`, `1.5m`, `2m`, `1b`, etc.

## Admin commands
- `/duel setspawn 1`
- `/duel setspawn 2`
- `/duel reload`

## GitHub build
Upload the contents of the `MagicDuels` folder to the root of your GitHub repository, then use **Actions -> Build MagicDuels -> Run workflow**.

Download the `MagicDuels-1.8.0` artifact after the build finishes and put the JAR in the server's `plugins` folder.

## PvP placement/drop rules added in 1.8.0
- Players cannot drop items in any duel mode, including solo `/duel test`.
- Players cannot pick up outside items during a duel/test.
- Block placement is disabled in every mode except Crystal PvP.
- In Crystal PvP, only obsidian blocks may be placed.
- End crystals are only usable in Crystal PvP.
- Block breaking remains disabled so the arena itself cannot be damaged.


## 1.8.0
- Sword PvP now explicitly uses an empty offhand (`AIR`).
- Missing, blank, `AIR`, or `NONE` offhand values are treated as an empty offhand.

## Crystal cleanup
Crystal PvP now tracks obsidian and end crystals placed during each duel or solo test. They are automatically removed when the duel ends, is cancelled, a player forfeits/disconnects, the plugin shuts down, or `/duel testend` is used. Existing arena obsidian is not removed.

## Personal duel kit layouts (v1.12.1)
Players can use `/duel layout` to choose a duel mode and rearrange the kit's inventory slots. Items cannot be moved into the player's real inventory or removed from the editor. Use **Save Layout** to save the positions, **Reset to Default** to restore the configured kit slots, or **Close** to exit without saving. Layouts are stored per player and per mode in `plugins/MagicDuels/layouts.yml`.


## 1.12.1 command lock
During real duels and solo test mode, commands are blocked with a message. OPs/admins can always use `/duel testend` to exit test mode.
