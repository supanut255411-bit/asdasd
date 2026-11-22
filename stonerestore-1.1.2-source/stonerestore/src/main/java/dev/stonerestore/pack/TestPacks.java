package dev.stonerestore.pack;

import dev.stonerestore.core.GhostGridIcon;
import dev.stonerestore.core.PngPixels;
import dev.stonerestore.core.ProtectedAssets;
import dev.stonerestore.core.StoneScanner;
import net.minecraft.resource.ResourcePack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates test resource packs from the player's own vanilla assets (so no Mojang textures are shipped
 * inside this mod):
 *
 * <ul>
 *   <li>{@code AllStone} - every vanilla block texture replaced by vanilla stone (textures only).</li>
 *   <li>{@code UnstableVisual} - an approximation of the Unstable SMP visual pack's behaviour: plain grey stone on
 *       every full block including leaves (name-based approximation of "full block"; bells, glass, honey and
 *       other non-full blocks left alone), no green biome tint (untinted leaves model, neutral grass colour
 *       map), fire/flame textures left alone, rotation-free blockstates, a stone-pointing blockstate, a model
 *       with floating geometry, and invisible short/tall grass. It is a test fixture for StoneRestore, NOT a
 *       copy of the real pack.</li>
 *   <li>{@code Custom} - (no thumbnail) two legitimate custom block textures (solid magenta diamond block, solid cyan
 *       gold block) used to check that unrelated packs are preserved.</li>
 * </ul>
 */
public final class TestPacks {

    public static final String ALL_STONE = "StoneRestore-TestPack-AllStone.zip";
    public static final String UNSTABLE = "StoneRestore-TestPack-UnstableVisual.zip";
    public static final String CUSTOM = "StoneRestore-TestPack-Custom.zip";

    /**
     * Blocks the pack must NOT turn into stone: non-full blocks (bell ...), glass and honey, liquids, plants.
     * Leaves are deliberately NOT in this list - they become stone too.
     */
    private static final String[] NOT_STONE_TOKENS = {
            "glass", "honey_block", "bell", "water", "lava", "door", "sapling", "vine", "sign", "rail", "flower", "tulip",
            "orchid", "allium", "bluet", "cornflower", "dandelion", "poppy", "lily", "rose", "peony", "lilac", "stem",
            "wheat", "carrot", "potato", "beetroot", "fern", "torch", "lantern", "leaf_litter", "bush"};

