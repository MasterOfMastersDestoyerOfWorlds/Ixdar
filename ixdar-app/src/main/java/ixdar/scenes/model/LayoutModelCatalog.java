package ixdar.scenes.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The shared model list for the layout scenes: every input triangle mesh
 * ({@code *_in_tri.off}) under {@code test/resources/quadlayout}. Only the {@code _in_tri}
 * inputs are listed, since the {@code _out_quad} outputs are not valid pipeline inputs; paths
 * stay relative to the app working directory so they feed {@code MeshLoader.load} unchanged.
 */
public final class LayoutModelCatalog {

    /** Directory scanned for input meshes, relative to the app working directory. */
    public static final String QUADLAYOUT_DIR = "test/resources/quadlayout";

    /** Suffix identifying an input triangle mesh. */
    public static final String IN_TRI_SUFFIX = "_in_tri.off";

    private final List<ModelChoice> choices;

    /**
     * Build the catalog by scanning {@link #QUADLAYOUT_DIR}.
     */
    public LayoutModelCatalog() {
        this(Path.of(QUADLAYOUT_DIR));
    }

    /**
     * Build the catalog by scanning {@code root} for {@code *_in_tri.off} files.
     *
     * @param root directory to scan
     */
    public LayoutModelCatalog(Path root) {
        this.choices = scan(root);
    }

    /**
     * The discovered models, sorted by display name.
     *
     * @return the model list (never {@code null}; empty when the corpus is absent)
     */
    public List<ModelChoice> choices() {
        return choices;
    }

    /**
     * Resolve a user-typed token to a model: an exact display-name match first, then a
     * case-insensitive substring of the display name or path.
     *
     * @param token text typed at the terminal (e.g. {@code "fertility"})
     * @return the matching choice, or {@code null} if none matches
     */
    public ModelChoice resolve(String token) {
        for (ModelChoice choice : choices) {
            if (choice.displayName.equalsIgnoreCase(token)) {
                return choice;
            }
        }
        String lower = token.toLowerCase();
        for (ModelChoice choice : choices) {
            if (choice.displayName.toLowerCase().contains(lower)
                    || choice.path.toLowerCase().contains(lower)) {
                return choice;
            }
        }
        return null;
    }

    private static List<ModelChoice> scan(Path root) {
        List<ModelChoice> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(IN_TRI_SUFFIX))
                .forEach(path -> out.add(buildChoice(path)));
        } catch (IOException ignored) {
            return List.of();
        }
        out.sort(Comparator.comparing(choice -> choice.displayName));
        return List.copyOf(out);
    }

    private static ModelChoice buildChoice(Path file) {
        String fileName = file.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - IN_TRI_SUFFIX.length());
        Path parent = file.getParent();
        String figure = parent == null ? "" : parent.getFileName().toString();
        String display = figure.isEmpty() ? baseName : baseName + " (" + figure + ")";
        String path = file.toString().replace('\\', '/');
        return new ModelChoice(display, path);
    }
}
