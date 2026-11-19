/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Folia implementation of {@link SchedulerAdapter}.
 *
 * <p>Folia replaces Bukkit's single main thread with multiple region threads.
 * Consequently {@code BukkitScheduler} is unavailable, and we must route work
 * through Folia's four distinct schedulers:</p>
 * <ul>
 *   <li>{@code RegionScheduler} (via {@code Bukkit.getRegionScheduler()}) for
 *       block-state mutations at a location/chunk.</li>
 *   <li>{@code EntityScheduler} (via {@code Entity#getScheduler()}) for
 *       entity-related work.</li>
 *   <li>{@code AsyncScheduler} (via {@code Bukkit.getAsyncScheduler()}) for
 *       off-thread IO/compression.</li>
 *   <li>{@code GlobalRegionScheduler} (via {@code Bukkit.getGlobalRegionScheduler()})
 *       for global non-location-bound work.</li>
 * </ul>
 *
 * <h2>Critical: Consumer&lt;ScheduledTask&gt; not Runnable</h2>
 * <p>Unlike Bukkit's {@code BukkitScheduler} which takes plain {@code Runnable},
 * Folia's scheduler methods all take {@code java.util.function.Consumer<ScheduledTask>}
 * as the task callback. The {@code ScheduledTask} argument is passed to the consumer
 * when the task executes, allowing the task to inspect/cancel itself.</p>
 *
 * <p>This adapter wraps every {@code Runnable} into a {@code Consumer} that ignores
 * the {@code ScheduledTask} argument. All Folia classes are accessed reflectively so
 * the jar compiles against the Paper API only. {@link Method} handles are cached on
 * first use to amortise reflection cost.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class FoliaSchedulerAdapter implements SchedulerAdapter {

    private final Plugin plugin;

    // Cached reflective Method handles (resolved lazily, then reused).
    private volatile Method getRegionScheduler;
    private volatile Method getGlobalRegionScheduler;
    private volatile Method getAsyncScheduler;

    // Scheduler method handles — all use Consumer (not Runnable) for callbacks.
    private volatile Method regionRun;
    private volatile Method globalRun;
    private volatile Method globalRunDelayed;
    private volatile Method globalRunAtFixedRate;
    private volatile Method asyncRunNow;
    private volatile Method asyncRunDelayed;
    private volatile Method asyncRunAtFixedRate;

    /** Tracks repeating tasks we own (for shutdown cancellation). One-shots are not retained. */
    private final Set<Object> owned = new CopyOnWriteArraySet<>();

    public FoliaSchedulerAdapter(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Async
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAsync(@NotNull final Runnable task) {
        final Object sched = getAsyncScheduler();
        final Method m = resolve(asyncRunNow, "runNow", sched.getClass(),
                Plugin.class, Consumer.class);
        asyncRunNow = m;
        return invokeAndMaybeTrack(false, sched, m, plugin, toConsumer(task));
    }

    @Override
    public @NotNull TaskHandle runAsyncDelayed(@NotNull final Runnable task, final long delayTicks) {
        final Object sched = getAsyncScheduler();
        final Method m = resolve(asyncRunDelayed, "runDelayed", sched.getClass(),
                Plugin.class, Consumer.class, long.class, TimeUnit.class);
        asyncRunDelayed = m;
        return invokeAndMaybeTrack(false, sched, m, plugin, toConsumer(task),
                SchedulerAdapter.ticksToMillis(delayTicks), TimeUnit.MILLISECONDS);
    }

    @Override
    public @NotNull TaskHandle runAsyncRepeating(@NotNull final Runnable task,
                                                  final long delayTicks, final long periodTicks) {
        final Object sched = getAsyncScheduler();
        final Method m = resolve(asyncRunAtFixedRate, "runAtFixedRate", sched.getClass(),
                Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
        asyncRunAtFixedRate = m;
        // Folia requires initial delay >= 1 tick. Clamp to minimum 50ms (1 tick).
        final long safeDelayMs = Math.max(50L, SchedulerAdapter.ticksToMillis(delayTicks));
        final long safePeriodMs = Math.max(50L, SchedulerAdapter.ticksToMillis(periodTicks));
        return invokeAndMaybeTrack(true, sched, m, plugin, toConsumer(task), safeDelayMs, safePeriodMs, TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------------
    // Region (at location)
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task) {
        return runAtLocation(location, task, null);
    }

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location,
                                             @NotNull final Runnable task,
                                             @Nullable final Runnable onScheduleFailure) {
        return runAtLocationRetry(location, task, 1, onScheduleFailure);
    }

    private @NotNull TaskHandle runAtLocationRetry(@NotNull final Location location,
                                                   @NotNull final Runnable task,
                                                   final int retriesLeft,
                                                   @Nullable final Runnable onScheduleFailure) {
        final Object sched = getRegionScheduler();
        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Method m = resolve(regionRun, "run", sched.getClass(),
                Plugin.class, World.class, int.class, int.class, Consumer.class);
        regionRun = m;
        final World world = location.getWorld();
        final Object result = invoke(sched, m, plugin, world, chunkX, chunkZ, toConsumer(task));
        if (result != null) {
            return track(result, false);
        }
        if (retriesLeft > 0) {
            plugin.getSLF4JLogger().error(
                    "Folia region schedule failed at {},{} — retrying in 1 tick", chunkX, chunkZ);
            return runGlobalDelayed(
                    () -> runAtLocationRetry(location, task, retriesLeft - 1, onScheduleFailure), 1L);
        }
        plugin.getSLF4JLogger().error(
                "Folia region schedule permanently failed at {},{}", chunkX, chunkZ);
        if (onScheduleFailure != null) {
            return runGlobal(onScheduleFailure);
        }
        return track(null, false);
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task) {
        // Bypass the EntityScheduler entirely — its execute() signature varies across
        // Folia versions (4-param vs 5-param) and the Folia reflection rewriter interferes
        // with getMethod() calls, causing "argument type mismatch" or "NoSuchMethodException".
        //
        // Instead, we run on the region that owns the entity's current location. This
        // achieves the same thread-affinity as the EntityScheduler for our use cases
        // (particle spawning, teleporting) without any EntityScheduler reflection.
        // The only difference is that if the entity moves between regions during the
        // scheduling delay, the task runs on the OLD region — but we always use delay=0,
        // so the entity hasn't moved by the time the task fires.
        return runAtLocation(entity.getLocation(), task);
    }

    // -----------------------------------------------------------------------
    // Global region
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runGlobal(@NotNull final Runnable task) {
        final Object sched = getGlobalRegionScheduler();
        final Method m = resolve(globalRun, "run", sched.getClass(),
                Plugin.class, Consumer.class);
        globalRun = m;
        return invokeAndMaybeTrack(false, sched, m, plugin, toConsumer(task));
    }

    @Override
    public @NotNull TaskHandle runGlobalDelayed(@NotNull final Runnable task, final long delayTicks) {
        final Object sched = getGlobalRegionScheduler();
        final Method m = resolve(globalRunDelayed, "runDelayed", sched.getClass(),
                Plugin.class, Consumer.class, long.class);
        globalRunDelayed = m;
        // Folia requires delay >= 1 tick for runDelayed (0 is allowed for runDelayed
        // but not for runAtFixedRate — clamp to be safe).
        final long safeDelay = Math.max(1L, delayTicks);
        return invokeAndMaybeTrack(false, sched, m, plugin, toConsumer(task), safeDelay);
    }

    @Override
    public @NotNull TaskHandle runGlobalRepeating(@NotNull final Runnable task,
                                                   final long delayTicks, final long periodTicks) {
        final Object sched = getGlobalRegionScheduler();
        final Method m = resolve(globalRunAtFixedRate, "runAtFixedRate", sched.getClass(),
                Plugin.class, Consumer.class, long.class, long.class);
        globalRunAtFixedRate = m;
        // Folia requires initial delay >= 1 tick. Clamp to minimum 1.
        final long safeDelay = Math.max(1L, delayTicks);
        final long safePeriod = Math.max(1L, periodTicks);
        return invokeAndMaybeTrack(true, sched, m, plugin, toConsumer(task), safeDelay, safePeriod);
    }

    // -----------------------------------------------------------------------
    // Chunks
    // -----------------------------------------------------------------------

    @Override
    public boolean isChunkLoaded(@NotNull final World world, final int x, final int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public void ensureChunkLoaded(@NotNull final World world, final int chunkX, final int chunkZ,
                                  @NotNull final Runnable callback) {
        // Folia: use the world's async chunk API if available (returns CompletableFuture).
        final Location loc = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
        try {
            final var method = world.getClass().getMethod("getChunkAtAsync", int.class, int.class);
            final Object future = method.invoke(world, chunkX, chunkZ);
            if (future instanceof java.util.concurrent.CompletableFuture<?> cf) {
                cf.whenComplete((ignored, err) -> runAtLocation(loc, () -> {
                    if (err != null) {
                        plugin.getSLF4JLogger().warn("Async chunk load failed at {},{}", chunkX, chunkZ, err);
                    }
                    world.getChunkAt(chunkX, chunkZ);
                    callback.run();
                }));
                return;
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("getChunkAtAsync unavailable, using region fallback", t);
        }
        // Fallback: schedule on the region owning the chunk; Folia will load it.
        runAtLocation(loc, () -> {
            world.getChunkAt(chunkX, chunkZ);
            callback.run();
        });
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    @Override
    public void cancelOwnedTasks() {
        // Snapshot the set to avoid concurrent modification during iteration.
        final Object[] tasks = owned.toArray();
        owned.clear();
        for (final Object task : tasks) {
            try {
                cancelScheduledTask(task);
            } catch (final Throwable ignored) {
                // Never let cancellation errors propagate during shutdown.
            }
        }
    }

    @Override
    public @NotNull String implementationName() {
        return "Folia";
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    private @NotNull Object getAsyncScheduler() {
        try {
            if (getAsyncScheduler == null) {
                getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            }
            return getAsyncScheduler.invoke(Bukkit.getServer());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Folia AsyncScheduler unavailable", e);
        }
    }

    private @NotNull Object getRegionScheduler() {
        try {
            if (getRegionScheduler == null) {
                getRegionScheduler = Bukkit.class.getMethod("getRegionScheduler");
            }
            return getRegionScheduler.invoke(Bukkit.getServer());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Folia RegionScheduler unavailable", e);
        }
    }

    private @NotNull Object getGlobalRegionScheduler() {
        try {
            if (getGlobalRegionScheduler == null) {
                getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            }
            return getGlobalRegionScheduler.invoke(Bukkit.getServer());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Folia GlobalRegionScheduler unavailable", e);
        }
    }

    private @NotNull Method resolve(@Nullable Method cached, @NotNull final String name,
                                    @NotNull final Class<?> clazz, final Class<?>... paramTypes) {
        if (cached != null) {
            return cached;
        }
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException("Folia method " + name + " not found on " + clazz, e);
        }
    }

    private @Nullable Object invoke(@NotNull final Object target, @NotNull final Method m, final Object... args) {
        try {
            return m.invoke(target, args);
        } catch (final ReflectiveOperationException e) {
            plugin.getSLF4JLogger().error("Folia scheduler invoke failed: {}.{}", target.getClass(), m.getName(), e);
            return null;
        }
    }

    private @NotNull TaskHandle invokeAndMaybeTrack(final boolean keepUntilCancel,
                                                    @NotNull final Object target,
                                                    @NotNull final Method m,
                                                    final Object... args) {
        final Object result = invoke(target, m, args);
        if (result == null) {
            plugin.getSLF4JLogger().error(
                    "Folia schedule returned null for {}.{}",
                    target.getClass().getSimpleName(), m.getName());
            // For non-region schedulers only: try to run the consumer so callers do not hang.
            // Region-bound work must never fall through here (see runAtLocation).
            final Consumer<?> consumer = findConsumerArg(args);
            if (consumer != null && !isRegionScheduler(target)) {
                try {
                    consumer.accept(null);
                } catch (final Throwable t) {
                    plugin.getSLF4JLogger().error("Folia fallback consumer failed", t);
                }
            }
            return track(null, false);
        }
        return track(result, keepUntilCancel);
    }

    private boolean isRegionScheduler(@NotNull final Object target) {
        final String n = target.getClass().getName();
        return n.contains("RegionScheduler") || n.contains("EntityScheduler");
    }

    @SuppressWarnings("rawtypes")
    private static @Nullable Consumer findConsumerArg(final Object... args) {
        for (final Object a : args) {
            if (a instanceof Consumer<?> c) {
                return c;
            }
        }
        return null;
    }

    private @NotNull TaskHandle track(@Nullable final Object scheduledTask, final boolean keepUntilCancel) {
        // Only retain repeating/long-lived tasks in {@code owned}. One-shots are
        // extremely frequent during regen and would grow without bound.
        if (keepUntilCancel && scheduledTask != null) {
            owned.add(scheduledTask);
        }
        return new TaskHandle() {
            private volatile boolean cancelled;
            @Override public void cancel() {
                if (cancelled || scheduledTask == null) return;
                cancelled = true;
                cancelScheduledTask(scheduledTask);
                owned.remove(scheduledTask);
            }
            @Override public @Nullable Object raw() { return scheduledTask; }
        };
    }

    /**
     * Cancel a Folia ScheduledTask reflectively. Fully null-safe and exception-safe:
     * catches ALL throwables (including InvocationTargetException from already-cancelled
     * or already-completed tasks) and never throws during shutdown.
     *
     * <p>Strategy:</p>
     * <ol>
     *   <li>Check if the task is already cancelled (via isCancelled()) — skip if so.</li>
     *   <li>Check if the task is already done (via isDone() / isExecuted()) — skip if so.</li>
     *   <li>Try {@code cancel()} (no-arg).</li>
     *   <li>Try {@code cancel(boolean)} as fallback.</li>
     *   <li>All exceptions are silently swallowed (best-effort during shutdown).</li>
     * </ol>
     */
    private void cancelScheduledTask(@Nullable final Object task) {
        if (task == null) {
            return;
        }
        // Check if already cancelled or done — avoid invoking cancel() unnecessarily.
        if (isTaskAlreadyCancelled(task)) {
            return;
        }
        // Try no-arg cancel() first.
        try {
            final Method cancel = task.getClass().getMethod("cancel");
            cancel.invoke(task);
            return;
        } catch (final Throwable ignored) {
            // Fall through to cancel(boolean).
        }
        // Try cancel(boolean) as fallback.
        try {
            final Method cancel2 = task.getClass().getMethod("cancel", boolean.class);
            cancel2.invoke(task, true);
        } catch (final Throwable ignored) {
            // Silently give up — this is best-effort during shutdown.
        }
    }

    /**
     * Check if a Folia ScheduledTask is already cancelled or completed.
     * Uses reflection to call isCancelled() and isExecuted() if they exist.
     *
     * @param task the task object.
     * @return true if the task is already cancelled, done, or executed.
     */
    private boolean isTaskAlreadyCancelled(@NotNull final Object task) {
        final Class<?> clazz = task.getClass();
        // Check isCancelled().
        try {
            final Method isCancelled = clazz.getMethod("isCancelled");
            final Object result = isCancelled.invoke(task);
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
        } catch (final Throwable ignored) {
            // Method may not exist — continue.
        }
        // Check isExecuted() (Folia's name for "is done").
        try {
            final Method isExecuted = clazz.getMethod("isExecuted");
            final Object result = isExecuted.invoke(task);
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
        } catch (final Throwable ignored) {
            // Method may not exist — continue.
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Runnable → Consumer<ScheduledTask> bridge
    // -----------------------------------------------------------------------

    /**
     * Wrap a {@link Runnable} into a {@code Consumer<ScheduledTask>} that ignores
     * the task argument. This is required because Folia's scheduler methods all
     * take {@code Consumer<ScheduledTask>} rather than plain {@code Runnable}.
     *
     * <p>Exceptions are caught and logged so they never propagate into Folia's
     * executors (which would silently swallow them).</p>
     *
     * @param r the runnable to wrap.
     * @return a raw Consumer that delegates to the runnable.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private @NotNull Consumer toConsumer(@NotNull final Runnable r) {
        return (Consumer) (Object task) -> safeRun(r);
    }

    /**
     * Run a runnable with full exception logging.
     */
    private void safeRun(@NotNull final Runnable r) {
        try {
            r.run();
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Unhandled exception in scheduled task", t);
        }
    }
}
