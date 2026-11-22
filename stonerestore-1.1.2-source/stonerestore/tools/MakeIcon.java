import dev.stonerestore.core.GhostGridIcon;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regenerates the mod icon (128x128 ghost grid):
 *   javac -d out src/main/java/dev/stonerestore/core/*.java tools/MakeIcon.java
 *   java -cp out MakeIcon src/main/resources/assets/stonerestore/icon.png
 */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length > 0 ? args[0] : "icon.png");
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, GhostGridIcon.procedural(128));
        System.out.println("wrote " + target + " (" + Files.size(target) + " bytes)");
    }
}
