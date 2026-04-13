package ixdar.cli;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
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
 * Headless grid renderer CLI for VLM tournament consumption.
 *
 * Usage: java -XstartOnFirstThread -cp ... ixdar.cli.RenderGrid --inputs dir/ --output grid.png --cols N --cell-size N
 *
 * Options:
 *   --inputs <dir>     Input directory containing .dsl and .obj files
 *   --output <file>    Output grid PNG file path
 *   --cols <N>         Number of columns in the grid
 *   --cell-size <N>    Size of each cell in pixels (e.g., 512)
 *   --cell-padding <N> Padding between cells in pixels (default: 10)
 *   --label-size <N>   Font size for cell labels (default: 24)
 *
 * Renders all .dsl and .obj files in the input directory to a labeled grid image.
 * Each cell is auto-framed with consistent camera angle.
 */
public class RenderGrid {

    private static final int DEFAULT_CELL_SIZE = 512;
    private static final int DEFAULT_CELL_PADDING = 10;
    private static final int DEFAULT_LABEL_SIZE = 24;
    private static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    private static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);

    public static void main(String[] args) {
        // Parse arguments
        String inputsDir = null;
        String outputPath = null;
        int cols = 0;
        int cellSize = DEFAULT_CELL_SIZE;
        int cellPadding = DEFAULT_CELL_PADDING;
        int labelSize = DEFAULT_LABEL_SIZE;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--inputs" -> inputsDir = args[++i];
                case "--output" -> outputPath = args[++i];
                case "--cols" -> cols = Integer.parseInt(args[++i]);
                case "--cell-size" -> cellSize = Integer.parseInt(args[++i]);
                case "--cell-padding" -> cellPadding = Integer.parseInt(args[++i]);
                case "--label-size" -> labelSize = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        // Validate arguments
        if (inputsDir == null) {
            System.err.println("Error: --inputs is required");
            printUsage();
            System.exit(1);
        }
        if (outputPath == null) {
            System.err.println("Error: --output is required");
            printUsage();
            System.exit(1);
        }
        if (cols <= 0) {
            System.err.println("Error: --cols must be positive");
            printUsage();
            System.exit(1);
        }

        File inputDir = new File(inputsDir);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            System.err.println("Error: --inputs directory does not exist: " + inputsDir);
            System.exit(1);
        }

        System.out.println("[RenderGrid] Inputs: " + inputsDir);
        System.out.println("[RenderGrid] Output: " + outputPath);
        System.out.println("[RenderGrid] Grid: " + cols + " columns, " + cellSize + "x" + cellSize + " cells");

        // Find all renderable files
        List<File> renderableFiles = new ArrayList<>();
        File[] files = inputDir.listFiles((dir, name) -> 
            name.toLowerCase().endsWith(".dsl") || name.toLowerCase().endsWith(".obj"));
        
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    renderableFiles.add(f);
                }
            }
        }

        if (renderableFiles.isEmpty()) {
            System.err.println("Error: No .dsl or .obj files found in " + inputsDir);
            System.exit(1);
        }

        System.out.println("[RenderGrid] Found " + renderableFiles.size() + " files to render");

        // Calculate grid dimensions
        int rows = (int) Math.ceil((double) renderableFiles.size() / cols);
        int gridWidth = cols * cellSize + (cols - 1) * cellPadding;
        int gridHeight = rows * cellSize + (rows - 1) * cellPadding;

        // Add label area at bottom of each cell
        int labelHeight = labelSize + 10;
        gridHeight += labelHeight;

        System.out.println("[RenderGrid] Grid dimensions: " + gridWidth + "x" + gridHeight);

        // Initialize headless platform with max cell size for rendering
        HeadlessPlatform platform = new HeadlessPlatform(cellSize, cellSize);
        int platformId = 1;
        platform.setPlatformID(platformId);

        try {
            // Initialize Platforms singleton
            HeadlessGL gl = platform.getGL();
            gl.setPlatformID(platformId);
            Platforms.init(platform, gl);

            // Create output image
            BufferedImage gridImage = new BufferedImage(gridWidth, gridHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = gridImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, gridWidth, gridHeight);
            g2d.dispose();

            // Process each file
            for (int i = 0; i < renderableFiles.size(); i++) {
                File file = renderableFiles.get(i);
                int cellRow = i / cols;
                int cellCol = i % cols;
                
                int cellX = cellCol * (cellSize + cellPadding);
                int cellY = cellRow * (cellSize + cellPadding);

                System.out.println("[RenderGrid] Rendering cell " + (i + 1) + "/" + renderableFiles.size() + 
                                   ": " + file.getName() + " at (" + cellX + ", " + cellY + ")");

                // Render the file
                BufferedImage cellImage = renderCell(platform, gl, file);
                
                if (cellImage != null) {
                    // Composite cell into grid
                    Graphics2D gridG2d = gridImage.createGraphics();
                    gridG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    gridG2d.drawImage(cellImage, cellX, cellY, null);
                    gridG2d.dispose();

                    // Add label
                    addLabel(gridImage, cellX, cellY, cellSize, i + 1, labelSize);
                }
            }

            // Write output
            File outputFile = new File(outputPath);
            File parent = outputFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            
            javax.imageio.ImageIO.write(gridImage, "PNG", outputFile);
            System.out.println("[RenderGrid] Done: " + outputPath);

        } catch (Exception e) {
            System.err.println("[RenderGrid] ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            platform.shutdown();
        }
    }

    /**
     * Render a single DSL or OBJ file to a BufferedImage.
     */
    private static BufferedImage renderCell(HeadlessPlatform platform, HeadlessGL gl, File file) throws Exception {
        String lowerName = file.getName().toLowerCase();
        MeshTopology mesh;

        if (lowerName.endsWith(".dsl")) {
            mesh = renderDsl(platform, file);
        } else if (lowerName.endsWith(".obj")) {
            mesh = renderObj(file);
        } else {
            System.err.println("[RenderGrid] Unsupported file format: " + file.getName());
            return null;
        }

        if (mesh == null) {
            System.err.println("[RenderGrid] Failed to load mesh from: " + file.getName());
            return null;
        }

        int width = platform.getWindowWidth();
        int height = platform.getWindowHeight();

        // Enable depth testing
        gl.enable(gl.DEPTH_TEST());

        // Set viewport and clear
        gl.viewport(0, 0, width, height);
        gl.clearColor(0.12f, 0.12f, 0.14f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());

        // Auto-frame camera
        Vector3f meshCenter = mesh.center(new Vector3f());
        float distance = Math.max(1.5f, mesh.radius() * 2.5f);
        float camX = meshCenter.x + (float) (Math.sin(CAMERA_AZIMUTH) * Math.cos(CAMERA_ELEVATION) * distance);
        float camY = meshCenter.y + (float) (Math.sin(CAMERA_ELEVATION) * distance);
        float camZ = meshCenter.z + (float) (Math.cos(CAMERA_AZIMUTH) * Math.cos(CAMERA_ELEVATION) * distance);

        Camera3D camera = new Camera3D(new Vector3f(camX, camY, camZ), 0f, 0f, null);
        camera.target.set(meshCenter);
        camera.fov = 45f;
        camera.updateViewFirstPerson();

        platform.setFrameBufferSize(width, height);

        // Upload and render mesh
        HalfEdgeMeshRuntime meshRuntime = new HalfEdgeMeshRuntime();
        meshRuntime.upload(mesh);
        meshRuntime.render(camera);

        // Capture pixels
        int[] pixels = gl.readPixels(
            0, 0, width, height,
            gl.RGBA(),
            gl.UNSIGNED_BYTE(),
            0
        );

        // Create BufferedImage (flip Y for AWT)
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                int a = (pixel >> 24) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // Convert to RGB (ignore alpha, use white for transparent)
                int rgb = ((a > 0) ? (r << 16 | g << 8 | b) : 0xFFFFFF);
                image.setRGB(x, height - 1 - y, rgb);
            }
        }

        meshRuntime.dispose();
        return image;
    }

    /**
     * Render a DSL file.
     */
    private static MeshTopology renderDsl(HeadlessPlatform platform, File dslFile) throws Exception {
        String dslCode = Files.readString(Paths.get(dslFile.getAbsolutePath()));
        System.out.println("[RenderGrid] Parsing DSL: " + dslFile.getName() + " (" + dslCode.length() + " chars)");

        PythonLexer lexer = new PythonLexer(dslCode);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();
        System.out.println("[RenderGrid] Parsed " + ast.size() + " nodes");

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        String finalNodeId = ast.get(ast.size() - 1).id;
        System.out.println("[RenderGrid] Final node: " + finalNodeId);

        MeshTopology mesh = executeWithPortFallback(runtime, ast, finalNodeId, null);
        
        if (mesh == null) {
            throw new Exception("No mesh produced from DSL graph");
        }

        System.out.println("[RenderGrid] DSL mesh: verts=" + mesh.vertexCount() + " faces=" + mesh.faceCount() + 
                           " radius=" + String.format("%.3f", mesh.radius()));
        
        return mesh;
    }

    /**
     * Load an OBJ file.
     */
    private static MeshTopology renderObj(File objFile) throws Exception {
        System.out.println("[RenderGrid] Loading OBJ: " + objFile.getName());
        ArrayMesh mesh = MeshLoader.load(objFile.getAbsolutePath());
        System.out.println("[RenderGrid] OBJ mesh: verts=" + mesh.vertexCount() + " faces=" + mesh.faceCount() + 
                           " radius=" + String.format("%.3f", mesh.radius()));
        return mesh;
    }

    /**
     * Try the specified port, or auto-detect between "mesh" and "geometry".
     */
    private static MeshTopology executeWithPortFallback(
            NodeGraphRuntime runtime,
            List<PythonParser.ParsedNode> ast,
            String finalNodeId,
            String explicitPort) throws Exception {

        if (explicitPort != null) {
            return runtime.executeGraphToMesh(ast, finalNodeId, explicitPort);
        }

        // Try "mesh" first (most common), fall back to "geometry"
        try {
            MeshTopology result = runtime.executeGraphToMesh(ast, finalNodeId, "mesh");
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            // Port "mesh" not found — try geometry
        }

        return runtime.executeGraphToMesh(ast, finalNodeId, "geometry");
    }

    /**
     * Add a numbered label to the bottom of a cell.
     */
    private static void addLabel(BufferedImage image, int cellX, int cellY, int cellWidth, int labelNum, int fontSize) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        g2d.setFont(font);
        
        FontMetrics fm = g2d.getFontMetrics();
        String label = String.valueOf(labelNum);
        int labelWidth = fm.stringWidth(label);
        
        // White background for label
        int padding = 4;
        int bgX = cellX + (cellWidth - labelWidth) / 2 - padding;
        int bgY = cellY + cellWidth + padding; // Below the cell
        int bgWidth = labelWidth + 2 * padding;
        int bgHeight = fontSize + 2 * padding;
        
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRoundRect(bgX, bgY, bgWidth, bgHeight, 5, 5);
        
        // White text
        g2d.setColor(Color.WHITE);
        g2d.drawString(label, bgX + padding, bgY + fontSize);
        
        g2d.dispose();
    }

    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("Usage: java -XstartOnFirstThread -cp ... ixdar.cli.RenderGrid [options]");
        System.out.println("");
        System.out.println("Options:");
        System.out.println("  --inputs <dir>     Input directory containing .dsl and .obj files (required)");
        System.out.println("  --output <file>    Output grid PNG file path (required)");
        System.out.println("  --cols <N>         Number of columns in the grid (required)");
        System.out.println("  --cell-size <N>    Size of each cell in pixels (default: 512)");
        System.out.println("  --cell-padding <N> Padding between cells in pixels (default: 10)");
        System.out.println("  --label-size <N>   Font size for cell labels (default: 24)");
    }
}
