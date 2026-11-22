package dev.stonerestore.pack;

import dev.stonerestore.core.TextureSource;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** {@link TextureSource} over a Minecraft {@link ResourcePack} (client assets, {@code minecraft} namespace). */
public final class PackTextureSource implements TextureSource {

    private static final String[] ROOTS = {"textures", "blockstates", "models", "particles"};

    private final ResourcePack pack;

    public PackTextureSource(ResourcePack pack) {
        this.pack = pack;
    }

    @Override
    public Collection<String> listAssets() {
        List<String> out = new ArrayList<>();
        for (String root : ROOTS) {
            pack.findResources(ResourceType.CLIENT_RESOURCES, "minecraft", root,
                    (id, supplier) -> out.add(id.getPath()));
        }
        return out;
    }

    @Override
    public byte[] read(String path, int maxBytes) throws IOException {
        InputSupplier<InputStream> supplier = pack.open(ResourceType.CLIENT_RESOURCES, Identifier.of("minecraft", path));
        if (supplier == null) return null;
        try (InputStream in = supplier.get()) {
            byte[] data = in.readNBytes(maxBytes + 1);
            return data.length > maxBytes ? null : data;
        }
    }

    @Override
    public boolean exists(String path) {
        return pack.open(ResourceType.CLIENT_RESOURCES, Identifier.of("minecraft", path)) != null;
    }
}
