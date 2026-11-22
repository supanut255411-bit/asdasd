package dev.stonerestore.pack;

import java.util.ArrayList;
import java.util.List;

/** What one resource reload did. Every number comes from the scan and from the post-reload verification. */
public record ReloadReport(List<PackReport> packs, long millis) {

    /**
     * @param analyzed         textures examined in this pack
     * @param analyzedModels   blockstate/model/particle files examined
     * @param detected         stone copies found on non-stone vanilla paths
     * @param restoredTextures textures hidden AND verified to no longer resolve to the pack's replacement
     * @param restoredModels   models/blockstates/particle definitions, same verification
     * @param failures         "identifier: reason" lines (also written to latest.log)
     */
    public record PackReport(String packId, int analyzed, int analyzedModels, int detected, boolean flagged,
                             boolean forced, boolean applied, int restoredTextures, int restoredModels,
                             List<String> failures) {}

    public boolean anyFlagged() {
        return packs.stream().anyMatch(PackReport::flagged);
    }

    public boolean anyApplied() {
        return packs.stream().anyMatch(PackReport::applied);
    }

    public int totalRestoredTextures() {
        return packs.stream().mapToInt(PackReport::restoredTextures).sum();
    }

    public int totalRestoredModels() {
        return packs.stream().mapToInt(PackReport::restoredModels).sum();
    }

    /** Lines for the in-game chat (without the "[StoneRestore]" prefix, the engine adds it). */
    public List<String> chatLines() {
        List<String> lines = new ArrayList<>();
        int analyzed = 0;
        int analyzedModels = 0;
        int detected = 0;
        int failed = 0;
        boolean forced = false;
        for (PackReport p : packs) {
            if (!p.flagged()) continue;
            analyzed += p.analyzed();
            analyzedModels += p.analyzedModels();
            detected += p.detected();
            failed += p.failures().size();
            forced |= p.forced();
        }
        lines.add(forced ? "Force mode: restoring vanilla assets without detection."
                         : "Stone texture replacements detected.");
        lines.add(analyzed + " textures analyzed (" + analyzedModels + " models/blockstates).");
        if (anyApplied()) {
            lines.add(totalRestoredTextures() + " textures and " + totalRestoredModels() + " models/blockstates restored.");
            if (failed > 0) lines.add(failed + " problems - see logs/latest.log.");
            lines.add(failed > 0 ? "Restoration finished with problems." : "Restoration complete.");
        } else {
            lines.add(detected + " textures could be restored. Automatic Restoration is OFF - nothing was changed.");
        }
        return lines;
    }

    public List<String> logLines() {
        List<String> lines = new ArrayList<>();
        for (PackReport p : packs) {
            if (p.analyzed() == 0 && !p.flagged() && p.failures().isEmpty()) continue;
            lines.add(String.format("pack '%s': %d textures + %d models analyzed, %d stone copies, flagged=%s, applied=%s, "
                            + "%d textures + %d models restored, %d problems",
                    p.packId(), p.analyzed(), p.analyzedModels(), p.detected(), p.flagged(), p.applied(),
                    p.restoredTextures(), p.restoredModels(), p.failures().size()));
            for (String f : p.failures()) lines.add("  problem: " + f);
        }
        if (lines.isEmpty()) lines.add("no stone replacements found");
        lines.add("reload processed in " + millis + " ms");
        return lines;
    }
}
