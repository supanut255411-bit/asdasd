package dev.stonerestore.core;

/** Which user-facing toggle an asset path belongs to. */
public enum TextureCategory {
    BLOCK, ITEM, ENTITY, GUI, PARTICLE, OTHER;

    public static TextureCategory of(String path) {
        if (path.startsWith("textures/block/") || path.startsWith("textures/colormap/")
                || path.startsWith("blockstates/") || path.startsWith("models/block/")) return BLOCK;
        if (path.startsWith("textures/item/") || path.startsWith("models/item/")) return ITEM;
        if (path.startsWith("textures/entity/")) return ENTITY;
        if (path.startsWith("textures/gui/")) return GUI;
        if (path.startsWith("textures/particle/") || path.startsWith("particles/")) return PARTICLE;
        return OTHER;
    }
}
