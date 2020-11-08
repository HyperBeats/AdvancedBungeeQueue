package fr.customentity.advancedbungeequeue.bungee.manager;

import fr.customentity.advancedbungeequeue.bungee.AdvancedBungeeQueue;
import fr.customentity.advancedbungeequeue.bungee.data.Priority;
import fr.customentity.advancedbungeequeue.bungee.data.QueuedPlayer;
import fr.customentity.advancedbungeequeue.bungee.runnable.ConnectRunnable;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class QueueManager {

    private AdvancedBungeeQueue plugin;

    private ConcurrentHashMap<ServerInfo, List<QueuedPlayer>> queue;
    private ScheduledExecutorService scheduledExecutorService;
    private boolean paused = false;
    private boolean enabled = true;

    public QueueManager(AdvancedBungeeQueue plugin) {
        this.plugin = plugin;
        this.queue = new ConcurrentHashMap<>();
        this.scheduledExecutorService = new ScheduledThreadPoolExecutor(plugin.getConfigFile().getInt("thread-pool-size"));

        this.loadServersQueue();
        for (ServerInfo serverInfo : queue.keySet()) {
            this.scheduledExecutorService.scheduleAtFixedRate(new ConnectRunnable(plugin, serverInfo), this.plugin.getConfigFile().getLong("queue-speed"), this.plugin.getConfigFile().getLong("queue-speed"), TimeUnit.MILLISECONDS);
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (!enabled) queue.clear();
        else loadServersQueue();
        this.enabled = enabled;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void addPlayerInQueue(ProxiedPlayer proxiedPlayer, ServerInfo serverInfo) {
        if (plugin.getConfigFile().getStringList("default-servers").stream().noneMatch(s -> proxiedPlayer.getServer().getInfo().getName().contains(s))) {
            plugin.sendConfigMessage(proxiedPlayer, "commands.join.cannot-join-queue");
            return;
        }
        if (proxiedPlayer.hasPermission(plugin.getConfigFile().getString("permissions.bypass")) || !isEnabled()) {
            proxiedPlayer.connect(serverInfo);
            return;
        }
        List<QueuedPlayer> queuedPlayers = queue.get(serverInfo);
        if (this.plugin.getConfigFile().getBoolean("use-same-queue"))
            queuedPlayers = queue.values().stream().findFirst().get();

        if (queuedPlayers == null) return;

        Optional<QueuedPlayer> queuedPlayerOptional = QueuedPlayer.get(proxiedPlayer);
        if (queuedPlayerOptional.isPresent()) return;
        QueuedPlayer queuedPlayer = new QueuedPlayer(proxiedPlayer, serverInfo, getPriority(proxiedPlayer));
        QueuedPlayer.getQueuedPlayerSet().add(queuedPlayer);


        boolean added = false;
        for (QueuedPlayer player : queuedPlayers) {
            if (player.getPriority().getPriority() < queuedPlayer.getPriority().getPriority()) {
                queuedPlayers.add(queuedPlayers.indexOf(player), queuedPlayer);
                added = true;
                break;
            }
        }
        if (!added) queuedPlayers.add(queuedPlayer);

        if (queuedPlayers.size() == 0) queuedPlayers.add(queuedPlayer);

        plugin.sendConfigMessage(proxiedPlayer, "commands.join.success", "%all%", queuedPlayers.size() + "",
                "%position%", queuedPlayers.indexOf(queuedPlayer) + 1 + "", "%priority%", queuedPlayer.getPriority().getName());
    }

    public void removePlayerFromQueue(QueuedPlayer queuedPlayer) {
        queue.get(queuedPlayer.getTargetServer()).remove(queuedPlayer);
        QueuedPlayer.getQueuedPlayerSet().remove(queuedPlayer);
    }

    public Priority getPriority(ProxiedPlayer proxiedPlayer) {
        for (String str : plugin.getConfigFile().getSection("priorities").getKeys()) {
            if (proxiedPlayer.hasPermission("advancedbungeequeue.priority." + str))
                return new Priority(str, plugin.getConfigFile().getInt("priorities." + str));
        }
        String last = plugin.getConfigFile().getSection("priorities").getKeys().toArray(new String[0])[plugin.getConfigFile().getSection("priorities").getKeys().size() - 1];
        return new Priority(last, plugin.getConfigFile().getInt("priorities." + last, 0));
    }

    public int getPlayerPosition(ProxiedPlayer proxiedPlayer) {
        Optional<QueuedPlayer> queuedPlayer = QueuedPlayer.get(proxiedPlayer);
        if (queuedPlayer.isPresent()) {
            if (queue.get(queuedPlayer.get().getTargetServer()) != null)
                return queue.get(queuedPlayer.get().getTargetServer()).indexOf(queuedPlayer.get()) + 1;
        }
        return -1;
    }

    public void connectNextPlayers(ServerInfo serverInfo) {
        for (int i = 0; i < plugin.getConfigFile().getInt("player-amount"); i++) {
            List<QueuedPlayer> queuedPlayers = queue.get(serverInfo);
            if (i >= queuedPlayers.size()) return;
            QueuedPlayer queuedPlayer = queuedPlayers.get(i);
            if (queuedPlayer == null) continue;
            serverInfo.ping((serverPing, pingError) -> {
                if (serverPing == null) {
                    plugin.sendConfigMessage(queuedPlayer.getProxiedPlayer(), "general.unavailable-server");
                } else {
                    int online = serverPing.getPlayers().getOnline();
                    int max = serverPing.getPlayers().getMax();

                    if (online >= max) {
                        if (queuedPlayers.indexOf(queuedPlayer) == 0) {
                            plugin.sendConfigMessage(queuedPlayer.getProxiedPlayer(), "general.full-server");

                        }
                        return;
                    }
                    queuedPlayer.setConnecting(true);
                    queuedPlayer.getProxiedPlayer().connect(serverInfo, (result, error) -> {
                        if (result) {

                            System.out.println("CONNECTING PLAYER: " + queuedPlayer.getProxiedPlayer().getName() + " REMAIN: " + queue.get(serverInfo).size());
                        } else {
                            queuedPlayer.setConnecting(false);
                            System.out.println("ERROR WHILE CONNECTING PLAYER: " + queuedPlayer.getProxiedPlayer().getName());
                        }
                    }, ServerConnectEvent.Reason.PLUGIN);
                }
            });
        }
    }


    public void loadServersQueue() {
        for (String servers : this.plugin.getConfigFile().getStringList("queued-servers")) {
            ServerInfo serverInfo = this.plugin.getProxy().getServerInfo(servers);
            if (serverInfo != null) {
                queue.putIfAbsent(serverInfo, Collections.synchronizedList(new ArrayList<>()));
                plugin.getLogger().log(Level.INFO, "Queue enabled for server: " + serverInfo.getName());
                if (!this.plugin.getConfigFile().getBoolean("use-same-queue")) break;
            }
        }
    }

    public ConcurrentHashMap<ServerInfo, List<QueuedPlayer>> getQueues() {
        return queue;
    }
}