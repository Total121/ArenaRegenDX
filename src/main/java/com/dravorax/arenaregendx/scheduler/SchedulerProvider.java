/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Detects the server runtime (Folia vs Paper) and produces the correct
 * {@link SchedulerAdapter}.
 *
 * <p>Detection is classpath-based: Folia ships {@code io.papermc.paper.threadedregions.RegionizedServer}
 * while Paper does not. We probe reflectively so the jar compiles against the
 * Paper API only, avoiding a hard Folia compile dependency.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class SchedulerProvider {

    /** Folia marker class — present only on Folia runtimes. */
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    /** Cached detection result (computed once). */
    private static volatile Boolean foliaCache;

    private SchedulerProvider() {
        // utility
    }

    /**
     * @return true if the running server is Folia.
     */
    public static boolean isFolia() {
        if (foliaCache != null) {
            return foliaCache;
        }
        synchronized (SchedulerProvider.class) {
            if (foliaCache != null) {
                return foliaCache;
            }
            boolean detected;
            try {
                Class.forName(FOLIA_MARKER, false, Bukkit.class.getClassLoader());
                detected = true;
            } catch (final ClassNotFoundException ex) {
                detected = false;
            }
            foliaCache = detected;
            return detected;
        }
    }

    /**
     * Create the appropriate scheduler adapter.
     *
     * @param plugin the owning plugin.
     * @param folia  whether the server is Folia (use {@link #isFolia()}).
     * @return the adapter.
     */
    public static @NotNull SchedulerAdapter create(@NotNull final Plugin plugin, final boolean folia) {
        return folia ? new FoliaSchedulerAdapter(plugin) : new PaperSchedulerAdapter(plugin);
    }
}
