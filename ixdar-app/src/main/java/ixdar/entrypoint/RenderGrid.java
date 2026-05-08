package ixdar.entrypoint;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.gl.headless.HeadlessGL;
import ixdar.platform.gl.headless.HeadlessPlatform;

/**
 * Headless CLI that renders multiple DSL files into a labeled grid image.
 * Designed for VLM tournament consumption (VOYAGE-3).
 *
 * Usage: java -XstartOnFirstThread -cp ... ixdar.entrypoint.RenderGrid [options]
 *
 * Options:
 *   --inputs &lt;dir&gt;       Directory containing .dsl files (required)
 *   --output &lt;path&gt;      Output PNG path (required)
 *   --reference &lt;file&gt;   Optional reference .dsl rendered in cell 0 with "REF" label
 *   --cols &lt;N&gt;           Grid columns (default: 4)
 *   --cell-size &lt;N&gt;      Cell width/height in pixels (default: 256)
 *   --labels &lt;a,b,c&gt;     Comma-separated labels (default: 1,2,3...)
 */
public class RenderGrid {
    public static final String DSL = ".dsl";
    public static final String X = "x";
    public static final int NUM_60 = 60;
    public static final int NUM_20 = 20;
    public static final int NUM_180 = 180;
    public static final int NUM_80 = 80;
    public static final int NUM_11 = 11;
    public static final int NUM_10 = 10;
    public static final int NUM_14 = 14;
    public static final float NUM_0_12 = 0.12f;
    public static final float NUM_0_14 = 0.14f;
    public static final float NUM_1_5 = 1.5f;
    public static final float NUM_2_5 = 2.5f;
    public static final float NUM_0 = 0f;
    public static final float NUM_45 = 45f;
    public static final int NUM_24 = 24;
    public static final int NUM_0xF = 0xFF;
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;

    private static final int DEFAULT_COLS = 4;
    private static final int DEFAULT_CELL_SIZE = 256;
    private static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    private static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    private static final int LABEL_HEIGHT = 28;
    private static final Color BG_COLOR = new Color(30, 30, 36);
    private static final Color LABEL_BG = new Color(20, 20, 24);
    private static final Color LABEL_FG = new Color(220, 220, 220);
    private static final Color REF_LABEL_FG = new Color(255, 180, 60);

