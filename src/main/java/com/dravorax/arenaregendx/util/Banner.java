/*
 * ArenaRegenDX
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.util;

import org.slf4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Console splash printed on enable. Box-drawing only — no colour codes, so it
 * survives every logger / Windows / Linux console. Disable with {@code banner: false}.
 */
public final class Banner {

    private Banner() {
    }

    public static void print(@NotNull final Logger log, @NotNull final String version) {
        final String ver = ("v" + version + "                  ").substring(0, 18);
        log.info("");
        log.info("  ╔══════════════════════════════════════════════════════════╗");
        log.info("  ║                                                          ║");
        log.info("  ║      █████╗ ██████╗ ███████╗███╗   ██╗ █████╗            ║");
        log.info("  ║     ██╔══██╗██╔══██╗██╔════╝████╗  ██║██╔══██╗           ║");
        log.info("  ║     ███████║██████╔╝█████╗  ██╔██╗ ██║███████║           ║");
        log.info("  ║     ██╔══██║██╔══██╗██╔══╝  ██║╚██╗██║██╔══██║           ║");
        log.info("  ║     ██║  ██║██║  ██║███████╗██║ ╚████║██║  ██║           ║");
        log.info("  ║     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝╚═╝  ╚═══╝╚═╝  ╚═╝           ║");
        log.info("  ║                                                          ║");
        log.info("  ║              ██████╗ ███████╗ ██████╗ ███████╗███╗   ██╗ ║");
        log.info("  ║              ██╔══██╗██╔════╝██╔════╝ ██╔════╝████╗  ██║ ║");
        log.info("  ║              ██████╔╝█████╗  ██║  ███╗█████╗  ██╔██╗ ██║ ║");
        log.info("  ║              ██╔══██╗██╔══╝  ██║   ██║██╔══╝  ██║╚██╗██║ ║");
        log.info("  ║              ██║  ██║███████╗╚██████╔╝███████╗██║ ╚████║ ║");
        log.info("  ║              ╚═╝  ╚═╝╚══════╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝ ║");
        log.info("  ║                                                          ║");
        log.info("  ║                       ██████╗ ██╗  ██╗                   ║");
        log.info("  ║                       ██╔══██╗╚██╗██╔╝                   ║");
        log.info("  ║                       ██║  ██║ ╚███╔╝                    ║");
        log.info("  ║                       ██║  ██║ ██╔██╗                    ║");
        log.info("  ║                       ██████╔╝██╔╝ ██╗                   ║");
        log.info("  ║                       ╚═════╝ ╚═╝  ╚═╝                   ║");
        log.info("  ║                                                          ║");
        log.info("  ║     snapshot · restore · dust          {}║", ver);
        log.info("  ║     paper / folia 1.21+                dravorax          ║");
        log.info("  ╚══════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
