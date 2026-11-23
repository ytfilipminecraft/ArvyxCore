package sk.arvyx.core.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sk.arvyx.core.CoreModule;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.game.chaos.ChaosEventManager;
import sk.arvyx.core.game.chaos.ChaosModule;
import sk.arvyx.core.matchmaking.MatchmakingManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.network.ServerInfo;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.reset.ResetManager;
import sk.arvyx.core.util.MessageFormatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Hlavný správca príkazu /arvyx vrátane matchmakingu (/arvyx join, /arvyx leave) a administrácie.
 */
public final class ArvyxCommand implements CommandExecutor, TabCompleter {

    private final CoreModule coreModule;
    private final ConfigManager configManager;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final MatchmakingManager matchmakingManager;
    private final GameManager gameManager;
    private final CountdownManager countdownManager;
    private final ResetManager resetManager;
    private final NetworkManager networkManager;
    private final MessageFormatter formatter;

    public ArvyxCommand(
            CoreModule coreModule,
            ConfigManager configManager,
            ArenaManager arenaManager,
            PlayerManager playerManager,
            MatchmakingManager matchmakingManager,
            GameManager gameManager,
            CountdownManager countdownManager,
            ResetManager resetManager,
            NetworkManager networkManager,
            MessageFormatter formatter
    ) {
        this.coreModule = coreModule;
        this.configManager = configManager;
        this.arenaManager = arenaManager;
        this.playerManager = playerManager;
        this.matchmakingManager = matchmakingManager;
        this.gameManager = gameManager;
        this.countdownManager = countdownManager;
        this.resetManager = resetManager;
        this.networkManager = networkManager;
        this.formatter = formatter;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (!hasPermission(sender, "arvyx.admin")) {
                formatter.send(sender, configManager.getMessage("general.no-permission"));
                return true;
            }
            sendInfo(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> handleJoin(sender, args);
            case "leave" -> handleLeave(sender);
            case "reload" -> handleReload(sender);
            case "arenas" -> handleArenas(sender);
            case "arena" -> handleArenaDetails(sender, args);
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender, args);
            case "reset" -> handleReset(sender, args);
            case "event" -> handleEvent(sender, args);
            default -> formatter.send(sender, configManager.getMessage("general.unknown-subcommand"));
        }
        return true;
    }

    private void handleJoin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "arvyx.join")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            formatter.send(sender, configManager.getMessage("general.player-only"));
            return;
        }
        if (args.length < 2) {
            formatter.send(sender, configManager.getMessage("matchmaking.join-usage"));
            return;
        }
        Optional<ArenaType> typeOpt = ArenaType.fromString(args[1]);
        if (typeOpt.isEmpty()) {
            formatter.send(sender, configManager.getMessage("matchmaking.invalid-game-type"), Map.of("game", args[1]));
            return;
        }
        matchmakingManager.joinPlayer(player, typeOpt.get());
    }

    private void handleLeave(CommandSender sender) {
        if (!hasPermission(sender, "arvyx.leave")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            formatter.send(sender, configManager.getMessage("general.player-only"));
            return;
        }
        matchmakingManager.leavePlayer(player);
    }

    private void sendInfo(CommandSender sender) {
        long onlineServers = networkManager.getRegisteredServers().stream().filter(ServerInfo::online).count();
        long remoteArenas = networkManager.getArenaSnapshots().stream()
                .filter(s -> !s.serverId().equalsIgnoreCase(configManager.getServerId()))
                .count();

        formatter.send(sender, configManager.getMessage("command.info-header"));
        formatter.send(sender, configManager.getMessage("command.info-title"), Map.of(
                "version", coreModule.getPluginVersion()
        ));
        formatter.send(sender, configManager.getMessage("command.info-details"), Map.of(
                "server_id", configManager.getServerId(),
                "server_role", configManager.getServerRole(),
                "arena_count", String.valueOf(arenaManager.getArenaCount()),
                "player_count", String.valueOf(playerManager.getTrackedPlayerCount())
        ));
        formatter.send(sender, configManager.getMessage("command.info-network"), Map.of(
                "transport_mode", networkManager.getTransportMode().getDescription(),
                "online_servers", String.valueOf(onlineServers),
                "remote_arenas", String.valueOf(remoteArenas)
        ));
        formatter.send(sender, configManager.getMessage("command.info-commands"));
        formatter.send(sender, configManager.getMessage("command.info-header"));
    }

    private void handleReload(CommandSender sender) {
        if (!hasPermission(sender, "arvyx.reload")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        try {
            coreModule.reloadConfiguration();
            formatter.send(sender, configManager.getMessage("general.reload-success"));
        } catch (Exception ex) {
            formatter.send(sender, configManager.getMessage("general.reload-failed"));
        }
    }

    private void handleArenas(CommandSender sender) {
        if (!hasPermission(sender, "arvyx.arena")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        Collection<Arena> arenas = arenaManager.getAllArenas();
        if (arenas.isEmpty()) {
            formatter.send(sender, configManager.getMessage("command.arenas-empty"));
            return;
        }
        formatter.send(sender, configManager.getMessage("command.arenas-header"), Map.of(
                "count", String.valueOf(arenas.size())
        ));
        for (Arena arena : arenas) {
            formatter.send(sender, configManager.getMessage("command.arenas-entry"), Map.of(
                    "id", arena.getId(),
                    "type", arena.getType().getDisplayName(),
                    "state", arena.getState().name(),
                    "state_color", arena.getState().getColorTag(),
                    "players", String.valueOf(arena.getCurrentPlayerCount()),
                    "max", String.valueOf(arena.getMaxPlayers())
            ));
        }
    }

    private void handleArenaDetails(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "arvyx.arena")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (args.length < 2) {
            formatter.send(sender, configManager.getMessage("general.unknown-subcommand"));
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.getArena(args[1]);
        if (arenaOpt.isEmpty()) {
            formatter.send(sender, configManager.getMessage("command.arena-not-found"), Map.of("id", args[1]));
            return;
        }
        Arena arena = arenaOpt.get();
        formatter.send(sender, configManager.getMessage("command.arena-details"), Map.of(
                "id", arena.getId(),
                "name", arena.getName(),
                "type", arena.getType().getDisplayName(),
                "state", arena.getState().name(),
                "state_color", arena.getState().getColorTag(),
                "players", String.valueOf(arena.getCurrentPlayerCount()),
                "max", String.valueOf(arena.getMaxPlayers()),
                "min", String.valueOf(arena.getMinPlayers())
        ));
    }

    private void handleStart(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "arvyx.start")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (args.length < 2) {
            formatter.send(sender, configManager.getMessage("general.unknown-subcommand"));
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.getArena(args[1]);
        if (arenaOpt.isEmpty()) {
            formatter.send(sender, configManager.getMessage("command.arena-not-found"), Map.of("id", args[1]));
            return;
        }
        Arena arena = arenaOpt.get();
        ArenaState state = arena.getState();

        if (state == ArenaState.WAITING) {
            if (!arena.hasMinimumPlayers()) {
                formatter.send(sender, configManager.getMessage("command.arena-start-failed"), Map.of(
                        "id", arena.getId(),
                        "reason", "Nedostatok hráčov (" + arena.getCurrentPlayerCount() + "/" + arena.getMinPlayers() + ")"
                ));
                return;
            }
            boolean started = countdownManager.startCountdown(arena, gameManager::startGame);
            if (started) {
                networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
                formatter.send(sender, configManager.getMessage("command.arena-start-success"), Map.of("id", arena.getId()));
            } else {
                formatter.send(sender, configManager.getMessage("command.arena-start-failed"), Map.of(
                        "id", arena.getId(),
                        "reason", "Odpočítavanie už beží alebo nie je možné spustiť arénu."
                ));
            }
            return;
        }

        if (state == ArenaState.STARTING) {
            countdownManager.cancelCountdown(arena.getId(), false);
            if (arena.transitionTo(ArenaState.INGAME)) {
                gameManager.startGame(arena);
                networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
                formatter.send(sender, configManager.getMessage("command.arena-start-success"), Map.of("id", arena.getId()));
                return;
            }
        }

        formatter.send(sender, configManager.getMessage("command.arena-start-failed"), Map.of(
                "id", arena.getId(),
                "reason", "Neplatný stav arény (" + state.name() + ")"
        ));
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "arvyx.stop")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (args.length < 2) {
            formatter.send(sender, configManager.getMessage("general.unknown-subcommand"));
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.getArena(args[1]);
        if (arenaOpt.isEmpty()) {
            formatter.send(sender, configManager.getMessage("command.arena-not-found"), Map.of("id", args[1]));
            return;
        }
        Arena arena = arenaOpt.get();
        ArenaState state = arena.getState();

        if (state == ArenaState.STARTING) {
            countdownManager.cancelCountdown(arena.getId(), true);
            networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
            formatter.send(sender, configManager.getMessage("command.arena-stop-success"), Map.of("id", arena.getId()));
            return;
        }

        if (state == ArenaState.INGAME) {
            gameManager.stopGame(arena);
            arena.transitionTo(ArenaState.ENDING);
            networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
            resetManager.scheduleResetAfterEnding(arena);
            formatter.send(sender, configManager.getMessage("command.arena-stop-success"), Map.of("id", arena.getId()));
            return;
        }

        formatter.send(sender, configManager.getMessage("command.arena-stop-failed"), Map.of(
                "id", arena.getId(),
                "reason", "Aréna nie je aktívna (stav: " + state.name() + ")"
        ));
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "arvyx.reset")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (args.length < 2) {
            formatter.send(sender, configManager.getMessage("general.unknown-subcommand"));
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.getArena(args[1]);
        if (arenaOpt.isEmpty()) {
            formatter.send(sender, configManager.getMessage("command.arena-not-found"), Map.of("id", args[1]));
            return;
        }
        Arena arena = arenaOpt.get();
        if (resetManager.resetArenaNow(arena)) {
            formatter.send(sender, configManager.getMessage("command.arena-reset-success"), Map.of("id", arena.getId()));
        } else {
            formatter.send(sender, configManager.getMessage("command.arena-reset-failed"), Map.of(
                    "id", arena.getId(),
                    "reason", "Reset už prebieha alebo je neplatný prechod zo stavu " + arena.getState().name()
            ));
        }
    }

    private void handleEvent(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "arvyx.event")) {
            formatter.send(sender, configManager.getMessage("general.no-permission"));
            return;
        }
        if (args.length < 3) {
            formatter.send(sender, configManager.getMessage("command.event-usage"));
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.getArena(args[1]);
        if (arenaOpt.isEmpty()) {
            formatter.send(sender, configManager.getMessage("command.arena-not-found"), Map.of("id", args[1]));
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getType() != ArenaType.CHAOS) {
            formatter.send(sender, configManager.getMessage("command.event-failed"), Map.of(
                    "reason", "Aréna '" + arena.getId() + "' nie je typu CHAOS."
            ));
            return;
        }
        if (arena.getState() != ArenaState.INGAME) {
            formatter.send(sender, configManager.getMessage("command.event-failed"), Map.of(
                    "reason", "Aréna '" + arena.getId() + "' momentálne neprebieha (stav: " + arena.getState().name() + ")."
            ));
            return;
        }
        var moduleOpt = gameManager.getModule(ArenaType.CHAOS);
        if (moduleOpt.isEmpty() || !(moduleOpt.get() instanceof ChaosModule chaosModule)) {
            formatter.send(sender, configManager.getMessage("command.event-failed"), Map.of(
                    "reason", "Modul Chaos nie je dostupný."
            ));
            return;
        }
        String eventId = args[2].toUpperCase(Locale.ROOT);
        ChaosEventManager.TriggerResult result = chaosModule.triggerManualEvent(arena, eventId);
        if (result == ChaosEventManager.TriggerResult.ACTIVATED) {
            formatter.send(sender, configManager.getMessage("command.event-triggered"), Map.of(
                    "event", eventId,
                    "arena", arena.getId()
            ));
        } else {
            formatter.send(sender, configManager.getMessage("command.event-failed"), Map.of(
                    "reason", describeTriggerFailure(result)
            ));
        }
    }

    private static String describeTriggerFailure(ChaosEventManager.TriggerResult result) {
        return switch (result) {
            case EVENT_NOT_FOUND -> "Neznáma Chaos udalosť.";
            case EVENT_DISABLED -> "Táto Chaos udalosť je v konfigurácii vypnutá.";
            case ALREADY_ACTIVE -> "Táto udalosť už v aréne práve prebieha.";
            case ON_COOLDOWN -> "Udalosť je momentálne na cooldowne.";
            case MAX_CONCURRENT_REACHED -> "Dosiahnutý maximálny počet súbežných udalostí.";
            default -> "Interná chyba pri aktivácii udalosti.";
        };
    }

    private static boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission("arvyx.admin") || sender.hasPermission(permission);
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            List<String> subs = List.of("join", "leave", "info", "reload", "arenas", "arena", "start", "stop", "reset", "event");
            return filterPrefix(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("join".equals(sub)) {
                return filterPrefix(List.of("skyrush", "chaos"), args[1]);
            }
            if (List.of("arena", "start", "stop", "reset", "event").contains(sub)) {
                List<String> ids = arenaManager.getAllArenas().stream()
                        .filter(a -> !"event".equals(sub) || a.getType() == ArenaType.CHAOS)
                        .map(Arena::getId)
                        .toList();
                return filterPrefix(ids, args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("event")) {
            Optional<ChaosModule> chaosOpt = gameManager.getModule(ArenaType.CHAOS)
                    .filter(ChaosModule.class::isInstance)
                    .map(ChaosModule.class::cast);
            if (chaosOpt.isPresent()) {
                return filterPrefix(chaosOpt.get().getEventManager().getRegisteredEventIds(), args[2]);
            }
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