    /**
     * CLI entry: render every {@code .dsl} file in {@code --inputs} (plus an
     * optional {@code --reference} cell) into a single labeled PNG grid suitable
     * for a VLM tournament prompt.
     *
     * @param args {@code --inputs <dir> --output <path> [--reference <file>]
     *             [--cols N] [--cell-size N] [--labels a,b,c] [--skill-dir path]}
     */
    public static void main(String[] args) {
        String inputDir = null;
        String outputPath = null;
        String referencePath = null;
        String skillDir = null;
        int cols = DEFAULT_COLS;
        int cellSize = DEFAULT_CELL_SIZE;
        String labelsArg = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--inputs" -> inputDir = args[++i];
                case "--output" -> outputPath = args[++i];
                case "--reference" -> referencePath = args[++i];
                case "--cols" -> cols = Integer.parseInt(args[++i]);
                case "--cell-size" -> cellSize = Integer.parseInt(args[++i]);
                case "--labels" -> labelsArg = args[++i];
                case "--skill-dir" -> skillDir = args[++i];
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
                }
            }
        }

        if (inputDir == null || outputPath == null) {
            System.err.println("Usage: RenderGrid --inputs <dir> --output <path> [options]");
            System.err.println("  --reference <file>   Reference DSL for cell 0");
            System.err.println("  --cols <N>           Grid columns (default: 4)");
            System.err.println("  --cell-size <N>      Cell size in pixels (default: 256)");
            System.err.println("  --labels <a,b,c>     Comma-separated labels");
            System.exit(1);
        }

        // Collect DSL files
        List<Path> dslFiles = new ArrayList<>();
        try (var stream = Files.list(Paths.get(inputDir))) {
            stream.filter(p -> p.toString().endsWith(DSL))
                    .sorted()
                    .forEach(dslFiles::add);
        } catch (IOException e) {
            System.err.println("[RenderGrid] Failed to list input directory: " + e.getMessage());
            System.exit(1);
        }

        if (dslFiles.isEmpty()) {
            System.err.println("[RenderGrid] No .dsl files found in " + inputDir);
            System.exit(1);
        }

        // Build render list: optional reference first, then candidates
        List<RenderEntry> entries = new ArrayList<>();
        if (referencePath != null) {
            entries.add(new RenderEntry(Paths.get(referencePath), "REF", true));
        }

        String[] customLabels = labelsArg != null ? labelsArg.split(",") : null;
        for (int i = 0; i < dslFiles.size(); i++) {
            String label = customLabels != null && i < customLabels.length
                    ? customLabels[i].trim()
                    : String.valueOf(i + 1);
            entries.add(new RenderEntry(dslFiles.get(i), label, false));
        }

        int totalCells = entries.size();
        int rows = (totalCells + cols - 1) / cols;
        int gridWidth = cols * cellSize;
        int gridHeight = rows * (cellSize + LABEL_HEIGHT);

        System.out.println("[RenderGrid] " + totalCells + " entries, " + cols + X + rows
                + " grid, " + gridWidth + X + gridHeight + "px");

        // Initialize headless GL once
        HeadlessPlatform platform = new HeadlessPlatform(cellSize, cellSize);
        platform.setPlatformID(1);

        try {
            HeadlessGL gl = platform.getGL();
            gl.setPlatformID(1);
            Platforms.init(platform, gl);
            gl.enable(gl.DEPTH_TEST());

            // Compose grid image
            BufferedImage grid = new BufferedImage(gridWidth, gridHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = grid.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(BG_COLOR);
            g2d.fillRect(0, 0, gridWidth, gridHeight);

            for (int idx = 0; idx < entries.size(); idx++) {
                RenderEntry entry = entries.get(idx);
                int col = idx % cols;
                int row = idx / cols;
                int cellX = col * cellSize;
                int cellY = row * (cellSize + LABEL_HEIGHT);

                BufferedImage cellImage = renderDslToImage(platform, gl, entry.path, cellSize, skillDir);
                if (cellImage != null) {
                    g2d.drawImage(cellImage, cellX, cellY, null);
                } else {
                    // Draw error placeholder
                    g2d.setColor(new Color(NUM_60, NUM_20, NUM_20));
                    g2d.fillRect(cellX, cellY, cellSize, cellSize);
                    g2d.setColor(new Color(NUM_180, NUM_80, NUM_80));
                    g2d.setFont(new Font(Font.MONOSPACED, Font.PLAIN, NUM_11));
                    g2d.drawString("ERROR", cellX + NUM_10, cellY + cellSize / 2);
                }

                // Draw label bar below cell
                g2d.setColor(LABEL_BG);
                g2d.fillRect(cellX, cellY + cellSize, cellSize, LABEL_HEIGHT);

                Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, NUM_14);
                g2d.setFont(labelFont);
                g2d.setColor(entry.isReference ? REF_LABEL_FG : LABEL_FG);
                FontMetrics fm = g2d.getFontMetrics();
                String displayLabel = entry.label;
                if (!entry.isReference) {
                    // Show filename without extension for candidates
                    String filename = entry.path.getFileName().toString();
                    if (filename.endsWith(DSL)) {
                        filename = filename.substring(0, filename.length() - DEFAULT_COLS);
                    }
                    displayLabel = entry.label + ": " + filename;
                }
                int textX = cellX + (cellSize - fm.stringWidth(displayLabel)) / 2;
                int textY = cellY + cellSize + (LABEL_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
                g2d.drawString(displayLabel, textX, textY);
            }

            g2d.dispose();

            // Write output
            File outFile = new File(outputPath);
            if (outFile.getParentFile() != null) {
                outFile.getParentFile().mkdirs();
            }
            ImageIO.write(grid, "png", outFile);
            System.out.println("[RenderGrid] Written: " + outputPath);

        } catch (Exception e) {
            System.err.println("[RenderGrid] ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            platform.shutdown();
        }
        // Force exit — on macOS with -XstartOnFirstThread, LWJGL/GLFW and AWT
        // (via ImageIO) leave non-daemon threads alive after main() returns, so
        // the JVM would otherwise linger indefinitely. Without this, every
        // voyage-batch grid render leaves a hung Java process behind.
        System.exit(0);
    }

    private static BufferedImage renderDslToImage(HeadlessPlatform platform, HeadlessGL gl,
            Path dslPath, int cellSize, String skillDir) {
        try {
            String dslCode = Files.readString(dslPath);
            PythonLexer lexer = new PythonLexer(dslCode);
            PythonParser parser = new PythonParser(lexer);
            List<PythonParser.ParsedNode> ast = parser.parseGraph();

            if (ast.isEmpty()) {
                System.err.println("[RenderGrid] Empty graph: " + dslPath);
                return null;
            }

            String finalNodeId = ast.get(ast.size() - 1).id;

            NodeGraphRuntime runtime = new NodeGraphRuntime();
            runtime.registerAllFromAnnotationRegistry();
            runtime.registerFunctionDefs(parser.functionDefs());

            if (skillDir != null) {
                var skillLib = new ixdar.geometry.mesh.graph.SkillLibrary();
                skillLib.loadDirectory(java.nio.file.Path.of(skillDir));
                skillLib.registerWith(runtime);
            }

            MeshTopology mesh = executeWithPortFallback(runtime, ast, finalNodeId);
            if (mesh == null || mesh.vertexCount() == 0) {
                System.err.println("[RenderGrid] No mesh: " + dslPath);
                return null;
            }

            // Set viewport and clear
            platform.setFrameBufferSize(cellSize, cellSize);
            gl.viewport(0, 0, cellSize, cellSize);
            gl.clearColor(NUM_0_12, NUM_0_12, NUM_0_14, 1.0f);
            gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());

            // Camera setup (same angles as RenderDsl)
            Vector3f meshCenter = mesh.center(new Vector3f());
            float distance = Math.max(NUM_1_5, mesh.radius() * NUM_2_5);
            float camX = meshCenter.x + (float) (Math.sin(CAMERA_AZIMUTH) * Math.cos(CAMERA_ELEVATION) * distance);
            float camY = meshCenter.y + (float) (Math.sin(CAMERA_ELEVATION) * distance);
            float camZ = meshCenter.z + (float) (Math.cos(CAMERA_AZIMUTH) * Math.cos(CAMERA_ELEVATION) * distance);

            Camera3D camera = new Camera3D(new Vector3f(camX, camY, camZ), NUM_0, NUM_0, null);
            camera.target.set(meshCenter);
            camera.fov = NUM_45;
            camera.updateViewFirstPerson();

            // Render
            HalfEdgeMeshRuntime meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(mesh);
            meshRuntime.render(camera);

            // Read pixels
            int[] pixels = gl.readPixels(0, 0, cellSize, cellSize,
                    gl.RGBA(), gl.UNSIGNED_BYTE(), 0);

            BufferedImage image = new BufferedImage(cellSize, cellSize, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < cellSize; y++) {
                for (int x = 0; x < cellSize; x++) {
                    int pixel = pixels[y * cellSize + x];
                    int a = (pixel >> NUM_24) & NUM_0xF;
                    int r = (pixel >> NUM_16) & NUM_0xF;
                    int g = (pixel >> NUM_8) & NUM_0xF;
                    int b = pixel & NUM_0xF;
                    int awtPixel = (a << NUM_24) | (r << NUM_16) | (g << NUM_8) | b;
                    image.setRGB(x, cellSize - 1 - y, awtPixel);
                }
            }

            meshRuntime.dispose();
            System.out.println("[RenderGrid] Rendered: " + dslPath.getFileName()
                    + " (" + mesh.vertexCount() + "v, " + mesh.faceCount() + "f)");
            return image;

        } catch (Exception e) {
            System.err.println("[RenderGrid] Failed: " + dslPath.getFileName() + " - " + e.getMessage());
            return null;
        }
    }

    private static MeshTopology executeWithPortFallback(
            NodeGraphRuntime runtime,
            List<PythonParser.ParsedNode> ast,
            String finalNodeId) throws Exception {
        try {
            MeshTopology result = runtime.executeGraphToMesh(ast, finalNodeId, "mesh");
            if (result != null) return result;
        } catch (Exception ignored) {
        }
        return runtime.executeGraphToMesh(ast, finalNodeId, "geometry");
    }

    private record RenderEntry(Path path, String label, boolean isReference) {}
}
