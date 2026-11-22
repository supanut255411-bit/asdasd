package dev.stonerestore.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Finds what a "stone visual" resource pack changed and decides what to give back to vanilla.
 *
 * <p><b>Textures.</b> A texture is a stone replacement when its bytes - or, for re-encoded files, its decoded
 * pixels - equal vanilla {@code stone.png} while vanilla's own texture at that path is something else.
 * Stone at stone's own path, paths vanilla does not have, and fire/flame assets are left alone. A pack is
 * only flagged once it has at least {@code minReplacements} such textures.
 *
 * <p><b>Blockstates / models / particle definitions</b> (only in a flagged pack, only where the pack's file
 * really differs from vanilla's by content, not whitespace). A file is restored when it
 * <ol>
 *   <li>is one of the short-grass / tall-grass blockstates or models (the pack hides those plants), or</li>
 *   <li>points at the stone texture or stone model although the vanilla file does not, or</li>
 *   <li>has fewer {@code tintindex} entries than vanilla's version (the pack removed the biome tint so that
 *       stone stays grey - this is how shared models such as {@code block/leaves} are caught), or</li>
 *   <li>belongs to a block whose texture was replaced by stone and differs from vanilla anyway
 *       (forced 0 degree rotation, changed UVs, moved/floating elements, changed model parent ...).</li>
 * </ol>
 * The short/tall grass textures and the biome colour maps are restored too when the pack swapped them (e.g.
 * for a transparent image, or a neutral colour map that keeps grass and leaves from turning green).
 * Everything else the pack ships - custom models, other packs' ideas - is kept.
 */
public final class StoneScanner {

    public static final String REFERENCE = "textures/block/stone.png";

    /** A stone texture is tiny; anything bigger in a pack can't be a copy of it. */
    public static final int PACK_LIMIT = 64 * 1024;
    public static final int VANILLA_LIMIT = 16 * 1024 * 1024;
    public static final int JSON_LIMIT = 512 * 1024;
    private static final int HASH_LIMIT = 4 * 1024 * 1024;

    private static final Set<String> STONE_REFS = Set.of("minecraft:block/stone", "block/stone");
    private static final Set<String> PLANT_TEXTURES = Set.of(
            "textures/block/short_grass.png", "textures/block/tall_grass_top.png", "textures/block/tall_grass_bottom.png");
    /** Biome colour maps: a neutral/white map is another way to stop stone from being tinted green. */
    private static final Set<String> COLORMAP_TEXTURES = Set.of(
            "textures/colormap/grass.png", "textures/colormap/foliage.png", "textures/colormap/dry_foliage.png");
    private static final int COLORMAP_LIMIT = 1024 * 1024;
    private static final Set<String> PLANT_JSON = Set.of(
            "blockstates/short_grass.json", "blockstates/tall_grass.json",
            "models/block/short_grass.json", "models/block/tall_grass_bottom.json", "models/block/tall_grass_top.json");

    private final TextureSource vanilla;
    private final byte[] stoneBytes;
    private final PngPixels.Size stoneSize;
    private final byte[] stonePixelHash; // null if vanilla stone isn't decodable by PngPixels

    private StoneScanner(TextureSource vanilla, byte[] stoneBytes) {
        this.vanilla = vanilla;
        this.stoneBytes = stoneBytes;
        this.stoneSize = PngPixels.peekSize(stoneBytes);
        PngPixels.Image img = PngPixels.decode(stoneBytes);
        this.stonePixelHash = img == null ? null : PngPixels.pixelHash(img);
    }

    public static StoneScanner create(TextureSource vanilla) throws IOException {
        byte[] stone = vanilla.read(REFERENCE, VANILLA_LIMIT);
        if (stone == null) throw new IOException("vanilla " + REFERENCE + " could not be read");
        return new StoneScanner(vanilla, stone);
    }

