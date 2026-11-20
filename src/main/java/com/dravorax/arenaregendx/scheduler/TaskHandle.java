/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.scheduler;

import org.jetbrains.annotations.Nullable;

/**
 * Opaque handle to a scheduled task, used for cancellation.
 *
 * <p>Implementations wrap either a Bukkit {@code BukkitTask} or a Folia
 * {@code ScheduledTask}.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public interface TaskHandle {

    /** Handle for work that was never scheduled (or already ran inline). */
    TaskHandle NOOP = new TaskHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public @Nullable Object raw() {
            return null;
        }
    };

    /** Cancel the underlying task. No-op if already cancelled or executed. */
    void cancel();

    /** @return the raw underlying object (BukkitTask / ScheduledTask), or null. */
    @Nullable Object raw();
}
