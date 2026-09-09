package com.magicsmp.duels;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class MagicDuels extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, DuelRequest> incomingRequests = new HashMap<>();
    private final Map<UUID, DuelSession> sessions = new HashMap<>();
    private final Map<UUID, PendingRespawn> pendingRespawns = new HashMap<>();
    private final Map<UUID, InventorySnapshot> testSnapshots = new HashMap<>();
    private final Map<UUID, String> testModes = new HashMap<>();
    private final Map<UUID, Set<Location>> testPlacedBlocks = new HashMap<>();
    private final Map<UUID, Set<UUID>> testPlacedCrystals = new HashMap<>();
    private final Set<String> occupiedArenas = new HashSet<>();
    private Economy economy;
    private File layoutsFile;
    private YamlConfiguration layoutsConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hookEconomy();
        loadLayouts();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("duel")).setExecutor(this);
        Objects.requireNonNull(getCommand("duel")).setTabCompleter(this);
        getLogger().info("MagicDuels enabled. Economy hook: " + (economy != null));
    }

    @Override
    public void onDisable() {
        // Safely return everyone and refund wagers on plugin/server shutdown.
        Set<DuelSession> unique = new HashSet<>(sessions.values());
        for (DuelSession session : unique) {
            cancelSession(session, true, "Server/plugin shutdown");
        }
        sessions.clear();
        for (var entry : new HashMap<>(testSnapshots).entrySet()) {
            cleanupTestArena(entry.getKey());
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) { entry.getValue().restore(p); p.teleport(entry.getValue().location); }
        }
        testSnapshots.clear();
        testModes.clear();
        testPlacedBlocks.clear();
        testPlacedCrystals.clear();
        occupiedArenas.clear();
        pendingRespawns.clear();
        incomingRequests.clear();
    }

    private void hookEconomy() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("magicduels.use")) {
            msg(player, "no-permission");
            return true;
        }

        cleanupExpiredRequests();

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("test") || sub.equals("testend")) {
            if (!player.hasPermission("magicduels.admin")) {
                msg(player, "no-permission");
                return true;
            }
            if (sub.equals("testend")) {
                endTest(player);
                return true;
            }
            if (testSnapshots.containsKey(player.getUniqueId())) {
                player.sendMessage(color(prefix() + "&cYou are already testing a duel. Use &e/duel testend&c first."));
                return true;
            }
            openTestGamemodeGui(player);
            return true;
        }

        if (sub.equals("layout")) {
            if (sessions.containsKey(player.getUniqueId()) || testSnapshots.containsKey(player.getUniqueId())) {
                player.sendMessage(color(prefix() + "&cYou cannot edit duel layouts while dueling or testing."));
                return true;
            }
            openLayoutModeGui(player);
            return true;
        }

        if (sub.equals("accept") || sub.equals("deny")) {
            DuelRequest request = incomingRequests.get(player.getUniqueId());
            if (request == null) {
                msg(player, "no-request");
                return true;
            }
            if (request.expired()) {
                incomingRequests.remove(player.getUniqueId());
                msg(player, "request-expired");
                return true;
            }

            Player challenger = Bukkit.getPlayer(request.challenger);
            if (challenger == null) {
                incomingRequests.remove(player.getUniqueId());
                msg(player, "challenger-offline");
                return true;
            }

            if (sub.equals("deny")) {
                incomingRequests.remove(player.getUniqueId());
                msg(player, "request-denied-target", "%player%", challenger.getName());
                msg(challenger, "request-denied-sender", "%player%", player.getName());
                return true;
            }

            // Accepting opens the gamemode selector first. The wager is not taken
            // until a gamemode is chosen and the duel actually starts.
            openGamemodeGui(player, request);
            return true;
        }

        if (sub.equals("cancel")) {
            DuelRequest request = incomingRequests.remove(player.getUniqueId());
            if (request != null) {
                Player challenger = Bukkit.getPlayer(request.challenger);
                msg(player, "duel-cancelled");
                if (challenger != null) msg(challenger, "duel-cancelled");
            } else {
                msg(player, "no-request");
            }
            return true;
        }

        if (sub.equals("setspawn") || sub.equals("reload")) {
            if (!player.hasPermission("magicduels.admin")) {
                msg(player, "no-permission");
                return true;
            }
            if (sub.equals("reload")) {
                reloadConfig();
                hookEconomy();
                msg(player, "reloaded");
                return true;
            }
            if (args.length < 3 || !(args[2].equals("1") || args[2].equals("2"))) {
                player.sendMessage(color(prefix() + "&cUsage: /duel setspawn <gamemode> <1|2>"));
                return true;
            }
            DuelMode spawnMode = getDuelMode(args[1]);
            if (spawnMode == null) {
                player.sendMessage(color(prefix() + "&cUnknown duel gamemode: &e" + args[1]));
                return true;
            }
            getConfig().set("arena-spawns." + spawnMode.key + ".spawn" + args[2], player.getLocation());
            saveConfig();
            msg(player, "arena-spawn-set", "%number%", args[2], "%gamemode%", color(spawnMode.displayName));
            return true;
        }

        if (sub.equals("status")) {
            DuelSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                player.sendMessage(color(prefix() + "&7You are not currently in a duel."));
            } else {
                UUID opponentId = session.other(player.getUniqueId());
                Player opponent = Bukkit.getPlayer(opponentId);
                DuelMode mode = getDuelMode(session.modeKey);
                String modeName = mode == null ? session.modeKey : color(mode.displayName);
                player.sendMessage(color(prefix() + "&7Dueling: &e" + (opponent == null ? "Unknown" : opponent.getName())
                        + "&7 | Mode: &e" + modeName + "&7 | Wager: &a$" + formatMoney(session.wager)));
            }
            return true;
        }

        // /duel <player> [wager]
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            msg(player, "player-not-found");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "cannot-duel-self");
            return true;
        }
        if (sessions.containsKey(player.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            msg(player, "already-dueling");
            return true;
        }

        double wager = 0;
        if (args.length >= 2) {
            wager = parseMoney(args[1]);
            if (!Double.isFinite(wager) || wager < 0) {
                msg(player, "invalid-wager");
                return true;
            }
        }
        if (wager > 0) {
            if (!getConfig().getBoolean("wagers.enabled", true)) {
                msg(player, "wager-disabled");
                return true;
            }
            if (economy == null) {
                msg(player, "no-economy");
                return true;
            }
            double min = getConfig().getDouble("wagers.minimum", 0);
            double max = getConfig().getDouble("wagers.maximum", 1_000_000_000D);
            if (wager < min) {
                msg(player, "wager-too-small", "%amount%", formatMoney(min));
                return true;
            }
            if (wager > max) {
                msg(player, "wager-too-large", "%amount%", formatMoney(max));
                return true;
            }
            if (!economy.has(player, wager) || !economy.has(target, wager)) {
                msg(player, "not-enough-money");
                return true;
            }
        }

        int timeout = Math.max(10, getConfig().getInt("request-timeout-seconds", 60));
        incomingRequests.put(target.getUniqueId(), new DuelRequest(player.getUniqueId(), target.getUniqueId(), wager, System.currentTimeMillis() + timeout * 1000L));

        if (wager > 0) {
            String amount = formatMoney(wager);
            String pot = formatMoney(wager * 2D);
            msg(player, "request-sent-wager", "%player%", target.getName(), "%amount%", amount, "%pot%", pot);
            msg(target, "request-received-wager", "%player%", player.getName(), "%amount%", amount, "%pot%", pot);
        } else {
            msg(player, "request-sent", "%player%", target.getName());
            msg(target, "request-received", "%player%", player.getName());
        }
        return true;
    }

    private void startDuel(Player a, Player b, double wager, DuelMode mode) {
        if (!arenaReady(mode.key)) {
            msg(a, "arena-not-set", "%gamemode%", color(mode.displayName));
            msg(b, "arena-not-set", "%gamemode%", color(mode.displayName));
            return;
        }
        if (occupiedArenas.contains(mode.key)) {
            msg(a, "arena-full", "%gamemode%", color(mode.displayName));
            msg(b, "arena-full", "%gamemode%", color(mode.displayName));
            return;
        }
        if (sessions.containsKey(a.getUniqueId()) || sessions.containsKey(b.getUniqueId())) {
            msg(a, "already-dueling");
            msg(b, "already-dueling");
            return;
        }
        if (wager > 0) {
            if (economy == null || !economy.has(a, wager) || !economy.has(b, wager)) {
                msg(a, economy == null ? "no-economy" : "not-enough-money");
                msg(b, economy == null ? "no-economy" : "not-enough-money");
                return;
            }
            if (!economy.withdrawPlayer(a, wager).transactionSuccess()) {
                msg(a, "duel-cancelled");
                msg(b, "duel-cancelled");
                return;
            }
            if (!economy.withdrawPlayer(b, wager).transactionSuccess()) {
                economy.depositPlayer(a, wager);
                msg(a, "duel-cancelled");
                msg(b, "duel-cancelled");
                return;
            }
        }

        DuelSession session = new DuelSession(a, b, wager, mode.key);
        occupiedArenas.add(mode.key);
        sessions.put(a.getUniqueId(), session);
        sessions.put(b.getUniqueId(), session);

        applyKit(a, mode);
        applyKit(b, mode);
        msg(a, "gamemode-start", "%gamemode%", color(mode.displayName));
        msg(b, "gamemode-start", "%gamemode%", color(mode.displayName));

        preparePlayerForArena(a, arenaSpawn(mode.key, 1));
        preparePlayerForArena(b, arenaSpawn(mode.key, 2));

        int countdown = Math.max(1, getConfig().getInt("countdown-seconds", 3));
        session.countdownTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int left = countdown;
            @Override
            public void run() {
                if (session.ended) {
                    if (session.countdownTask != null) session.countdownTask.cancel();
                    return;
                }
                Player pa = Bukkit.getPlayer(session.a);
                Player pb = Bukkit.getPlayer(session.b);
                if (pa == null || pb == null) {
                    cancelSession(session, true, "Disconnected during countdown");
                    return;
                }
                if (left <= 0) {
                    session.active = true;
                    msg(pa, "fight");
                    msg(pb, "fight");
                    if (session.countdownTask != null) session.countdownTask.cancel();
                    return;
                }
                msg(pa, "countdown", "%seconds%", String.valueOf(left));
                msg(pb, "countdown", "%seconds%", String.valueOf(left));
                left--;
            }
        }, 0L, 20L);
    }

    private void preparePlayerForArena(Player p, Location location) {
        p.closeInventory();
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0, 0));
        double max = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
        p.setHealth(max);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.teleport(Objects.requireNonNull(location));
    }

    private void finishWithWinner(DuelSession session, UUID winnerId, UUID loserId) {
        if (session.ended) return;
        session.ended = true;
        if (session.countdownTask != null) session.countdownTask.cancel();
        sessions.remove(session.a);
        sessions.remove(session.b);
        occupiedArenas.remove(session.modeKey);
        cleanupSessionArena(session);

        Player winner = Bukkit.getPlayer(winnerId);
        Player loser = Bukkit.getPlayer(loserId);

        InventorySnapshot winnerSnapshot = session.snapshot(winnerId);
        InventorySnapshot loserSnapshot = session.snapshot(loserId);

        if (winner != null) {
            winnerSnapshot.restore(winner);
            winner.teleport(winnerSnapshot.location);
            msg(winner, "winner", "%winner%", winner.getName(), "%loser%", loser == null ? "Opponent" : loser.getName());
        }
        if (loser != null && !loser.isDead()) {
            loserSnapshot.restore(loser);
            loser.teleport(loserSnapshot.location);
            msg(loser, "winner", "%winner%", winner == null ? "Opponent" : winner.getName(), "%loser%", loser.getName());
        }

        String winnerName = winner != null ? winner.getName() : Bukkit.getOfflinePlayer(winnerId).getName();
        String loserName = loser != null ? loser.getName() : Bukkit.getOfflinePlayer(loserId).getName();
        Bukkit.broadcastMessage(color(prefix() + message("winner")
                .replace("%winner%", winnerName == null ? "Winner" : winnerName)
                .replace("%loser%", loserName == null ? "Loser" : loserName)));

        if (session.wager > 0 && economy != null) {
            double prize = session.wager * 2D;
            economy.depositPlayer(Bukkit.getOfflinePlayer(winnerId), prize);
            Bukkit.broadcastMessage(color(prefix() + message("wager-winner")
                    .replace("%winner%", winnerName == null ? "Winner" : winnerName)
                    .replace("%amount%", formatMoney(prize))));
        }
    }

    private void cancelSession(DuelSession session, boolean refund, String reason) {
        if (session == null || session.ended) return;
        session.ended = true;
        if (session.countdownTask != null) session.countdownTask.cancel();
        sessions.remove(session.a);
        sessions.remove(session.b);
        occupiedArenas.remove(session.modeKey);
        cleanupSessionArena(session);

        for (UUID id : List.of(session.a, session.b)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                InventorySnapshot snap = session.snapshot(id);
                snap.restore(p);
                p.teleport(snap.location);
                msg(p, "duel-cancelled");
            }
        }
        if (refund && session.wager > 0 && economy != null) {
            economy.depositPlayer(Bukkit.getOfflinePlayer(session.a), session.wager);
            economy.depositPlayer(Bukkit.getOfflinePlayer(session.b), session.wager);
        }
        getLogger().info("Duel cancelled: " + reason);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player loser = event.getEntity();
        DuelSession session = sessions.get(loser.getUniqueId());
        if (session == null || session.ended) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);

        UUID winnerId = session.other(loser.getUniqueId());
        pendingRespawns.put(loser.getUniqueId(), new PendingRespawn(session.snapshot(loser.getUniqueId())));
        finishWithWinner(session, winnerId, loser.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        PendingRespawn pending = pendingRespawns.remove(event.getPlayer().getUniqueId());
        if (pending == null) return;
        event.setRespawnLocation(pending.snapshot.location);
        Bukkit.getScheduler().runTask(this, () -> pending.snapshot.restore(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = resolvePlayerDamager(event.getDamager());

        DuelSession victimSession = victim == null ? null : sessions.get(victim.getUniqueId());
        DuelSession attackerSession = attacker == null ? null : sessions.get(attacker.getUniqueId());

        // Crystal PvP: duelists must be able to punch End Crystals so they explode.
        // The old generic duel protection treated the crystal as a non-player victim and
        // cancelled this hit, which made placed crystals impossible to detonate.
        if (event.getEntity() instanceof EnderCrystal && attackerSession != null) {
            if (attackerSession.active && attackerSession.modeKey.equalsIgnoreCase("crystal")) {
                return;
            }
            event.setCancelled(true);
            return;
        }

        // Crystal explosions use the EnderCrystal entity as the damager rather than a
        // Player. Allow that explosion to damage participants in the active Crystal PvP
        // session. Other duel modes keep the normal protection below.
        if (event.getDamager() instanceof EnderCrystal && victimSession != null) {
            if (victimSession.active && victimSession.modeKey.equalsIgnoreCase("crystal")) {
                return;
            }
            event.setCancelled(true);
            return;
        }

        if (victim == null && attacker == null) return;
        if (victimSession == null && attackerSession == null) return;

        // A duel participant can only damage their own opponent after countdown.
        if (victim == null || attacker == null || victimSession == null || victimSession != attackerSession || !victimSession.active) {
            event.setCancelled(true);
            return;
        }
        if (!victimSession.other(victim.getUniqueId()).equals(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private Player resolvePlayerDamager(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        DuelSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.active || session.ended || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // Duel-only combat lock. While a player is in a real duel OR solo test,
        // every command is blocked unless the exact command is explicitly allowed.
        // /duel testend is additionally restricted to admins/OPs.
        if (!isInDuelOrTest(event.getPlayer())) return;

        String raw = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return;

        // Strip a namespace from the root command, e.g. minecraft:duel -> duel.
        String[] parts = raw.split("\\s+");
        String rootCommand = parts[0];
        if (rootCommand.contains(":")) {
            rootCommand = rootCommand.substring(rootCommand.indexOf(':') + 1);
        }
        StringBuilder normalizedBuilder = new StringBuilder(rootCommand);
        for (int i = 1; i < parts.length; i++) {
            normalizedBuilder.append(' ').append(parts[i]);
        }
        String normalized = normalizedBuilder.toString();

        List<String> allowed = getConfig().getStringList("allowed-commands-during-duel").stream()
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""))
                .map(s -> {
                    String[] a = s.split("\\s+");
                    String r = a[0];
                    if (r.contains(":")) r = r.substring(r.indexOf(':') + 1);
                    StringBuilder b = new StringBuilder(r);
                    for (int i = 1; i < a.length; i++) b.append(' ').append(a[i]);
                    return b.toString();
                })
                .collect(Collectors.toList());

        boolean isTestEnd = normalized.equals("duel testend") || normalized.equals("duels testend");
        boolean allowedCommand = allowed.contains(normalized);

        // Only OPs/admins may use /duel testend while locked.
        if (isTestEnd && event.getPlayer().hasPermission("magicduels.admin")) {
            return;
        }

        if (!allowedCommand) {
            event.setCancelled(true);
            msg(event.getPlayer(), "commands-blocked");
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isInDuelOrTest(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        Player player = e.getPlayer();
        if (!isInDuelOrTest(player)) return;

        // Crystal PvP may place obsidian. Track every placed block so it can be
        // removed automatically when the duel/test ends. Every other placement is blocked.
        if (isCrystalMode(player) && e.getBlockPlaced().getType() == Material.OBSIDIAN) {
            Location placed = e.getBlockPlaced().getLocation().clone();
            DuelSession session = sessions.get(player.getUniqueId());
            if (session != null) {
                session.placedBlocks.add(placed);
            } else if (testSnapshots.containsKey(player.getUniqueId())) {
                testPlacedBlocks.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(placed);
            }
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        Player player = event.getPlayer();
        if (player == null || !isInDuelOrTest(player) || !isCrystalMode(player)) return;

        DuelSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.placedCrystals.add(crystal.getUniqueId());
        } else if (testSnapshots.containsKey(player.getUniqueId())) {
            testPlacedCrystals.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(crystal.getUniqueId());
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isInDuelOrTest(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(color(prefix() + "&cYou cannot drop items during a duel."));
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        if (isInDuelOrTest(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;

        if (event.getInventory().getHolder() instanceof LayoutModeMenuHolder holder) {
            event.setCancelled(true);
            if (!holder.target.equals(p.getUniqueId())) return;
            DuelMode mode = getDuelModeBySlot(event.getRawSlot());
            if (mode == null) return;
            p.closeInventory();
            Bukkit.getScheduler().runTask(this, () -> openLayoutEditor(p, mode));
            return;
        }

        if (event.getInventory().getHolder() instanceof LayoutEditorHolder holder) {
            if (!holder.target.equals(p.getUniqueId())) { event.setCancelled(true); return; }
            int raw = event.getRawSlot();
            if (raw < 0) { event.setCancelled(true); return; }
            if (raw >= event.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
            if (raw >= 36) {
                event.setCancelled(true);
                if (raw == 49) {
                    if (saveLayoutFromEditor(p, holder.modeKey, event.getInventory())) {
                        p.closeInventory();
                        p.sendMessage(color(prefix() + "&aSaved your &e" + readableMode(holder.modeKey) + " &alayout."));
                    }
                } else if (raw == 45) {
                    resetLayout(p, holder.modeKey);
                    p.closeInventory();
                    Bukkit.getScheduler().runTask(this, () -> openLayoutEditor(p, Objects.requireNonNull(getDuelMode(holder.modeKey))));
                } else if (raw == 53) {
                    p.closeInventory();
                }
                return;
            }
            // Block click types that could move/clone/drop items into the real
            // player inventory or outside the GUI. Normal left/right clicks inside
            // slots 0-35 remain allowed for rearranging the temporary kit.
            ClickType click = event.getClick();
            if (event.isShiftClick() || click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND
                    || click == ClickType.DROP || click == ClickType.CONTROL_DROP
                    || click == ClickType.DOUBLE_CLICK || click == ClickType.MIDDLE) {
                event.setCancelled(true);
                return;
            }
            // Slots 0-35 are the editable duel inventory. Players may rearrange
            // these kit items, but the lower player inventory is completely blocked.
            return;
        }

        if (event.getInventory().getHolder() instanceof GamemodeMenuHolder holder) {
            event.setCancelled(true);
            if (!holder.target.equals(p.getUniqueId())) return;

            if (holder.testMode) {
                DuelMode mode = getDuelModeBySlot(event.getRawSlot());
                if (mode == null) return;
                p.closeInventory();
                startTest(p, mode);
                return;
            }

            DuelRequest request = incomingRequests.get(p.getUniqueId());
            if (request == null || request.expired()) {
                incomingRequests.remove(p.getUniqueId());
                p.closeInventory();
                msg(p, "request-expired");
                return;
            }

            DuelMode mode = getDuelModeBySlot(event.getRawSlot());
            if (mode == null) return;

            Player challenger = Bukkit.getPlayer(request.challenger);
            if (challenger == null) {
                incomingRequests.remove(p.getUniqueId());
                p.closeInventory();
                msg(p, "challenger-offline");
                return;
            }

            incomingRequests.remove(p.getUniqueId());
            p.closeInventory();
            msg(p, "gamemode-selected", "%gamemode%", color(mode.displayName));
            msg(challenger, "gamemode-selected-other", "%player%", p.getName(), "%gamemode%", color(mode.displayName));
            startDuel(challenger, p, request.wager, mode);
            return;
        }

        if (sessions.containsKey(p.getUniqueId()) && event.getClickedInventory() != p.getInventory()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (event.getInventory().getHolder() instanceof LayoutEditorHolder) {
            // Any cursor item came from the temporary layout GUI, so clear it to
            // prevent players from taking kit items into survival inventory.
            if (p.getItemOnCursor() != null && p.getItemOnCursor().getType() != Material.AIR) {
                p.setItemOnCursor(new ItemStack(Material.AIR));
            }
            return;
        }
        if (!(event.getInventory().getHolder() instanceof GamemodeMenuHolder)) return;
        if (incomingRequests.containsKey(p.getUniqueId())) {
            msg(p, "gamemode-not-selected");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!(event.getInventory().getHolder() instanceof LayoutEditorHolder holder)) return;
        if (!holder.target.equals(p.getUniqueId())) { event.setCancelled(true); return; }
        for (int raw : event.getRawSlots()) {
            if (raw >= 36) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInDuelOrTest(player)) return;

        // Never allow duelists to use containers in the arena.
        if (event.getClickedBlock() != null && event.getClickedBlock().getState() instanceof InventoryHolder) {
            event.setCancelled(true);
            return;
        }

        // End crystals are only usable in the Crystal PvP mode. In Crystal mode,
        // vanilla handles normal crystal placement on obsidian/bedrock.
        if (player.getInventory().getItemInMainHand().getType() == Material.END_CRYSTAL
                || player.getInventory().getItemInOffHand().getType() == Material.END_CRYSTAL) {
            if (!isCrystalMode(player)) event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();
        incomingRequests.remove(quitter.getUniqueId());
        incomingRequests.entrySet().removeIf(e -> e.getValue().challenger.equals(quitter.getUniqueId()));

        if (testSnapshots.containsKey(quitter.getUniqueId())) {
            testSnapshots.remove(quitter.getUniqueId());
            String testMode = testModes.remove(quitter.getUniqueId());
            if (testMode != null) occupiedArenas.remove(testMode);
            cleanupTestArena(quitter.getUniqueId());
        }

        DuelSession session = sessions.get(quitter.getUniqueId());
        if (session == null || session.ended) return;
        UUID other = session.other(quitter.getUniqueId());
        if (session.active) {
            // Leaving an active fight is a forfeit.
            finishWithWinner(session, other, quitter.getUniqueId());
        } else {
            cancelSession(session, true, "Player left during countdown");
        }
    }

    private void openLayoutModeGui(Player player) {
        List<DuelMode> modes = getDuelModes();
        if (modes.isEmpty()) { msg(player, "no-gamemodes"); return; }
        int size = getConfig().getInt("gamemode-gui.size", 27);
        size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
        LayoutModeMenuHolder holder = new LayoutModeMenuHolder(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, color("&8Choose Kit Layout"));
        holder.inventory = inv;
        Material fillerMaterial = Material.matchMaterial(getConfig().getString("gamemode-gui.filler", "GRAY_STAINED_GLASS_PANE"));
        if (fillerMaterial != null && fillerMaterial != Material.AIR) {
            ItemStack filler = namedItem(fillerMaterial, " ", Collections.emptyList());
            for (int i = 0; i < size; i++) inv.setItem(i, filler);
        }
        for (DuelMode mode : modes) {
            if (mode.slot < 0 || mode.slot >= size) continue;
            List<String> lore = new ArrayList<>();
            for (String line : mode.lore) lore.add(color(line));
            lore.add("");
            lore.add(color("&eClick to organize this kit"));
            inv.setItem(mode.slot, namedItem(mode.icon, color(mode.displayName), lore));
        }
        player.openInventory(inv);
    }

    private void openLayoutEditor(Player player, DuelMode mode) {
        if (player.getItemOnCursor() != null && player.getItemOnCursor().getType() != Material.AIR) {
            player.sendMessage(color(prefix() + "&cPut the item on your cursor away before editing a duel layout."));
            return;
        }
        LayoutEditorHolder holder = new LayoutEditorHolder(player.getUniqueId(), mode.key);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&8Layout: " + ChatColor.stripColor(color(mode.displayName))));
        holder.inventory = inv;

        ItemStack[] layout = getLayoutForMode(player, mode);
        for (int i = 0; i < 36; i++) inv.setItem(i, cloneItem(layout[i]));

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 36; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(45, namedItem(Material.REDSTONE, color("&cReset to Default"), List.of(color("&7Restore the default slots for this kit."))));
        inv.setItem(49, namedItem(Material.LIME_DYE, color("&a&lSave Layout"), List.of(color("&7Save these item positions for future duels."))));
        inv.setItem(53, namedItem(Material.BARRIER, color("&cClose"), List.of(color("&7Close without saving changes."))));
        player.openInventory(inv);
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean saveLayoutFromEditor(Player player, String modeKey, Inventory inv) {
        DuelMode mode = getDuelMode(modeKey);
        if (mode == null) return false;
        ItemStack[] candidate = new ItemStack[36];
        for (int i = 0; i < 36; i++) candidate[i] = cloneItem(inv.getItem(i));
        ItemStack[] expected = getDefaultStorageKit(mode);
        if (!sameKitContents(candidate, expected)) {
            player.sendMessage(color(prefix() + "&cThat layout is missing or has extra kit items. Put every kit item somewhere in the top 36 slots, then save again."));
            return false;
        }
        String path = "players." + player.getUniqueId() + "." + mode.key + ".inventory";
        List<String> serialized = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = candidate[i];
            if (item == null || item.getType() == Material.AIR) continue;
            serialized.add(i + ":" + item.getType().name() + ":" + item.getAmount());
        }
        layoutsConfig.set(path, serialized);
        saveLayouts();
        return true;
    }

    private void resetLayout(Player player, String modeKey) {
        if (layoutsConfig == null) return;
        layoutsConfig.set("players." + player.getUniqueId() + "." + modeKey, null);
        saveLayouts();
        player.sendMessage(color(prefix() + "&aReset your &e" + readableMode(modeKey) + " &alayout to default."));
    }

    private String readableMode(String modeKey) {
        DuelMode mode = getDuelMode(modeKey);
        return mode == null ? modeKey : ChatColor.stripColor(color(mode.displayName));
    }

    private ItemStack[] getLayoutForMode(Player player, DuelMode mode) {
        ItemStack[] defaults = getDefaultStorageKit(mode);
        if (layoutsConfig == null) return defaults;
        List<String> saved = layoutsConfig.getStringList("players." + player.getUniqueId() + "." + mode.key + ".inventory");
        if (saved.isEmpty()) return defaults;
        ItemStack[] candidate = parseStorageEntries(saved);
        if (!sameKitContents(candidate, defaults)) return defaults;
        return candidate;
    }

    private ItemStack[] getDefaultStorageKit(DuelMode mode) {
        return parseStorageEntries(getConfig().getStringList("gamemodes." + mode.key + ".kit.inventory"));
    }

    private ItemStack[] parseStorageEntries(List<String> entries) {
        ItemStack[] result = new ItemStack[36];
        for (String entry : entries) {
            String[] parts = entry.split(":", 3);
            if (parts.length < 2) continue;
            try {
                int slot = Integer.parseInt(parts[0].trim());
                Material material = Material.matchMaterial(parts[1].trim());
                int amount = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 1;
                if (slot < 0 || slot >= 36 || material == null || material == Material.AIR) continue;
                amount = Math.max(1, Math.min(amount, material.getMaxStackSize()));
                result[slot] = new ItemStack(material, amount);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private boolean sameKitContents(ItemStack[] a, ItemStack[] b) {
        return kitCounts(a).equals(kitCounts(b));
    }

    private Map<String, Integer> kitCounts(ItemStack[] items) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null || item.getType() == Material.AIR ? null : item.clone();
    }

    private void openGamemodeGui(Player target, DuelRequest request) {
        List<DuelMode> modes = getDuelModes();
        if (modes.isEmpty()) {
            msg(target, "no-gamemodes");
            return;
        }

        int size = getConfig().getInt("gamemode-gui.size", 27);
        size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
        String title = color(getConfig().getString("gamemode-gui.title", "&8Choose Duel Gamemode"));
        GamemodeMenuHolder holder = new GamemodeMenuHolder(target.getUniqueId(), false);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.inventory = inv;

        Material fillerMaterial = Material.matchMaterial(getConfig().getString("gamemode-gui.filler", "GRAY_STAINED_GLASS_PANE"));
        if (fillerMaterial != null && fillerMaterial != Material.AIR) {
            ItemStack filler = new ItemStack(fillerMaterial);
            var meta = filler.getItemMeta();
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
            for (int i = 0; i < size; i++) inv.setItem(i, filler);
        }

        for (DuelMode mode : modes) {
            if (mode.slot < 0 || mode.slot >= size) continue;
            ItemStack icon = new ItemStack(mode.icon);
            var meta = icon.getItemMeta();
            meta.setDisplayName(color(mode.displayName));
            List<String> lore = new ArrayList<>();
            for (String line : mode.lore) lore.add(color(line));
            if (request.wager > 0) {
                lore.add("");
                lore.add(color("&6Wager: &a$" + formatMoney(request.wager)));
                lore.add(color("&6Winner receives: &a$" + formatMoney(request.wager * 2D)));
            }
            lore.add("");
            lore.add(color("&eClick to select this gamemode"));
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.setItem(mode.slot, icon);
        }

        target.openInventory(inv);
        msg(target, "choose-gamemode");
    }

    private void openTestGamemodeGui(Player player) {
        List<DuelMode> modes = getDuelModes();
        if (modes.isEmpty()) { msg(player, "no-gamemodes"); return; }
        int size = getConfig().getInt("gamemode-gui.size", 27);
        size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
        GamemodeMenuHolder holder = new GamemodeMenuHolder(player.getUniqueId(), true);
        Inventory inv = Bukkit.createInventory(holder, size, color("&8Test Duel Gamemode"));
        holder.inventory = inv;
        for (DuelMode mode : modes) {
            if (mode.slot < 0 || mode.slot >= size) continue;
            ItemStack icon = new ItemStack(mode.icon);
            var meta = icon.getItemMeta();
            meta.setDisplayName(color(mode.displayName));
            List<String> lore = new ArrayList<>();
            for (String line : mode.lore) lore.add(color(line));
            lore.add(""); lore.add(color("&eClick to test this kit"));
            meta.setLore(lore); icon.setItemMeta(meta); inv.setItem(mode.slot, icon);
        }
        player.openInventory(inv);
        player.sendMessage(color(prefix() + "&7Choose a gamemode to test by yourself."));
    }

    private void startTest(Player player, DuelMode mode) {
        if (testSnapshots.containsKey(player.getUniqueId())) return;
        if (!arenaReady(mode.key)) {
            msg(player, "arena-not-set", "%gamemode%", color(mode.displayName));
            return;
        }
        if (occupiedArenas.contains(mode.key)) {
            msg(player, "arena-full", "%gamemode%", color(mode.displayName));
            return;
        }
        occupiedArenas.add(mode.key);
        testSnapshots.put(player.getUniqueId(), InventorySnapshot.capture(player));
        testModes.put(player.getUniqueId(), mode.key);
        testPlacedBlocks.put(player.getUniqueId(), new HashSet<>());
        testPlacedCrystals.put(player.getUniqueId(), new HashSet<>());
        applyKit(player, mode);
        preparePlayerForArena(player, arenaSpawn(mode.key, 1));
        player.sendMessage(color(prefix() + "&aTest started: " + mode.displayName));
        int countdown = Math.max(1, getConfig().getInt("countdown-seconds", 3));
        for (int i = 0; i < countdown; i++) {
            final int left = countdown - i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (testSnapshots.containsKey(player.getUniqueId()) && player.isOnline())
                    player.sendMessage(color(prefix() + "&e" + left + "..."));
            }, i * 20L);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (testSnapshots.containsKey(player.getUniqueId()) && player.isOnline())
                player.sendMessage(color(prefix() + "&a&lFIGHT! &7(Test mode — use &e/duel testend&7 when finished)"));
        }, countdown * 20L);
    }

    private void endTest(Player player) {
        InventorySnapshot snapshot = testSnapshots.remove(player.getUniqueId());
        String endedMode = testModes.remove(player.getUniqueId());
        if (endedMode != null) occupiedArenas.remove(endedMode);
        cleanupTestArena(player.getUniqueId());
        if (snapshot == null) {
            player.sendMessage(color(prefix() + "&cYou are not currently in duel test mode."));
            return;
        }
        snapshot.restore(player);
        player.teleport(snapshot.location);
        player.sendMessage(color(prefix() + "&aDuel test ended. Your inventory and location were restored."));
    }


    private void cleanupSessionArena(DuelSession session) {
        if (session == null) return;
        cleanupPlacedBlocks(session.placedBlocks);
        cleanupPlacedCrystals(session.placedCrystals);
        session.placedBlocks.clear();
        session.placedCrystals.clear();
    }

    private void cleanupTestArena(UUID playerId) {
        cleanupPlacedBlocks(testPlacedBlocks.remove(playerId));
        cleanupPlacedCrystals(testPlacedCrystals.remove(playerId));
    }

    private void cleanupPlacedBlocks(Collection<Location> blocks) {
        if (blocks == null) return;
        for (Location location : blocks) {
            if (location == null || location.getWorld() == null) continue;
            if (location.getBlock().getType() == Material.OBSIDIAN) {
                location.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void cleanupPlacedCrystals(Collection<UUID> crystals) {
        if (crystals == null) return;
        for (UUID entityId : crystals) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity instanceof EnderCrystal && entity.isValid()) {
                entity.remove();
            }
        }
    }


    private boolean isInDuelOrTest(Player player) {
        UUID id = player.getUniqueId();
        return sessions.containsKey(id) || testSnapshots.containsKey(id);
    }

    private boolean isCrystalMode(Player player) {
        UUID id = player.getUniqueId();
        DuelSession session = sessions.get(id);
        if (session != null) return session.modeKey.equalsIgnoreCase("crystal");
        String testMode = testModes.get(id);
        return testMode != null && testMode.equalsIgnoreCase("crystal");
    }

    private List<DuelMode> getDuelModes() {
        var section = getConfig().getConfigurationSection("gamemodes");
        if (section == null) return Collections.emptyList();
        List<DuelMode> modes = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String base = "gamemodes." + key + ".";
            if (!getConfig().getBoolean(base + "enabled", true)) continue;
            Material icon = Material.matchMaterial(getConfig().getString(base + "icon", "DIAMOND_SWORD"));
            if (icon == null || icon == Material.AIR) icon = Material.DIAMOND_SWORD;
            modes.add(new DuelMode(
                    key,
                    getConfig().getString(base + "name", key),
                    getConfig().getInt(base + "slot", 13),
                    icon,
                    getConfig().getStringList(base + "lore")
            ));
        }
        return modes;
    }

    private DuelMode getDuelMode(String key) {
        for (DuelMode mode : getDuelModes()) if (mode.key.equalsIgnoreCase(key)) return mode;
        return null;
    }

    private DuelMode getDuelModeBySlot(int slot) {
        for (DuelMode mode : getDuelModes()) if (mode.slot == slot) return mode;
        return null;
    }

    private void applyKit(Player player, DuelMode mode) {
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));

        String base = "gamemodes." + mode.key + ".kit.";
        setArmorPiece(player, "helmet", getConfig().getString(base + "helmet"));
        setArmorPiece(player, "chestplate", getConfig().getString(base + "chestplate"));
        setArmorPiece(player, "leggings", getConfig().getString(base + "leggings"));
        setArmorPiece(player, "boots", getConfig().getString(base + "boots"));
        String offhandValue = getConfig().getString(base + "offhand");
        ItemStack offhand = parseKitItem(offhandValue);
        player.getInventory().setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);

        ItemStack[] layout = getLayoutForMode(player, mode);
        for (int slot = 0; slot < layout.length; slot++) {
            if (layout[slot] != null) player.getInventory().setItem(slot, layout[slot].clone());
        }
        player.updateInventory();
    }

    private void setArmorPiece(Player player, String piece, String value) {
        ItemStack item = parseKitItem(value);
        if (item == null) return;
        switch (piece) {
            case "helmet" -> player.getInventory().setHelmet(item);
            case "chestplate" -> player.getInventory().setChestplate(item);
            case "leggings" -> player.getInventory().setLeggings(item);
            case "boots" -> player.getInventory().setBoots(item);
        }
    }

    private ItemStack parseKitItem(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("AIR") || value.equalsIgnoreCase("NONE")) return null;
        String[] parts = value.split(":", 2);
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null || material == Material.AIR) return null;
        int amount = 1;
        if (parts.length == 2) {
            try { amount = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) { }
        }
        amount = Math.max(1, Math.min(amount, material.getMaxStackSize()));
        return new ItemStack(material, amount);
    }

    private Location arenaSpawn(String modeKey, int number) {
        return getConfig().getLocation("arena-spawns." + modeKey + ".spawn" + number);
    }

    private boolean arenaReady(String modeKey) {
        Location one = arenaSpawn(modeKey, 1);
        Location two = arenaSpawn(modeKey, 2);
        return one != null && two != null && one.getWorld() != null && two.getWorld() != null;
    }

    private void cleanupExpiredRequests() {
        incomingRequests.entrySet().removeIf(e -> e.getValue().expired());
    }

    private void sendHelp(Player p) {
        p.sendMessage(color("&6&lMagicDuels"));
        p.sendMessage(color("&e/duel <player> &7- Challenge a player"));
        p.sendMessage(color("&e/duel <player> <amount> &7- Challenge with a wager"));
        p.sendMessage(color("&e/duel accept &7- Accept your current request"));
        p.sendMessage(color("&e/duel deny &7- Decline your current request"));
        p.sendMessage(color("&e/duel status"));
        p.sendMessage(color("&e/duel layout &7- Organize your duel kit inventories"));
        if (p.hasPermission("magicduels.admin")) {
            p.sendMessage(color("&c/duel setspawn <gamemode> <1|2>"));
            p.sendMessage(color("&c/duel reload"));
            p.sendMessage(color("&c/duel test &7- Test a kit by yourself"));
            p.sendMessage(color("&c/duel testend &7- End your test and restore everything"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("accept", "deny", "cancel", "status", "layout"));
            if (sender.hasPermission("magicduels.admin")) values.addAll(List.of("setspawn", "reload", "test", "testend"));
            for (Player p : Bukkit.getOnlinePlayers()) values.add(p.getName());
            String start = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(start)).distinct().sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setspawn") && sender.hasPermission("magicduels.admin")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            return getDuelModes().stream().map(m -> m.key).filter(k -> k.startsWith(start)).sorted().toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setspawn") && sender.hasPermission("magicduels.admin")) {
            return List.of("1", "2").stream().filter(v -> v.startsWith(args[2])).toList();
        }
        return Collections.emptyList();
    }

    private void loadLayouts() {
        layoutsFile = new File(getDataFolder(), "layouts.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        layoutsConfig = YamlConfiguration.loadConfiguration(layoutsFile);
    }

    private void saveLayouts() {
        if (layoutsConfig == null || layoutsFile == null) return;
        try {
            layoutsConfig.save(layoutsFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save layouts.yml: " + ex.getMessage());
        }
    }

    private String prefix() { return getConfig().getString("messages.prefix", "&6&lMagicSMP &8» &r"); }
    private String message(String key) { return getConfig().getString("messages." + key, "&cMissing message: " + key); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void msg(CommandSender sender, String key, String... replacements) {
        String text = message(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(color(prefix() + text));
    }

    private double parseMoney(String input) {
        if (input == null) return Double.NaN;
        String value = input.trim().toLowerCase(Locale.ROOT).replace(",", "");
        if (value.isEmpty()) return Double.NaN;

        double multiplier = 1D;
        char last = value.charAt(value.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            multiplier = switch (last) {
                case 'k' -> 1_000D;
                case 'm' -> 1_000_000D;
                case 'b' -> 1_000_000_000D;
                default -> 1D;
            };
            value = value.substring(0, value.length() - 1);
        }

        try {
            double amount = Double.parseDouble(value) * multiplier;
            if (!Double.isFinite(amount) || amount < 0) return Double.NaN;
            return Math.floor(amount * 100D) / 100D;
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private String formatMoney(double amount) {
        if (amount == Math.rint(amount)) return String.format(Locale.US, "%,.0f", amount);
        return String.format(Locale.US, "%,.2f", amount);
    }

    private static final class DuelMode {
        private final String key;
        private final String displayName;
        private final int slot;
        private final Material icon;
        private final List<String> lore;
        private DuelMode(String key, String displayName, int slot, Material icon, List<String> lore) {
            this.key = key;
            this.displayName = displayName;
            this.slot = slot;
            this.icon = icon;
            this.lore = lore == null ? Collections.emptyList() : lore;
        }
    }

    private static final class GamemodeMenuHolder implements InventoryHolder {
        private final UUID target;
        private final boolean testMode;
        private Inventory inventory;
        private GamemodeMenuHolder(UUID target, boolean testMode) { this.target = target; this.testMode = testMode; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class LayoutModeMenuHolder implements InventoryHolder {
        private final UUID target;
        private Inventory inventory;
        private LayoutModeMenuHolder(UUID target) { this.target = target; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class LayoutEditorHolder implements InventoryHolder {
        private final UUID target;
        private final String modeKey;
        private Inventory inventory;
        private LayoutEditorHolder(UUID target, String modeKey) { this.target = target; this.modeKey = modeKey; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class DuelRequest {
        private final UUID challenger;
        private final UUID target;
        private final double wager;
        private final long expiresAt;
        private DuelRequest(UUID challenger, UUID target, double wager, long expiresAt) {
            this.challenger = challenger;
            this.target = target;
            this.wager = wager;
            this.expiresAt = expiresAt;
        }
        private boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final class DuelSession {
        private final UUID a;
        private final UUID b;
        private final InventorySnapshot aSnapshot;
        private final InventorySnapshot bSnapshot;
        private final double wager;
        private final String modeKey;
        private final Set<Location> placedBlocks = new HashSet<>();
        private final Set<UUID> placedCrystals = new HashSet<>();
        private boolean active = false;
        private boolean ended = false;
        private BukkitTask countdownTask;
        private DuelSession(Player a, Player b, double wager, String modeKey) {
            this.a = a.getUniqueId();
            this.b = b.getUniqueId();
            this.aSnapshot = InventorySnapshot.capture(a);
            this.bSnapshot = InventorySnapshot.capture(b);
            this.wager = wager;
            this.modeKey = modeKey;
        }
        private UUID other(UUID id) { return id.equals(a) ? b : a; }
        private InventorySnapshot snapshot(UUID id) { return id.equals(a) ? aSnapshot : bSnapshot; }
    }

    private static final class PendingRespawn {
        private final InventorySnapshot snapshot;
        private PendingRespawn(InventorySnapshot snapshot) { this.snapshot = snapshot; }
    }

    private static final class InventorySnapshot {
        private final ItemStack[] storage;
        private final ItemStack[] armor;
        private final ItemStack offhand;
        private final int level;
        private final float exp;
        private final int totalExp;
        private final int food;
        private final float saturation;
        private final double health;
        private final int fireTicks;
        private final GameMode gameMode;
        private final Location location;

        private InventorySnapshot(Player p) {
            this.storage = cloneItems(p.getInventory().getStorageContents());
            this.armor = cloneItems(p.getInventory().getArmorContents());
            this.offhand = cloneItem(p.getInventory().getItemInOffHand());
            this.level = p.getLevel();
            this.exp = p.getExp();
            this.totalExp = p.getTotalExperience();
            this.food = p.getFoodLevel();
            this.saturation = p.getSaturation();
            this.health = p.getHealth();
            this.fireTicks = p.getFireTicks();
            this.gameMode = p.getGameMode();
            this.location = p.getLocation().clone();
        }
        static InventorySnapshot capture(Player p) { return new InventorySnapshot(p); }
        void restore(Player p) {
            p.getInventory().setStorageContents(cloneItems(storage));
            p.getInventory().setArmorContents(cloneItems(armor));
            p.getInventory().setItemInOffHand(cloneItem(offhand));
            p.setLevel(level);
            p.setExp(exp);
            p.setTotalExperience(totalExp);
            p.setFoodLevel(food);
            p.setSaturation(saturation);
            p.setFireTicks(fireTicks);
            p.setGameMode(gameMode);
            double max = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
            p.setHealth(Math.max(0.5D, Math.min(health, max)));
            p.setFallDistance(0);
            p.setVelocity(new Vector(0, 0, 0));
            p.updateInventory();
        }
        private static ItemStack[] cloneItems(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) copy[i] = cloneItem(source[i]);
            return copy;
        }
        private static ItemStack cloneItem(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) return null;
            return item.clone();
        }
    }
}