    public boolean isStone(byte[] png) {
        if (png == null) return false;
        if (Arrays.equals(png, stoneBytes)) return true;
        if (stonePixelHash == null || stoneSize == null) return false;
        if (!stoneSize.equals(PngPixels.peekSize(png))) return false;
        PngPixels.Image img = PngPixels.decode(png);
        return img != null && Arrays.equals(PngPixels.pixelHash(img), stonePixelHash);
    }

    public ScanResult scan(TextureSource pack, RestoreSettings settings, Consumer<String> trace) throws IOException {
        List<String> assets = new ArrayList<>(pack.listAssets());
        Collections.sort(assets);

        int analyzed = 0;
        int analyzedModels = 0;
        int matched = 0;
        int legit = 0;
        int unknown = 0;
        int skippedProtected = 0;
        List<String> failures = new ArrayList<>();
        Map<String, ScanResult.Restore> textures = new TreeMap<>();
        Map<String, byte[]> plantTextures = new TreeMap<>();

        // ---------------------------------------------------------------- phase 1: textures
        for (String path : assets) {
            if (!path.endsWith(".png") || !settings.allows(TextureCategory.of(path))) continue;
            analyzed++;
            if (ProtectedAssets.isProtected(path)) {
                skippedProtected++;
                note(trace, path, "fire/flame asset - never touched");
                continue;
            }
            try {
                if (settings.force()) {
                    if (vanilla.exists(path)) {
                        matched++;
                        textures.put(path, restore("force mode", pack.read(path, HASH_LIMIT)));
                        note(trace, path, "force mode: will use the vanilla texture");
                    } else {
                        unknown++;
                        note(trace, path, "no vanilla counterpart, left alone");
                    }
                    continue;
                }
                boolean colormap = COLORMAP_TEXTURES.contains(path);
                byte[] bytes = pack.read(path, colormap ? COLORMAP_LIMIT : PACK_LIMIT);
                if (bytes == null) continue;
                if (!isStone(bytes)) {
                    if (PLANT_TEXTURES.contains(path) || colormap) plantTextures.put(path, bytes);
                    continue;
                }
                byte[] original = vanilla.read(path, VANILLA_LIMIT);
                if (original == null) {
                    unknown++;
                    note(trace, path, "stone copy, but vanilla has no such texture - left alone");
                } else if (isStone(original)) {
                    legit++;
                    note(trace, path, "vanilla texture here is stone too - left alone");
                } else {
                    matched++;
                    textures.put(path, restore("copy of the stone texture", bytes));
                    note(trace, path, "stone copy replacing a non-stone vanilla texture");
                }
            } catch (IOException e) {
                failures.add(path + ": " + e.getMessage());
            }
        }

        boolean flagged = settings.force() ? matched > 0 : matched >= Math.max(1, settings.minReplacements());
        if (!flagged) {
            return new ScanResult(analyzed, 0, matched, legit, unknown, skippedProtected, false, settings.force(),
                    Map.of(), List.copyOf(failures));
        }

        Map<String, ScanResult.Restore> restored = new TreeMap<>(textures);

        // ---------------------------------------------------------------- phase 2a: hidden plants (textures)
        for (Map.Entry<String, byte[]> e : plantTextures.entrySet()) {
            try {
                byte[] original = vanilla.read(e.getKey(), VANILLA_LIMIT);
                if (original != null && !Arrays.equals(original, e.getValue())) {
                    String why = COLORMAP_TEXTURES.contains(e.getKey())
                            ? "biome colour map differs from vanilla (grass/leaves would stay untinted)"
                            : PngPixels.isFullyTransparent(e.getValue())
                            ? "grass texture replaced by a fully transparent image (invisible plant)"
                            : "grass texture differs from vanilla";
                    restored.put(e.getKey(), restore(why, e.getValue()));
                    note(trace, e.getKey(), why);
                }
            } catch (IOException ex) {
                failures.add(e.getKey() + ": " + ex.getMessage());
            }
        }

        // ---------------------------------------------------------------- phase 2b: blockstates / models / particles
        Set<String> restoredBases = new HashSet<>();
        for (String p : restored.keySet()) restoredBases.add(baseName(p));

        for (String path : assets) {
            if (!isJsonAsset(path) || !settings.allows(TextureCategory.of(path))) continue;
            analyzedModels++;
            if (ProtectedAssets.isProtected(path)) {
                skippedProtected++;
                note(trace, path, "fire/flame asset - never touched");
                continue;
            }
            try {
                byte[] packBytes = pack.read(path, JSON_LIMIT);
                if (packBytes == null) continue;
                byte[] vanillaBytes = vanilla.read(path, JSON_LIMIT);
                if (vanillaBytes == null) {
                    unknown++;
                    note(trace, path, "custom file vanilla does not have - left alone");
                    continue;
                }
                if (Arrays.equals(packBytes, vanillaBytes)) continue;

                Object packTree = null;
                Object vanillaTree = null;
                boolean packParsed = true;
                try {
                    packTree = MiniJson.parse(packBytes);
                } catch (IllegalArgumentException ex) {
                    packParsed = false;
                }
                try {
                    vanillaTree = MiniJson.parse(vanillaBytes);
                } catch (IllegalArgumentException ex) {
                    vanillaTree = null;
                }
                if (packParsed && vanillaTree != null && java.util.Objects.equals(packTree, vanillaTree)) continue; // formatting only

                String reason = null;
                if (settings.force()) {
                    reason = "force mode: differs from vanilla";
                } else if (PLANT_JSON.contains(path)) {
                    reason = "short/tall grass " + (path.startsWith("blockstates/") ? "blockstate" : "model") + " differs from vanilla";
                } else if (packParsed && MiniJson.mentions(packTree, STONE_REFS)
                        && !(vanillaTree != null && MiniJson.mentions(vanillaTree, STONE_REFS))) {
                    reason = "points at the stone texture/model";
                } else if (packParsed && vanillaTree != null
                        && MiniJson.countKey(packTree, "tintindex") < MiniJson.countKey(vanillaTree, "tintindex")) {
                    reason = "removes the biome tint vanilla uses (grass/leaves/vines would stay grey)";
                } else if (isBlockJson(path) && isLinked(path, restoredBases)) {
                    reason = "differs from vanilla for a block whose texture was replaced by stone (rotation/UV/geometry)";
                }
                if (reason != null) {
                    restored.put(path, restore(reason, packBytes));
                    note(trace, path, reason);
                } else {
                    note(trace, path, "differs from vanilla but is not stone-related - kept");
                }
            } catch (IOException e) {
                failures.add(path + ": " + e.getMessage());
            }
        }

        return new ScanResult(analyzed, analyzedModels, matched, legit, unknown, skippedProtected, true, settings.force(),
                Collections.unmodifiableMap(restored), List.copyOf(failures));
    }