    // pack_format 75 = 1.21.11; the supported range keeps neighbouring versions from showing a warning.
    private static String mcmeta(String description) {
        return "{\"pack\":{\"pack_format\":75,\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":99},"
                + "\"description\":\"" + description + "\"}}";
    }

    private TestPacks() {}

    public static List<String> generate(ResourcePack vanilla, Path resourcePackDir) throws IOException {
        PackTextureSource source = new PackTextureSource(vanilla);
        byte[] stone = source.read(StoneScanner.REFERENCE, StoneScanner.VANILLA_LIMIT);
        if (stone == null) throw new IOException("vanilla " + StoneScanner.REFERENCE + " not found");

        List<String> blockTextures = new ArrayList<>();
        for (String path : source.listAssets()) {
            if (path.startsWith("textures/block/") && path.endsWith(".png")) blockTextures.add(path);
        }
        blockTextures.sort(String::compareTo);
        Files.createDirectories(resourcePackDir);

        // thumbnail (pack.png): the 16x16 ghost grid, tiled from the player's own vanilla stone texture
        byte[] thumbnail = thumbnail(stone);

        // ---- AllStone
        Map<String, byte[]> allStone = new LinkedHashMap<>();
        allStone.put("pack.mcmeta", mcmeta("StoneRestore test pack: every block texture is vanilla stone").getBytes(StandardCharsets.UTF_8));
        allStone.put("pack.png", thumbnail);
        for (String path : blockTextures) allStone.put("assets/minecraft/" + path, stone);
        write(resourcePackDir.resolve(ALL_STONE), allStone);

        // ---- UnstableVisual (approximation)
        Map<String, byte[]> unstable = new LinkedHashMap<>();
        unstable.put("pack.mcmeta", mcmeta("StoneRestore test pack: approximation of the Unstable SMP visual pack").getBytes(StandardCharsets.UTF_8));
        unstable.put("pack.png", thumbnail);
        int stoneCount = 0;
        for (String path : blockTextures) {
            if (isEligible(path)) {
                unstable.put("assets/minecraft/" + path, stone);
                stoneCount++;
            }
        }
        // rotation-free blockstates (vanilla uses random y rotations for these)
        int blockstates = 0;
        for (String name : new String[]{"dirt", "gravel", "sand", "netherrack", "stone"}) {
            if (source.exists("blockstates/" + name + ".json")) {
                unstable.put("assets/minecraft/blockstates/" + name + ".json",
                        ("{\"variants\":{\"\":{\"model\":\"minecraft:block/" + name + "\"}}}").getBytes(StandardCharsets.UTF_8));
                blockstates++;
            }
        }
        if (source.exists("blockstates/grass_block.json")) {
            unstable.put("assets/minecraft/blockstates/grass_block.json",
                    ("{\"variants\":{\"snowy=false\":{\"model\":\"minecraft:block/grass_block\"},"
                            + "\"snowy=true\":{\"model\":\"minecraft:block/grass_block_snow\"}}}").getBytes(StandardCharsets.UTF_8));
            blockstates++;
        }
        // a blockstate that points at the stone model
        if (source.exists("blockstates/red_sandstone.json")) {
            unstable.put("assets/minecraft/blockstates/red_sandstone.json",
                    "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}".getBytes(StandardCharsets.UTF_8));
        }
        // floating geometry: a stone slab hovering one block above red sand, particle stays red sand
        if (source.exists("models/block/red_sand.json")) {
            unstable.put("assets/minecraft/models/block/red_sand.json", ("{\"textures\":{\"all\":\"minecraft:block/stone\","
                    + "\"particle\":\"minecraft:block/red_sand\"},\"elements\":[{\"from\":[0,16,0],\"to\":[16,17,16],"
                    + "\"faces\":{\"up\":{\"texture\":\"#all\"},\"down\":{\"texture\":\"#all\"},\"north\":{\"texture\":\"#all\"},"
                    + "\"south\":{\"texture\":\"#all\"},\"east\":{\"texture\":\"#all\"},\"west\":{\"texture\":\"#all\"}}}]}").getBytes(StandardCharsets.UTF_8));
        }
        // grey stone, not green: leaves model without tintindex (vanilla block/leaves tints every face) ...
        if (source.exists("models/block/leaves.json")) {
            unstable.put("assets/minecraft/models/block/leaves.json", ("{\"parent\":\"minecraft:block/block\","
                    + "\"textures\":{\"particle\":\"#all\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                    + "\"down\":{\"texture\":\"#all\",\"cullface\":\"down\"},\"up\":{\"texture\":\"#all\",\"cullface\":\"up\"},"
                    + "\"north\":{\"texture\":\"#all\",\"cullface\":\"north\"},\"south\":{\"texture\":\"#all\",\"cullface\":\"south\"},"
                    + "\"west\":{\"texture\":\"#all\",\"cullface\":\"west\"},\"east\":{\"texture\":\"#all\",\"cullface\":\"east\"}}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        // ... and a neutral grass colour map, so grass blocks stay grey too
        unstable.put("assets/minecraft/textures/colormap/grass.png", PngPixels.solid(256, 0xFFFFFFFF));
        // invisible short and tall grass
        byte[] clear = PngPixels.encodeRgba(16, 16, new int[256]);
        for (String n : new String[]{"short_grass", "tall_grass_top", "tall_grass_bottom"}) {
            unstable.put("assets/minecraft/textures/block/" + n + ".png", clear);
        }
        unstable.put("assets/minecraft/models/block/stonerestore_empty.json", "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8));
        unstable.put("assets/minecraft/blockstates/short_grass.json",
                "{\"variants\":{\"\":{\"model\":\"minecraft:block/stonerestore_empty\"}}}".getBytes(StandardCharsets.UTF_8));
        unstable.put("assets/minecraft/blockstates/tall_grass.json",
                ("{\"variants\":{\"half=lower\":{\"model\":\"minecraft:block/stonerestore_empty\"},"
                        + "\"half=upper\":{\"model\":\"minecraft:block/stonerestore_empty\"}}}").getBytes(StandardCharsets.UTF_8));
        write(resourcePackDir.resolve(UNSTABLE), unstable);

        // ---- Custom
        Map<String, byte[]> custom = new LinkedHashMap<>();
        custom.put("pack.mcmeta", mcmeta("StoneRestore test pack: legitimate custom textures").getBytes(StandardCharsets.UTF_8));
        custom.put("assets/minecraft/textures/block/diamond_block.png", PngPixels.solid(16, 0xFFFF00FF));
        custom.put("assets/minecraft/textures/block/gold_block.png", PngPixels.solid(16, 0xFF00FFFF));
        write(resourcePackDir.resolve(CUSTOM), custom);

        List<String> lines = new ArrayList<>();
        lines.add("Wrote 3 packs to resourcepacks/: " + ALL_STONE + " (" + blockTextures.size() + " block textures), "
                + UNSTABLE + " (" + stoneCount + " stone textures incl. leaves, untinted, " + blockstates
                + " rotation-free blockstates, invisible grass, floating model), " + CUSTOM + ".");
        lines.add("Enable them in Options > Resource Packs. Put the custom pack BELOW a stone pack to see it preserved.");
        return lines;
    }

    private static byte[] thumbnail(byte[] stonePng) {
        PngPixels.Image tile = PngPixels.decode(stonePng);
        if (tile != null && tile.width() == tile.height() && tile.width() <= 32) return GhostGridIcon.fromTile(tile);
        return GhostGridIcon.procedural(128);
    }

    /** Name-based stand-in for "becomes stone": every full block (leaves too), but not fire/flame, non-full blocks, glass, honey. */
    private static boolean isEligible(String path) {
        if (ProtectedAssets.isProtected(path)) return false;
        String base = path.substring(path.lastIndexOf('/') + 1);
        base = base.substring(0, base.length() - ".png".length());
        if (base.contains("grass") && !base.startsWith("grass_block")) return false;
        for (String token : NOT_STONE_TOKENS) if (base.contains(token)) return false;
        return true;
    }

    private static void write(Path target, Map<String, byte[]> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }
}
