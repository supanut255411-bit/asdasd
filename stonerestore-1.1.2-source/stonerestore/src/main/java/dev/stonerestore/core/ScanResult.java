package dev.stonerestore.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of scanning one resource pack.
 *
 * @param analyzed        png textures examined (only categories that are switched on)
 * @param analyzedModels  blockstate/model/particle json files examined
 * @param matched         textures that are a copy of vanilla stone although vanilla's own texture at that
 *                        path is something else (force mode: textures with a vanilla counterpart)
 * @param keptLegit       stone copies left alone because vanilla's texture at that path IS stone
 * @param unknown         stone copies / json files at paths vanilla does not have (left alone)
 * @param protectedSkipped fire / flame / candle / campfire assets skipped on purpose
 * @param flagged         pack counts as a stone-replacement pack (>= minReplacements stone copies, or force)
 * @param forced          scan ran in force mode
 * @param restored        asset path -> why it is restored and the hash of the pack's version (for verification)
 * @param failures        human readable "identifier: reason" entries
 */
public record ScanResult(int analyzed, int analyzedModels, int matched, int keptLegit, int unknown,
                         int protectedSkipped, boolean flagged, boolean forced,
                         Map<String, Restore> restored, List<String> failures) {

    /** @param packHash SHA-256 of the pack's file, or null if it was too large to hash */
    public record Restore(String reason, byte[] packHash) {}

    public static final ScanResult NONE =
            new ScanResult(0, 0, 0, 0, 0, 0, false, false, Map.of(), List.of());

    public static ScanResult failed(String reason) {
        return new ScanResult(0, 0, 0, 0, 0, 0, false, false, Map.of(), List.of(reason));
    }

    public Set<String> restoredPaths() {
        return Collections.unmodifiableSet(restored.keySet());
    }

    public int restoredTextures() {
        int n = 0;
        for (String p : restored.keySet()) if (p.endsWith(".png")) n++;
        return n;
    }

    public int restoredModels() {
        return restored.size() - restoredTextures();
    }

    /** True for a restored file and for its animation sidecar ({@code <png>.mcmeta}). */
    public boolean isHidden(String path) {
        if (restored.isEmpty()) return false;
        if (restored.containsKey(path)) return true;
        return path.endsWith(".mcmeta")
                && restored.containsKey(path.substring(0, path.length() - ".mcmeta".length()));
    }
}