    private static boolean isJsonAsset(String path) {
        return path.endsWith(".json")
                && (path.startsWith("blockstates/") || path.startsWith("models/") || path.startsWith("particles/"));
    }

    private static boolean isBlockJson(String path) {
        return path.startsWith("blockstates/") || path.startsWith("models/block/");
    }

    /** "textures/block/oak_log_top.png" -> "oak_log_top"; "blockstates/dirt.json" -> "dirt". */
    private static String baseName(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        int dot = base.indexOf('.');
        return dot < 0 ? base : base.substring(0, dot);
    }

    /** Does this blockstate/model belong to a block one of whose textures was restored? (name based) */
    private static boolean isLinked(String path, Set<String> restoredBases) {
        String name = baseName(path);
        if (name.equals("stone")) return true; // stone's own rotation variants are part of the pack's effect
        for (String b : restoredBases) {
            if (b.equals(name) || b.startsWith(name + "_") || name.startsWith(b + "_")) return true;
        }
        return false;
    }

    private static ScanResult.Restore restore(String reason, byte[] packBytes) {
        return new ScanResult.Restore(reason, packBytes == null ? null : PngPixels.sha256(packBytes));
    }

    private static void note(Consumer<String> trace, String path, String message) {
        if (trace != null) trace.accept(path + ": " + message);
    }
}
