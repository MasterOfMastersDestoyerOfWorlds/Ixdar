package ixdar.platform.file;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ixdar.platform.Compat;
import ixdar.platform.Platforms;

public class FileManagement {
    public static final String IX = ".ix";
    public static final String N = "\n";

    public static final String ASSET_REPO_ENV_VAR = "IXDAR_ASSET_REPO_ROOT";
    public static final String ASSET_REPO_PROP = "ixdar.asset.repo.root";
    public static final String DEFAULT_TEST_MODEL_FILE = "Hand.obj";

    public static final String SOLUTIONS_FOLDER = "./src/main/resources/solutions/";

    public static final String CACHE_FOLDER = "./test/cache/";

    /** The file naming the last point-set file opened, which the debug menu's Continue reopens. */
    public static String testFileCacheLocation = CACHE_FOLDER + "cache";

    /**
     * Locate the IxdarAssets checkout root, preferring the JVM property
     * {@code ixdar.asset.repo.root} over the env var {@code IXDAR_ASSET_REPO_ROOT}.
     *
     * @return absolute root path, or {@code null} if neither is set
     */
    public static String getAssetRepoRoot() {
        String propRoot = System.getProperty(ASSET_REPO_PROP);
        if (!Compat.isBlank(propRoot)) {
            return propRoot;
        }
        String root = System.getenv(ASSET_REPO_ENV_VAR);
        if (Compat.isBlank(root)) {
            return null;
        }
        return root;
    }

    /**
     * Resolve a path inside the IxdarAssets repo to an absolute filesystem path.
     *
     * @param relativeAssetPath path relative to the assets repo root
     * @throws IllegalStateException if the assets repo root has not been configured
     * @return absolute path produced by {@code Path.of(root, relativeAssetPath)}
     */
    public static String resolveAssetPath(String relativeAssetPath) {
        String root = getAssetRepoRoot();
        if (Compat.isBlank(root)) {
            throw new IllegalStateException(
                    "Missing asset repo root. Set either env var " + ASSET_REPO_ENV_VAR
                            + " or JVM property " + ASSET_REPO_PROP
                            + " (e.g. C:\\Code\\IxdarAssets).");
        }
        return Path.of(root, relativeAssetPath).toString();
    }

    /**
     * Load a file from the IxdarAssets repo via the current platform.
     *
     * @param relativeAssetPath path relative to the assets repo root
     * @throws IOException if the asset is missing or cannot be read
     * @return the file contents
     */
    public static TextFile loadAssetFile(String relativeAssetPath) throws IOException {
        String absolutePath = resolveAssetPath(relativeAssetPath);
        return Platforms.get().loadExternalFile(absolutePath);
    }

    /**
     * Resolve a solution-folder path for a logical test file name. Splits on {@code _} so that
     * {@code "myShape_v1"} maps under {@code solutions/myShape/}; appends {@code .ix} when missing.
     *
     * @param fileName base name (with or without {@code .ix} extension)
     * @return relative path under {@link #SOLUTIONS_FOLDER}
     */
    public static String getTestFile(String fileName) {
        String[] parts = fileName.split("_");
        if (fileName.contains(IX)) {
            return SOLUTIONS_FOLDER + parts[0].replace(IX, "") + "/" + fileName;
        }
        return SOLUTIONS_FOLDER + parts[0] + "/" + fileName + IX;
    }

    /**
     * Wrap {@link #getTestFile(String)} as a {@link TextFile} (no I/O performed).
     *
     * @param fileName base name forwarded to {@link #getTestFile(String)}
     * @return empty {@link TextFile} pointing at the resolved path
     */
    public static TextFile getFile(String fileName) {
        String path = getTestFile(fileName);
        return new TextFile(path);
    }

    /**
     * Logical scratch file at {@code "temp.ix"}; the {@code fileName} argument is currently
     * ignored.
     *
     * @param fileName intended name (unused)
     * @return temp {@link TextFile}
     */
    public static TextFile getTempFile(String fileName) {
        return new TextFile("temp", IX);
    }

    /**
     * Read the most recently used file path from the on-disk test cache.
     *
     * @return cached path string, or {@code ""} if the cache is missing or unreadable
     */
    public static String getTestFileCache() {
        File cache = new File(testFileCacheLocation);
        try (BufferedReader br = new BufferedReader(new FileReader(cache))) {
            String line = br.readLine();
            br.close();
            return line;
        } catch (Exception e) {

        }
        return "";
    }

    /**
     * Persist {@code cachedLocation} as the last-used file path. Blank strings are ignored;
     * I/O errors are logged and swallowed.
     *
     * @param cachedLocation path to remember; ignored if blank
     */
    public static void updateTestFileCache(String cachedLocation) {
        if (!Compat.isBlank(cachedLocation)) {
            File cache = new File(testFileCacheLocation);
            try (FileWriter fw = new FileWriter(cache)) {
                BufferedWriter out = new BufferedWriter(fw);
                out.write(cachedLocation);
                out.flush();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // New APIs returning logical text files instead of java.io.File for
    // cross-platform
    /**
     * Wrap a logical path in an empty {@link TextFile} (cross-platform alternative to
     * {@code java.io.File} since web has no filesystem).
     *
     * @param logicalPath path string
     * @return empty {@link TextFile} bound to that path
     */
    public static TextFile toTextFile(String logicalPath) {
        return new TextFile(logicalPath);
    }

    /**
     * Copy {@code src} to {@code dest} line-by-line (each terminated by {@code \n}); errors are
     * caught and printed.
     *
     * @param src source file
     * @param dest destination file (overwritten)
     */
    public static void copyFileContents(File src, File dest) {

        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(src);
            BufferedReader br = new BufferedReader(fr);
            while ((line = br.readLine()) != null) {
                lines.add(line + N);
            }
            fr.close();
            br.close();

            FileWriter fw = new FileWriter(dest);
            BufferedWriter out = new BufferedWriter(fw);
            for (String s : lines)
                out.write(s);
            out.flush();
            out.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Append a {@code //}-prefixed comment line to {@code path} and flush it to disk in append
     * mode.
     *
     * @param path target file (mutated in memory and on disk)
     * @param comment text after {@code //}
     */
    public static void appendComment(TextFile path, String comment) {
        try {
            path.getLines().add("// " + comment);
            Platforms.get().writeTextFile(path, true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Append a line to {@code path} and flush it to disk in append mode. Note: the line is
     * also currently prefixed with {@code //} due to shared implementation with
     * {@link #appendComment}.
     *
     * @param path target file
     * @param appLine line content
     */
    public static void appendLine(TextFile path, String appLine) {
        try {
            path.getLines().add("// " + appLine);
            Platforms.get().writeTextFile(path, true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

}
