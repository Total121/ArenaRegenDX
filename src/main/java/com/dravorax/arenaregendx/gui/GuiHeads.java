/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Custom player-head textures sourced from
 * <a href="https://minecraft-heads.com">minecraft-heads.com</a>
 * (TheLuca98 public hash mirror). Applied via Paper {@link PlayerTextures}.
 */
public final class GuiHeads {

    private GuiHeads() {
    }

    /** Emerald Heart — brand / logo. */
    public static final String LOGO = "7292ca166d8ef15b4f5defb4338ed2239a9774527db01d7226cb0cbf1c1528d0";
    /** Earth Rune — arenas / world. */
    public static final String ARENAS = "dfb8022c9d9a05e08316da87457bcab2785c3ba7a590d4947cef68843b4d07ad";
    /** Lime Arrow Up — create. */
    public static final String CREATE = "b221da4418bd3bfb42eb64d2ab429c61decb8f4bf7d4cfb77a162be3dcb0b927";
    /** Cyan Arrow Right — restore. */
    public static final String RESTORE = "6ff55f1b32c3435ac1ab3e5e535c50b527285da716e54fe701c9b59352afc1c";
    /** Yellow Arrow Up — performance. */
    public static final String PERFORMANCE = "3e4f2f9698c3f186fe44cc63d2f3c4f9a241223acf0581775d9cecd7075";
    /** Netherite 8 — settings cog look. */
    public static final String SETTINGS = "869cd74c0a8a2f01055886f815f8e1ed6d7de6e788aae9f86bd37e2d4e4621c6";
    /** Question Mark. */
    public static final String HELP = "178fceede7c5aa4dfd57a701aa2fe9556bbffdb5101902e320d33f39b2b503f9";
    /** Chat Exclamation — status. */
    public static final String STATUS = "85409b666103eaf5d3d3ccc3c6a9212638a943107736dc93e6661cd4e44f92c5";
    /** Lime Arrow Up — instant. */
    public static final String INSTANT = "b221da4418bd3bfb42eb64d2ab429c61decb8f4bf7d4cfb77a162be3dcb0b927";
    /** Cyan Arrow Up — layer. */
    public static final String LAYER = "4b221cb9607c8a9bf02fef5d7614e3eb169cc219bf4250fd5715d5d2d6045f7";
    /** Cyan Arrow Right — chunk. */
    public static final String CHUNK = "6ff55f1b32c3435ac1ab3e5e535c50b527285da716e54fe701c9b59352afc1c";
    /** Fire Rune — animated. */
    public static final String ANIMATED = "ca7d57836cad775d1f30f0eebaf040f89cedc307a9dfea0b483631bb5f75b25a";
    /** Orange Arrow Left — back. */
    public static final String BACK = "6e8c3ce2aee6cf2faade7db37bbae73a36627ac1473fef75b410a0af97659f";
    /** Cyan Arrow Right — next. */
    public static final String NEXT = "6ff55f1b32c3435ac1ab3e5e535c50b527285da716e54fe701c9b59352afc1c";
    /** Lime Plus. */
    public static final String PLUS = "b056bc1244fcff99344f12aba42ac23fee6ef6e3351d27d273c1572531f";
    /** Red Minus. */
    public static final String MINUS = "4e4b8b8d2362c864e062301487d94d3272a6b570afbf80c2c5b148c954579d46";
    /** Bomb Angry Bird — explosion. */
    public static final String EXPLOSION = "e142d1fa1263a1530873b7de0e38fe935b2321a353d0b0bec518fa73319be1d4";
    /** Fire Rune — fire. */
    public static final String FIRE = "ca7d57836cad775d1f30f0eebaf040f89cedc307a9dfea0b483631bb5f75b25a";
    /** Emerald Heart — particles. */
    public static final String DUST = "7292ca166d8ef15b4f5defb4338ed2239a9774527db01d7226cb0cbf1c1528d0";
    /** Yellow Arrow Right — reload. */
    public static final String RELOAD = "141ff6bc67a481232d2e669e43c4f087f9d2306665b4f829fb86892d13b70ca";
    /** Bloody Exclamation — delete. */
    public static final String DELETE = "ffdbb4e477dbabbeb25e76542eb5c133705a4b69438cdcae2a0b90b06b40cf96";
    /** Cyan Plus — spawn. */
    public static final String SPAWN = "dd8b7173d841f2563ec108888b0f797917efc18be27861f0a6761aa3ed91ce";
    /** Netherite A — save / snapshot. */
    public static final String SAVE = "36de3314382b210b34e8c1b5d25ae4b75b849046e8ea0ef0e800a328baab5f7e";
    /** Question Mark (oak) — info. */
    public static final String INFO = "d34e063cafb467a5c8de43ec78619399f369f4a52434da8017a983cdd92516a0";
    /** Green Plus — async on. */
    public static final String ON = "5ff31431d64587ff6ef98c0675810681f8c13bf96f51d9cb07ed7852b2ffd1";
    /** Red Minus — async off. */
    public static final String OFF = "4e4b8b8d2362c864e062301487d94d3272a6b570afbf80c2c5b148c954579d46";
    /** Brick — arena card ready. */
    public static final String ARENA_READY = "350b6fa5db45accd93826c6e37ba32175f4b76ed26c1b6364135a22b5179f038";
    /** Fire Energy — regenerating. */
    public static final String ARENA_BUSY = "44253275e1b7757130a2d637d07f522fcc6686dc804346f0e71668d5ce6d5891";
    /** Black Exclamation — error. */
    public static final String ARENA_ERROR = "dd2acd9f2dfc2e05f69d941fe9970e8c3f05527a02a9381157891c8ddb8cf3";
    /** Page / paper. */
    public static final String PAGE = "178fceede7c5aa4dfd57a701aa2fe9556bbffdb5101902e320d33f39b2b503f9";
    /** Gold dust preview. */
    public static final String PREVIEW = "7292ca166d8ef15b4f5defb4338ed2239a9774527db01d7226cb0cbf1c1528d0";
    /** TPS. */
    public static final String TPS = "3e4f2f9698c3f186fe44cc63d2f3c4f9a241223acf0581775d9cecd7075";
    /** Queue. */
    public static final String QUEUE = "6ff55f1b32c3435ac1ab3e5e535c50b527285da716e54fe701c9b59352afc1c";
    /** Memory. */
    public static final String MEMORY = "7292ca166d8ef15b4f5defb4338ed2239a9774527db01d7226cb0cbf1c1528d0";
    /** Wand / create. */
    public static final String WAND = "b221da4418bd3bfb42eb64d2ab429c61decb8f4bf7d4cfb77a162be3dcb0b927";

    public static @NotNull ItemStack of(@NotNull final String hash) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return head;
        }
        try {
            final UUID uuid = UUID.nameUUIDFromBytes(("ardx-head:" + hash).getBytes(StandardCharsets.UTF_8));
            final com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(uuid, "ardx");
            final String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                    + hash + "\"}}}";
            final String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", value));
            try {
                final PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create("https://textures.minecraft.net/texture/" + hash).toURL());
                profile.setTextures(textures);
            } catch (final Exception ignored) {
            }
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
        } catch (final Exception ignored) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        return head;
    }
}
