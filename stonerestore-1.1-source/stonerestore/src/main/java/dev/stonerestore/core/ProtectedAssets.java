package dev.stonerestore.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Fire and flame related assets are never examined or restored: the visual pack leaves them vanilla, so
 * StoneRestore must not override anything the pack did or did not do there. Matching is done on the file's
 * base name so that e.g. {@code fire_coral_block}, {@code smoker_front} and {@code sea_lantern} (ordinary
 * solid blocks) are NOT protected.
 */
public final class ProtectedAssets {

    private static final List<Pattern> PATTERNS = List.of(
            // fire, soul_fire, fire_0, soul_fire_1, fire_floor0, fire_side_alt1, fire_up0 ...
            Pattern.compile("^(soul_)?fire(_\\d+|_(floor|side|side_alt|up)\\d*)?$"),
            Pattern.compile("(^|_)campfire(_|$)"),   // campfire, soul_campfire, campfire_log_lit, campfire_smoke
            Pattern.compile("(^|_)candle(_|$)"),     // candle, red_candle_lit, candle_cake, white_candle_cake_lit
            Pattern.compile("(^|_)flame(_|$)"),      // flame, small_flame, soul_fire_flame
            Pattern.compile("(^|_)smoke(_|$)"),      // campfire_smoke, big_smoke_3 (not smoker_*)
            Pattern.compile("(^|_)torch(_|$)"),
            Pattern.compile("^(soul_)?lantern(_|$)") // lantern, soul_lantern (not sea_lantern)
    );

    private ProtectedAssets() {}

    public static boolean isProtected(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        if (base.endsWith(".mcmeta")) base = base.substring(0, base.length() - ".mcmeta".length());
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        for (Pattern p : PATTERNS) {
            if (p.matcher(base).find()) return true;
        }
        return false;
    }
}
