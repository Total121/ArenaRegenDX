/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.scheduler;

import org.jetbrains.annotations.Nullable;

/**
 * Opaque handle to a scheduled task, used for cancellation.
 *
 * <p>Implementations store either a Bukkit {@code BukkitTask} or a Folia
 * {@code ScheduledTask} internally. The handle abstracts away the concrete type.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public interface TaskHandle {

    /**
     * Cancel the underlying task. No-op if already cancelled or executed.
     */
    void cancel();

    /**
     * @return the raw underlying object (BukkitTask / ScheduledTask), or null.
     */
    @Nullable Object raw();
}
