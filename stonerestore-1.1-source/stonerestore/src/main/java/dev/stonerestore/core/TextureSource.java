package dev.stonerestore.core;

import java.io.IOException;
import java.util.Collection;

/**
 * Minimal view of "a place assets live" (a resource pack, or the vanilla assets).
 * Paths are relative to the {@code minecraft} namespace, e.g. {@code textures/block/dirt.png} or
 * {@code blockstates/dirt.json}. Kept free of Minecraft types so the detection logic can be tested stand-alone.
 */
public interface TextureSource {

    /** Every file below {@code textures/}, {@code blockstates/}, {@code models/} and {@code particles/}. */
    Collection<String> listAssets() throws IOException;

    /** File content, or {@code null} if the file does not exist or is larger than {@code maxBytes}. */
    byte[] read(String path, int maxBytes) throws IOException;

    /** Cheap existence check. */
    boolean exists(String path);
}
