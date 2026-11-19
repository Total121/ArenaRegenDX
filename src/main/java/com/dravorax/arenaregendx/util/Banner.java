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
 * survives every logger / Windows / Linux console.
 */
public final class Banner {

    private Banner() {
    }

    public static void print(@NotNull final Logger log) {
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
        log.info("  ║     snapshot · restore · dust          v2.1.0            ║");
        log.info("  ║     paper / folia 1.21+                dravorax          ║");
        log.info("  ╚══════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
