package dev.stonerestore.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.stonerestore.StoneRestoreClient;
import dev.stonerestore.StoneRestoreEngine;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * /stonerestorer chunk [help | status | set <3-32> | max | refresh | diagnose]
 *
 * <p>Changes only the LOCAL client render distance (the same option as Options > Video Settings). It cannot
 * change what the server sends: the server's own view-distance caps the chunks you actually receive, and
 * {@code diagnose} measures that instead of guessing.
 */
public final class ChunkCommands {

    public static final int MIN = 3;
    public static final int MAX = 32;

    private static final int NOT_A_NUMBER = -1000;
    private static final int OUT_OF_RANGE = -1001;
    private static final int PROBE_RADIUS = 34;
    private static final long REFRESH_COOLDOWN_MS = 3000;
    private static long lastRefreshMillis;

    private ChunkCommands() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node(StoneRestoreEngine engine) {
        return literal("chunk")
                .executes(ChunkCommands::missing)
                .then(literal("help").executes(ChunkCommands::help))
                .then(literal("status").executes(c -> status(c, engine)))
                .then(literal("max").executes(c -> apply(c, engine, MAX)))
                .then(literal("refresh").executes(ChunkCommands::refresh))
                .then(literal("diagnose").executes(c -> diagnose(c, engine)))
                .then(literal("set")
                        .executes(ChunkCommands::missing)
                        .then(argument("value", StringArgumentType.word())
                                .suggests(ChunkCommands::suggest)
                                .executes(c -> set(c, engine))));
    }

