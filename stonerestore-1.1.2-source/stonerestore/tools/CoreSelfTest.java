import dev.stonerestore.core.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Stand-alone self test for the Minecraft-free detection core. Run (no Gradle needed):
 *
 *   javac -d out src/main/java/dev/stonerestore/core/*.java tools/CoreSelfTest.java
 *   java -cp out CoreSelfTest
 */
public class CoreSelfTest {

    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + name);
        }
    }

    static class MapSource implements TextureSource {
        final Map<String, byte[]> files = new TreeMap<>();
        MapSource put(String path, byte[] data) { files.put(path, data); return this; }
        public Collection<String> listAssets() { return new ArrayList<>(files.keySet()); }
        public byte[] read(String path, int max) {
            byte[] b = files.get(path);
            return (b == null || b.length > max) ? null : b;
        }
        public boolean exists(String path) { return files.containsKey(path); }
    }

    static int[] noise(int w, int h, long seed, boolean alpha) {
        Random r = new Random(seed);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int a = alpha ? (r.nextInt(4) == 0 ? 0 : 255) : 255;
            px[i] = (a << 24) | (r.nextInt(256) << 16) | (r.nextInt(256) << 8) | r.nextInt(256);
        }
        return px;
    }

    static byte[] viaImageIO(int w, int h, int[] argb, int type) throws IOException {
        BufferedImage img = new BufferedImage(w, h, type);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb[y * w + x]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // ---- PNG decoding ------------------------------------------------------------------
        System.out.println("PNG decoder");
        int[] px = noise(16, 16, 7, true);
        byte[] mine = PngPixels.encodeRgba(16, 16, px);
        PngPixels.Image back = PngPixels.decode(mine);
        check(back != null && Arrays.equals(back.argb(), px), "own encoder -> decoder round trip (RGBA)");
        check(PngPixels.peekSize(mine).equals(new PngPixels.Size(16, 16)), "peekSize");

        int[] big = noise(40, 33, 99, true);
        byte[] ioArgb = viaImageIO(40, 33, big, BufferedImage.TYPE_INT_ARGB);
        PngPixels.Image d = PngPixels.decode(ioArgb);
        check(d != null && Arrays.equals(d.argb(), normalise(big)), "ImageIO ARGB (adaptive filters) decodes identically");

        int[] opaque = noise(40, 33, 5, false);
        byte[] ioRgb = viaImageIO(40, 33, opaque, BufferedImage.TYPE_3BYTE_BGR);
        d = PngPixels.decode(ioRgb);
        check(d != null && Arrays.equals(d.argb(), opaque), "ImageIO RGB decodes identically");

        BufferedImage idx = new BufferedImage(24, 24, BufferedImage.TYPE_BYTE_INDEXED);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) idx.setRGB(x, y, opaque[y * 33 + x] | 0xFF000000);
        ByteArrayOutputStream o2 = new ByteArrayOutputStream();
        ImageIO.write(idx, "png", o2);
        d = PngPixels.decode(o2.toByteArray());
        boolean palOk = d != null;
        if (palOk) for (int y = 0; y < 24 && palOk; y++) for (int x = 0; x < 24; x++)
            if (d.argb()[y * 24 + x] != idx.getRGB(x, y)) { palOk = false; break; }
        check(palOk, "ImageIO palette PNG decodes identically");

        BufferedImage gray = new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_GRAY);
        Random gr = new Random(3);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) gray.getRaster().setSample(x, y, 0, gr.nextInt(256));
        ByteArrayOutputStream o3 = new ByteArrayOutputStream();
        ImageIO.write(gray, "png", o3);
        d = PngPixels.decode(o3.toByteArray());
        boolean grayOk = d != null;
        if (grayOk) for (int y = 0; y < 20 && grayOk; y++) for (int x = 0; x < 20; x++) {
            int v = gray.getRaster().getSample(x, y, 0);
            if (d.argb()[y * 20 + x] != (0xFF000000 | v << 16 | v << 8 | v)) { grayOk = false; break; }
        }
        check(grayOk, "ImageIO grayscale PNG decodes identically");

        check(PngPixels.decode(new byte[]{1, 2, 3}) == null, "garbage -> null");
        check(PngPixels.decode(Arrays.copyOf(mine, mine.length / 2)) == null, "truncated PNG -> null, no exception");

        // ---- scanner -----------------------------------------------------------------------
        System.out.println("Stone scanner");
        int[] stonePx = noise(16, 16, 1, false);
        byte[] stone = PngPixels.encodeRgba(16, 16, stonePx);
        byte[] stoneReencoded = viaImageIO(16, 16, stonePx, BufferedImage.TYPE_INT_ARGB);
        check(!Arrays.equals(stone, stoneReencoded), "(setup) re-encoded stone differs byte-wise");

        MapSource vanilla = new MapSource().put("textures/block/stone.png", stone);
        String[] blocks = {"dirt", "grass_block_side", "oak_planks", "diamond_ore", "netherrack", "bedrock",
                "sand", "gravel", "obsidian", "end_stone", "diamond_block"};
        long seed = 100;
        for (String b : blocks) vanilla.put("textures/block/" + b + ".png", PngPixels.encodeRgba(16, 16, noise(16, 16, seed++, true)));
        vanilla.put("textures/block/water_still.png", PngPixels.encodeRgba(16, 64, noise(16, 64, seed++, true)));
        vanilla.put("textures/block/water_still.png.mcmeta", "{\"animation\":{}}".getBytes());
        vanilla.put("textures/item/diamond.png", PngPixels.encodeRgba(16, 16, noise(16, 16, seed++, true)));
        vanilla.put("textures/entity/zombie.png", PngPixels.encodeRgba(64, 64, noise(64, 64, seed++, true)));

        MapSource pack = new MapSource().put("textures/block/stone.png", stone);
        for (String b : new String[]{"dirt", "grass_block_side", "oak_planks", "diamond_ore", "netherrack", "bedrock",
                "sand", "gravel", "obsidian"}) pack.put("textures/block/" + b + ".png", stone);
        pack.put("textures/block/water_still.png", stone);
        pack.put("textures/block/water_still.png.mcmeta", "{\"animation\":{}}".getBytes());
        pack.put("textures/block/end_stone.png", stoneReencoded);                     // re-encoded copy
        byte[] magenta = PngPixels.solid(16, 0xFFFF00FF);
        pack.put("textures/block/diamond_block.png", magenta);                        // legit custom texture
        pack.put("textures/block/modded_thing.png", stone);                           // unknown to vanilla
        pack.put("textures/item/diamond.png", stone);
        pack.put("textures/entity/zombie.png", stone);

        StoneScanner scanner = StoneScanner.create(vanilla);
        RestoreSettings all = new RestoreSettings(true, true, true, true, true, false, 8);
        ScanResult r = scanner.scan(pack, all, null);
        check(r.analyzed() == 16, "analyzed counts every png (got " + r.analyzed() + ")");
        check(r.matched() == 13, "13 stone copies on non-stone paths (got " + r.matched() + ")");
        check(r.keptLegit() == 1, "stone.png itself kept (got " + r.keptLegit() + ")");
        check(r.unknown() == 1, "modded path left alone (got " + r.unknown() + ")");
        check(r.flagged(), "pack flagged");
        check(r.restoredPaths().contains("textures/block/dirt.png"), "dirt restored");
        check(r.restoredPaths().contains("textures/block/end_stone.png"), "re-encoded stone detected via pixel hash");
        check(!r.restoredPaths().contains("textures/block/stone.png"), "stone block not touched");
        check(!r.restoredPaths().contains("textures/block/diamond_block.png"), "legit custom texture preserved");
        check(!r.restoredPaths().contains("textures/block/modded_thing.png"), "unknown asset untouched");
        check(r.isHidden("textures/block/water_still.png.mcmeta"), "animation sidecar hidden with its png");
        check(!r.isHidden("textures/block/diamond_block.png.mcmeta"), "sidecar of a kept texture not hidden");

        ScanResult noItems = scanner.scan(pack, new RestoreSettings(true, false, true, true, true, false, 8), null);
        check(noItems.matched() == 12 && !noItems.restoredPaths().contains("textures/item/diamond.png"),
                "item toggle respected (matched " + noItems.matched() + ")");

        MapSource small = new MapSource().put("textures/block/stone.png", stone)
                .put("textures/block/dirt.png", stone).put("textures/block/sand.png", stone).put("textures/block/gravel.png", stone);
        ScanResult sm = scanner.scan(small, all, null);
        check(sm.matched() == 3 && !sm.flagged() && sm.restoredPaths().isEmpty(),
                "3 stone copies stay below the threshold -> not flagged");

        ScanResult forced = scanner.scan(pack, new RestoreSettings(true, true, true, true, true, true, 8), null);
        check(forced.flagged() && forced.forced(), "force mode flags the pack");
        check(forced.restoredPaths().contains("textures/block/diamond_block.png"), "force mode also overrides custom textures (by design)");
        check(!forced.restoredPaths().contains("textures/block/modded_thing.png"), "force mode still leaves assets vanilla lacks");

        MapSource clean = new MapSource().put("textures/block/dirt.png", PngPixels.encodeRgba(16, 16, noise(16, 16, 777, true)));
        ScanResult cl = scanner.scan(clean, all, null);
        check(!cl.flagged() && cl.matched() == 0, "ordinary pack is not flagged");

        MapSource huge = new MapSource().put("textures/block/dirt.png", new byte[StoneScanner.PACK_LIMIT + 1]);
        check(!scanner.scan(huge, all, null).flagged(), "oversized file skipped safely");

        // ---- MiniJson ----------------------------------------------------------------------
        System.out.println("MiniJson");
        Object a = MiniJson.parse("{\"b\": [1, 2.5, true, null], \"a\": {\"x\": \"\\u0041\\n\"}}".getBytes());
        Object b = MiniJson.parse("{\"a\":{\"x\":\"A\\n\"},\"b\":[1.0,2.5,true,null]}".getBytes());
        check(a.equals(b), "same content, other whitespace/key order/number format -> equal");
        check(!MiniJson.parse("[1,2]".getBytes()).equals(MiniJson.parse("[2,1]".getBytes())), "array order matters");
        boolean threw = false;
        try { MiniJson.parse("{\"a\": }".getBytes()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "invalid JSON rejected");
        threw = false;
        try { MiniJson.parse("{} x".getBytes()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "trailing data rejected");
        check(MiniJson.mentions(MiniJson.parse("{\"textures\":{\"all\":\"minecraft:block/stone\"}}".getBytes()),
                Set.of("minecraft:block/stone")), "mentions() finds nested strings");

        // ---- protected assets ----------------------------------------------------------------
        System.out.println("Protected (fire) assets");
        for (String p : new String[]{"textures/block/fire_0.png", "textures/block/fire_1.png", "textures/block/soul_fire_0.png",
                "textures/block/campfire_log_lit.png", "textures/block/campfire_fire.png", "textures/block/soul_campfire_fire.png",
                "textures/block/white_candle_lit.png", "blockstates/red_candle_cake.json", "textures/particle/flame.png",
                "textures/particle/big_smoke_0.png", "models/block/fire_floor0.json", "models/block/fire_side_alt1.json",
                "models/block/fire_up0.json", "textures/block/redstone_torch.png", "blockstates/lantern.json",
                "textures/block/campfire_log_lit.png.mcmeta", "textures/block/soul_fire_0.png.mcmeta"}) {
            check(ProtectedAssets.isProtected(p), "protected: " + p);
        }
        for (String p : new String[]{"textures/block/fire_coral_block.png", "textures/block/smoker_front.png",
                "textures/block/sea_lantern.png", "textures/block/dead_fire_coral_block.png", "textures/block/torchflower.png",
                "textures/block/furnace_front_on.png", "textures/block/magma.png", "textures/block/dirt.png",
                "blockstates/fire_coral.json"}) {
            check(!ProtectedAssets.isProtected(p), "NOT protected: " + p);
        }

        // ---- models / blockstates / plants ---------------------------------------------------
        System.out.println("Models, blockstates, grass");
        MapSource v2 = new MapSource();
        v2.files.putAll(vanilla.files);
        MapSource p2 = new MapSource();
        p2.files.putAll(pack.files);
        for (String t : new String[]{"red_sand", "fire_0", "campfire_log_lit", "short_grass", "tall_grass_top", "tall_grass_bottom"})
            v2.put("textures/block/" + t + ".png", PngPixels.encodeRgba(16, 16, noise(16, 16, seed++, true)));
        p2.put("textures/block/red_sand.png", stone);
        p2.put("textures/block/fire_0.png", stone);                 // protected: must stay as the pack has it
        p2.put("textures/block/campfire_log_lit.png", stone);       // protected
        int[] clear = new int[256];
        p2.put("textures/block/short_grass.png", PngPixels.encodeRgba(16, 16, clear));   // invisible plant
        p2.put("textures/block/tall_grass_top.png", PngPixels.encodeRgba(16, 16, clear));
        p2.put("textures/block/tall_grass_bottom.png", v2.files.get("textures/block/tall_grass_bottom.png")); // untouched

        String rot4 = "{\"variants\":{\"\":[{\"model\":\"minecraft:block/%s\"},{\"model\":\"minecraft:block/%s\",\"y\":90},"
                + "{\"model\":\"minecraft:block/%s\",\"y\":180},{\"model\":\"minecraft:block/%s\",\"y\":270}]}}";
        for (String n : new String[]{"dirt", "gravel", "stone", "oak_planks"})
            v2.put("blockstates/" + n + ".json", String.format(rot4, n, n, n, n).getBytes());
        v2.put("blockstates/grass_block.json", "{\"variants\":{\"snowy=false\":[{\"model\":\"minecraft:block/grass_block\"},{\"model\":\"minecraft:block/grass_block\",\"y\":90}]}}".getBytes());
        for (String n : new String[]{"short_grass", "tall_grass_top", "tall_grass_bottom"})
            v2.put("models/block/" + n + ".json", ("{\"parent\":\"minecraft:block/tinted_cross\",\"textures\":{\"cross\":\"minecraft:block/" + n + "\"}}").getBytes());
        v2.put("blockstates/short_grass.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/short_grass\"}}}".getBytes());
        v2.put("blockstates/tall_grass.json", "{\"variants\":{\"half=lower\":{\"model\":\"minecraft:block/tall_grass_bottom\"},\"half=upper\":{\"model\":\"minecraft:block/tall_grass_top\"}}}".getBytes());
        v2.put("models/block/red_sand.json", "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"minecraft:block/red_sand\"}}".getBytes());
        v2.put("models/block/oak_slab.json", "{\"parent\":\"minecraft:block/slab\",\"textures\":{\"bottom\":\"minecraft:block/oak_planks\"}}".getBytes());
        v2.put("blockstates/fire.json", "{\"multipart\":[]}".getBytes());
        v2.put("blockstates/campfire.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/campfire\"}}}".getBytes());
        v2.put("particles/flame.json", "{\"textures\":[\"minecraft:flame\"]}".getBytes());

        // pack side
        p2.put("blockstates/dirt.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/dirt\"}}}".getBytes());          // forced 0 degrees
        p2.put("blockstates/grass_block.json", "{\"variants\":{\"snowy=false\":{\"model\":\"minecraft:block/grass_block\"}}}".getBytes());
        p2.put("blockstates/gravel.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}".getBytes());           // points at stone
        p2.put("blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}".getBytes());
        p2.put("models/block/red_sand.json", ("{\"textures\":{\"all\":\"minecraft:block/stone\",\"particle\":\"minecraft:block/red_sand\"},"
                + "\"elements\":[{\"from\":[0,16,0],\"to\":[16,32,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}]}").getBytes()); // floating geometry
        p2.put("blockstates/short_grass.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stonerestore_empty\"}}}".getBytes());
        p2.put("blockstates/tall_grass.json", "{\"variants\":{\"half=lower\":{\"model\":\"minecraft:block/stonerestore_empty\"},\"half=upper\":{\"model\":\"minecraft:block/stonerestore_empty\"}}}".getBytes());
        p2.put("models/block/tall_grass_top.json", "{\"elements\":[]}".getBytes());
        p2.put("models/block/tall_grass_bottom.json", v2.files.get("models/block/tall_grass_bottom.json"));
        p2.put("models/block/stonerestore_empty.json", "{\"elements\":[]}".getBytes());                       // custom, unknown to vanilla
        // same content as vanilla, different formatting/key order -> must NOT count as modified
        p2.put("blockstates/oak_planks.json", ("{ \"variants\" : { \"\" : [ {\"model\" : \"minecraft:block/oak_planks\"},"
                + " {\"model\":\"minecraft:block/oak_planks\", \"y\":90}, {\"model\":\"minecraft:block/oak_planks\",\"y\":180},"
                + " {\"model\":\"minecraft:block/oak_planks\", \"y\":270} ] } }").getBytes());
        p2.put("blockstates/fire.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}".getBytes()); // protected
        p2.put("blockstates/campfire.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}".getBytes());
        p2.put("particles/flame.json", "{\"textures\":[\"minecraft:block/stone\"]}".getBytes());
        p2.put("models/block/oak_slab.json", "{\"parent\":\"minecraft:block/slab\",\"textures\":{\"bottom\":\"minecraft:block/custom_planks\"}}".getBytes()); // legit custom

        StoneScanner sc2 = StoneScanner.create(v2);
        ScanResult m = sc2.scan(p2, all, null);
        check(m.flagged(), "model fixture: pack flagged");
        check(m.restoredPaths().contains("textures/block/short_grass.png") && m.restoredPaths().contains("textures/block/tall_grass_top.png"),
                "invisible short/tall grass textures restored");
        check(m.restored().get("textures/block/short_grass.png").reason().contains("transparent"), "...and reported as transparent");
        check(!m.restoredPaths().contains("textures/block/tall_grass_bottom.png"), "unchanged grass texture not touched");
        check(!m.restoredPaths().contains("textures/block/fire_0.png") && !m.restoredPaths().contains("textures/block/campfire_log_lit.png"),
                "fire textures never touched");
        for (String need : new String[]{"blockstates/dirt.json", "blockstates/grass_block.json", "blockstates/gravel.json",
                "blockstates/short_grass.json", "blockstates/tall_grass.json", "blockstates/stone.json",
                "models/block/red_sand.json", "models/block/tall_grass_top.json"})
            check(m.restoredPaths().contains(need), "restored: " + need);
        for (String keep : new String[]{"blockstates/oak_planks.json", "blockstates/fire.json", "blockstates/campfire.json",
                "models/block/oak_slab.json", "models/block/stonerestore_empty.json", "models/block/tall_grass_bottom.json",
                "particles/flame.json"})
            check(!m.restoredPaths().contains(keep), "kept: " + keep);
        check(m.restoredModels() == 8, "8 models/blockstates restored (got " + m.restoredModels() + ")");
        check(m.protectedSkipped() == 5, "5 fire assets skipped (got " + m.protectedSkipped() + ")");
        check(m.restored().get("blockstates/dirt.json").packHash() != null, "pack hash recorded for verification");

        ScanResult noBlocks = sc2.scan(p2, new RestoreSettings(false, true, true, true, true, false, 8), null);
        check(noBlocks.restoredModels() == 0 && !noBlocks.restoredPaths().contains("blockstates/dirt.json"), "block toggle off -> no block models touched");

        MapSource p3 = new MapSource().put("textures/block/dirt.png", stone).put("textures/block/sand.png", stone)
                .put("textures/block/short_grass.png", PngPixels.encodeRgba(16, 16, clear))
                .put("blockstates/short_grass.json", p2.files.get("blockstates/short_grass.json"));
        v2.put("textures/block/sand.png", PngPixels.encodeRgba(16, 16, noise(16, 16, 4242, true)));
        ScanResult weak = sc2.scan(p3, all, null);
        check(!weak.flagged() && weak.restoredPaths().isEmpty(), "pack below threshold: nothing restored, even invisible grass");

        ScanResult mf = sc2.scan(p2, new RestoreSettings(true, true, true, true, true, true, 8), null);
        check(mf.restoredPaths().contains("models/block/oak_slab.json"), "force mode restores any differing model");
        check(!mf.restoredPaths().contains("blockstates/fire.json") && !mf.restoredPaths().contains("textures/block/fire_0.png"), "force mode still skips fire");
        check(!mf.restoredPaths().contains("blockstates/oak_planks.json"), "force mode ignores formatting-only differences");

        // ---- grey stone, no green tint (leaves, grass colour map) -----------------------------
        System.out.println("Tint handling");
        check(MiniJson.countKey(MiniJson.parse("{\"elements\":[{\"faces\":{\"up\":{\"tintindex\":0},\"down\":{\"tintindex\":0}}}]}".getBytes()), "tintindex") == 2, "countKey counts nested keys");
        MapSource v3 = new MapSource();
        v3.files.putAll(v2.files);
        MapSource p4 = new MapSource();
        p4.files.putAll(p2.files);
        v3.put("textures/block/oak_leaves.png", PngPixels.encodeRgba(16, 16, noise(16, 16, 31337, true)));
        p4.put("textures/block/oak_leaves.png", stone);                                  // leaves become stone too
        String tinted = "{\"parent\":\"minecraft:block/block\",\"elements\":[{\"faces\":{\"up\":{\"texture\":\"#all\",\"tintindex\":0},\"down\":{\"texture\":\"#all\",\"tintindex\":0}}}]}";
        String untinted = "{\"parent\":\"minecraft:block/block\",\"elements\":[{\"faces\":{\"up\":{\"texture\":\"#all\"},\"down\":{\"texture\":\"#all\"}}}]}";
        v3.put("models/block/leaves.json", tinted.getBytes());
        p4.put("models/block/leaves.json", untinted.getBytes());                         // tint removed, no mention of stone
        v3.put("models/block/vine.json", tinted.getBytes());
        p4.put("models/block/vine.json", tinted.replace("minecraft:block/block", "minecraft:block/other").getBytes()); // differs, tint kept
        byte[] vanillaColormap = PngPixels.encodeRgba(16, 16, noise(16, 16, 555, false));
        v3.put("textures/colormap/grass.png", vanillaColormap);
        p4.put("textures/colormap/grass.png", PngPixels.solid(16, 0xFFFFFFFF));          // neutral colour map
        v3.put("textures/colormap/foliage.png", vanillaColormap);
        p4.put("textures/colormap/foliage.png", vanillaColormap);                        // untouched
        ScanResult t = StoneScanner.create(v3).scan(p4, all, null);
        check(t.restoredPaths().contains("textures/block/oak_leaves.png"), "leaves stone copy restored like any other block");
        check(t.restoredPaths().contains("models/block/leaves.json"), "shared leaves model with tint removed is restored");
        check(t.restored().get("models/block/leaves.json").reason().contains("tint"), "...for the right reason");
        check(!t.restoredPaths().contains("models/block/vine.json"), "model that differs but keeps its tint is not touched");
        check(t.restoredPaths().contains("textures/colormap/grass.png"), "neutral grass colour map restored");
        check(!t.restoredPaths().contains("textures/colormap/foliage.png"), "unchanged colour map not touched");

        // ---- thumbnails ------------------------------------------------------------------------
        System.out.println("Ghost grid icon");
        byte[] icon = GhostGridIcon.procedural(128);
        PngPixels.Image iconImg = PngPixels.decode(icon);
        check(iconImg != null && iconImg.width() == 128 && iconImg.height() == 128, "procedural icon is a 128x128 PNG");
        check(Arrays.equals(icon, GhostGridIcon.procedural(128)), "icon generation is deterministic");
        check(iconImg != null && (iconImg.argb()[0] & 0xFFFFFF) == 0x5AD1FF, "outline pixel present");
        check(iconImg != null && iconImg.argb()[64 * 128 + 64] != iconImg.argb()[64 * 128 + 72], "grid line pixel differs from tile pixel");
        byte[] packIcon = GhostGridIcon.fromTile(PngPixels.decode(stone));
        PngPixels.Image packImg = PngPixels.decode(packIcon);
        check(packImg != null && packImg.width() == 256 && packImg.height() == 256, "pack thumbnail from a 16px tile is 256x256");
        boolean threwIcon = false;
        try { GhostGridIcon.fromTile(new PngPixels.Image(2, 3, new int[6])); } catch (IllegalArgumentException e) { threwIcon = true; }
        check(threwIcon, "non-square tile rejected");

        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** pixelHash/decoder keep alpha-0 pixels' RGB; decode returns raw values, so compare as-is. */
    static int[] normalise(int[] argb) { return argb; }
}
