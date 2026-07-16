package org.legacy.aegis.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class FoliaScheduler {

    private static boolean folia;

    private static final Set<CancellableTask> trackedTasks = ConcurrentHashMap.newKeySet();

    private FoliaScheduler() {}

    @FunctionalInterface
    public interface CancellableTask {
        void cancel();
    }

    public static void init(Plugin plugin) {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
    }

    public static boolean isFolia() {
        return folia;
    }

    // ── Global Region / Entity Scheduler ─────────────────────

    public static void runTask(Plugin plugin, Runnable task) {
        runTask(plugin, task, null);
    }

    public static void runTask(Plugin plugin, Runnable task, @Nullable Entity entity) {
        if (folia) {
            ScheduledTask scheduledTask;
            if (entity != null) {
                scheduledTask = entity.getScheduler().run(plugin, t -> task.run(), () -> {});
            } else {
                scheduledTask = Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
            }
            trackIfPresent(scheduledTask);
            return;
        }

        BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
        trackIfPresent(bukkitTask);
    }

    public static void runTaskLater(Plugin plugin, Runnable task, long delayTicks, @Nullable Entity entity) {
        if (folia) {
            ScheduledTask scheduledTask;
            if (entity != null) {
                scheduledTask = entity.getScheduler().runDelayed(plugin, t -> task.run(), () -> {}, delayTicks);
            } else {
                scheduledTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
            }
            trackIfPresent(scheduledTask);
            return;
        }

        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        trackIfPresent(bukkitTask);
    }

    public static void runTaskTimer(Plugin plugin, Runnable task, long delay, long period, @Nullable Entity entity) {
        runTaskTimerCancellable(plugin, task, delay, period, entity);
    }

    public static CancellableTask runTaskTimerCancellable(Plugin plugin, Runnable task,
                                                          long delay, long period,
                                                          @Nullable Entity entity) {
        CancellableTask handle;

        if (folia) {
            ScheduledTask scheduledTask;
            if (entity != null) {
                scheduledTask = entity.getScheduler().runAtFixedRate(plugin, t -> task.run(), () -> {}, delay, period);
            } else {
                scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delay, period);
            }
            handle = wrap(scheduledTask);
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            handle = wrap(bukkitTask);
        }

        trackedTasks.add(handle);
        return handle;
    }

    // ── Location-based (RegionScheduler) ─────────────────────

    public static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        if (folia) {
            ScheduledTask scheduledTask = Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
            trackIfPresent(scheduledTask);
            return;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
        trackIfPresent(bukkitTask);
    }

    public static void runAtLocationLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        if (folia) {
            ScheduledTask scheduledTask = Bukkit.getRegionScheduler().runDelayed(plugin, location, t -> task.run(), delayTicks);
            trackIfPresent(scheduledTask);
            return;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        trackIfPresent(bukkitTask);
    }

    // ── Async ────────────────────────────────────────────────

    public static void runAsync(Plugin plugin, Runnable task) {
        if (folia) {
            ScheduledTask scheduledTask = Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
            trackIfPresent(scheduledTask);
            return;
        }

        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        trackIfPresent(bukkitTask);
    }

    public static void runAsyncTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (folia) {
            ScheduledTask scheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin,
                    t -> task.run(),
                    delay * 50L,
                    period * 50L,
                    TimeUnit.MILLISECONDS
            );
            trackIfPresent(scheduledTask);
            return;
        }

        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        trackIfPresent(bukkitTask);
    }

    // ── Cancel ───────────────────────────────────────────────

    public static void cancelTask(@Nullable ScheduledTask task) {
        if (task != null) task.cancel();
    }

    public static void cancelAllTasks(Plugin plugin) {
        for (CancellableTask task : trackedTasks) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        }
        trackedTasks.clear();

        if (folia) {
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    // ── Internal tracking ────────────────────────────────────

    private static void trackIfPresent(@Nullable ScheduledTask task) {
        if (task != null) {
            trackedTasks.add(wrap(task));
        }
    }

    private static void trackIfPresent(@Nullable BukkitTask task) {
        if (task != null) {
            trackedTasks.add(wrap(task));
        }
    }

    private static CancellableTask wrap(ScheduledTask task) {
        return task::cancel;
    }

    private static CancellableTask wrap(BukkitTask task) {
        return task::cancel;
    }
}