    /** Re-applies the saved selection once at startup (not every frame). */
    public static void applySaved(MinecraftClient client, StoneRestoreEngine engine) {
        int saved = engine.config().clientRenderDistance;
        if (saved < MIN || saved > MAX) return;
        try {
            SimpleOption<Integer> option = client.options.getViewDistance();
            if (option.getValue() != saved) {
                option.setValue(saved);
                StoneRestoreClient.LOG.info("[StoneRestore] Applied saved client render distance: {} chunks", saved);
            }
        } catch (RuntimeException e) {
            StoneRestoreClient.LOG.warn("[StoneRestore] Could not apply saved render distance: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------

    private static CompletableFuture<Suggestions> suggest(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        for (int i = MIN; i <= MAX; i++) {
            String s = Integer.toString(i);
            if (s.startsWith(typed)) builder.suggest(s);
        }
        return builder.buildFuture();
    }

    private static void say(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendFeedback(StoneRestoreEngine.prefixed(message));
    }

    private static void error(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendError(StoneRestoreEngine.prefixed(message));
    }

    private static void detail(CommandContext<FabricClientCommandSource> ctx, String line) {
        ctx.getSource().sendFeedback(Text.literal(line).formatted(Formatting.GRAY));
    }

    private static int missing(CommandContext<FabricClientCommandSource> ctx) {
        error(ctx, "Missing required arguments.");
        detail(ctx, "Usage: /stonerestorer chunk set <3-32>");
        detail(ctx, "Example: /stonerestorer chunk set 12");
        detail(ctx, "Allowed range: 3\u201332 chunks.");
        detail(ctx, "Other: /stonerestorer chunk status | max | refresh | diagnose | help");
        return 0;
    }

    private static int help(CommandContext<FabricClientCommandSource> ctx) {
        say(ctx, "Chunk commands (local client render distance):");
        detail(ctx, "/stonerestorer chunk set <3-32>  - change your render distance (example: set 12)");
        detail(ctx, "/stonerestorer chunk max         - render distance 32");
        detail(ctx, "/stonerestorer chunk status      - show the current setting");
        detail(ctx, "/stonerestorer chunk refresh     - rebuild chunk rendering");
        detail(ctx, "/stonerestorer chunk diagnose    - compare your setting with the chunks the server really sends");
        return 1;
    }

    /** @return the value (MIN..MAX), NOT_A_NUMBER or OUT_OF_RANGE. Never clamps. */
    private static int parse(String raw) {
        if (!raw.matches("[+-]?\\d+")) return NOT_A_NUMBER;
        BigInteger v = new BigInteger(raw);
        if (v.compareTo(BigInteger.valueOf(MIN)) < 0 || v.compareTo(BigInteger.valueOf(MAX)) > 0) return OUT_OF_RANGE;
        return v.intValue();
    }

    private static int set(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        String raw = StringArgumentType.getString(ctx, "value");
        int parsed = parse(raw);
        if (parsed == NOT_A_NUMBER) {
            error(ctx, "Invalid render distance: " + raw + ".");
            detail(ctx, raw.matches("[+-]?\\d*\\.\\d+")
                    ? "The value must be a whole number - decimals are not allowed."
                    : "The value must be a whole number.");
            detail(ctx, "Allowed range: 3\u201332 chunks.");
            return 0;
        }
        if (parsed == OUT_OF_RANGE) {
            error(ctx, "Invalid render distance: " + raw + ".");
            detail(ctx, "Allowed range: 3\u201332 chunks.");
            return 0;
        }
        return apply(ctx, engine, parsed);
    }

    private static int apply(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine, int value) {
        MinecraftClient mc = MinecraftClient.getInstance();
        SimpleOption<Integer> option = mc.options.getViewDistance();
        option.setValue(value);          // vanilla's own option: its callback schedules the terrain update
        int actual = option.getValue();  // read back, never assume
        if (actual != value) {
            error(ctx, "Minecraft kept render distance " + actual + " instead of " + value + ".");
            return 0;
        }
        mc.options.write();              // persists options.txt (and lets vanilla inform the server)
        engine.config().clientRenderDistance = actual;
        engine.config().save();
        say(ctx, "Client render distance set to " + actual + " chunks.");
        if (mc.world != null && !mc.isIntegratedServerRunning()) {
            detail(ctx, "On multiplayer the server's view-distance caps what you receive - run /stonerestorer chunk diagnose.");
        }
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int current = mc.options.getViewDistance().getValue();
        int saved = engine.config().clientRenderDistance;
        say(ctx, "Client render distance: " + current + " chunks.");
        detail(ctx, saved >= MIN ? "Saved StoneRestore selection: " + saved + " chunks (re-applied at startup)."
                                 : "No StoneRestore selection saved - Minecraft's own setting is used.");
        return 1;
    }

    private static int refresh(CommandContext<FabricClientCommandSource> ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.worldRenderer == null) {
            error(ctx, "Join a world first - there is no chunk rendering to refresh.");
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < REFRESH_COOLDOWN_MS) {
            error(ctx, "A refresh was just requested - please wait a moment.");
            return 0;
        }
        lastRefreshMillis = now;
        mc.worldRenderer.reload();       // same as F3+A
        say(ctx, "Chunk rendering refresh requested.");
        return 1;
    }

    private static int diagnose(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int setting = mc.options.getViewDistance().getValue();
        say(ctx, "Render distance setting: " + setting + " chunks.");
        if (mc.world == null || mc.player == null) {
            detail(ctx, "Join a world to measure how many chunks the server actually supplies.");
            return 1;
        }
        ChunkPos center = mc.player.getChunkPos();
        int[] ring = new int[PROBE_RADIUS + 1];
        for (int dx = -PROBE_RADIUS; dx <= PROBE_RADIUS; dx++) {
            for (int dz = -PROBE_RADIUS; dz <= PROBE_RADIUS; dz++) {
                if (mc.world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz) != null) {
                    ring[Math.max(Math.abs(dx), Math.abs(dz))]++;
                }
            }
        }
        int farthest = 0;
        for (int d = 0; d <= PROBE_RADIUS; d++) if (ring[d] > 0) farthest = d;
        int complete = 0;
        int running = 0;
        for (int d = 0; d <= PROBE_RADIUS; d++) {
            running += ring[d];
            int side = 2 * d + 1;
            if (running == side * side) complete = d; else break;
        }
        detail(ctx, "Measured now: chunks are loaded completely out to " + complete + " and the farthest loaded chunk is "
                + farthest + " away (Minecraft loads one extra ring it does not draw).");
        if (mc.isIntegratedServerRunning()) {
            detail(ctx, "Singleplayer: the built-in server follows your render distance.");
        } else if (farthest - 1 < setting) {
            detail(ctx, "Fewer chunks arrive than your setting asks for. On a server this is normally the server's "
                    + "view-distance (or chunks are still streaming in). StoneRestore cannot raise it.");
        } else {
            detail(ctx, "The server is currently supplying at least as many chunks as your setting.");
        }
        detail(ctx, "The server's simulation-distance is separate and is not touched.");
        return 1;
    }
}
