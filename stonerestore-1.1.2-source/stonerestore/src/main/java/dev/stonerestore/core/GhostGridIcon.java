package dev.stonerestore.core;

import java.util.Random;

/**
 * Draws the thumbnail used for the mod and for the generated test packs: a 16 x 16 grid of translucent stone
 * tiles floating on a dark background, fading towards the edges like a "ghost" plane.
 *
 * <p>{@link #fromTile} tiles any square tile (the generated packs pass the player's own vanilla stone texture,
 * so nothing from Mojang is shipped inside the mod); {@link #procedural} builds its own grey noise tile and is
 * what the mod's static icon is made from.
 */
public final class GhostGridIcon {

    public static final int CELLS = 16;

    private static final int BG_TOP = 0x0F1218;
    private static final int BG_BOTTOM = 0x1B2230;
    private static final int GRID_LINE = 0xDADADA;
    private static final int OUTLINE = 0x5AD1FF;
    private static final int CENTER_ALPHA = 235;
    private static final int EDGE_ALPHA = 55;

    private GhostGridIcon() {}

    /** @param tile square tile; the result is (tile width * 16) pixels wide */
    public static byte[] fromTile(PngPixels.Image tile) {
        if (tile.width() != tile.height()) throw new IllegalArgumentException("tile must be square");
        int t = tile.width();
        int size = t * CELLS;
        int[] out = new int[size * size];
        int outline = Math.max(2, size / 96); // stays visible when Minecraft scales the thumbnail down to 64 px
        double center = (size - 1) / 2.0;
        double maxDist = center * Math.sqrt(2);
        for (int y = 0; y < size; y++) {
            int bg = mix(BG_TOP, BG_BOTTOM, y / (double) (size - 1));
            for (int x = 0; x < size; x++) {
                int tx = x % t;
                int ty = y % t;
                double d = Math.hypot(x - center, y - center) / maxDist;
                int alpha = (int) Math.round(CENTER_ALPHA - d * (CENTER_ALPHA - EDGE_ALPHA));
                int rgb;
                if (x < outline || y < outline || x >= size - outline || y >= size - outline) {
                    rgb = OUTLINE;
                } else if (tx == 0 || ty == 0) {
                    rgb = blend(bg, GRID_LINE, alpha / 255.0);
                } else {
                    rgb = blend(bg, tile.argb()[ty * t + tx] & 0xFFFFFF, alpha / 255.0);
                }
                out[y * size + x] = 0xFF000000 | rgb;
            }
        }
        return PngPixels.encodeRgba(size, size, out);
    }

    /** @param size multiple of 16, e.g. 128 (8 px per cell) */
    public static byte[] procedural(int size) {
        if (size < CELLS * 4 || size % CELLS != 0) throw new IllegalArgumentException("size must be a multiple of 16, at least 64");
        int cell = size / CELLS;
        Random random = new Random(1616);
        int[] px = new int[cell * cell];
        for (int i = 0; i < px.length; i++) {
            int g = 112 + random.nextInt(44);
            px[i] = 0xFF000000 | (g << 16) | (g << 8) | (g + 4);
        }
        return fromTile(new PngPixels.Image(cell, cell, px));
    }

    private static int mix(int a, int b, double f) {
        return blend(a, b, f);
    }

    private static int blend(int base, int top, double alpha) {
        int r = (int) Math.round(((base >> 16) & 255) * (1 - alpha) + ((top >> 16) & 255) * alpha);
        int g = (int) Math.round(((base >> 8) & 255) * (1 - alpha) + ((top >> 8) & 255) * alpha);
        int b = (int) Math.round((base & 255) * (1 - alpha) + (top & 255) * alpha);
        return (r << 16) | (g << 8) | b;
    }
}
