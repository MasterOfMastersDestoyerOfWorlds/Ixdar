package ixdar.cli;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

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
 * Headless DSL renderer CLI.
 *
 * Usage: java -XstartOnFirstThread -cp ... ixdar.cli.RenderDsl &lt;file.dsl&gt; &lt;output.png&gt; [options]
 *
 * Options:
 *   --node &lt;id&gt;    Final node ID (default: last node in graph)
 *   --port &lt;name&gt;  Output port name (default: auto-detect "mesh" or "geometry")
 *   --width &lt;N&gt;    Image width (default: 512)
 *   --height &lt;N&gt;   Image height (default: 512)
 *
 * Renders a .dsl mesh file to a PNG image without requiring the desktop app.
 * Uses LWJGL GLFW invisible window for real OpenGL 3.3 context.
 */
public class RenderDsl {

    private static final int DEFAULT_WIDTH = 512;
    private static final int DEFAULT_HEIGHT = 512;
    private static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    private static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -XstartOnFirstThread -cp ... ixdar.cli.RenderDsl <file.dsl> <output.png> [options]");
            System.err.println("  --node <id>    Final node ID (default: last node in graph)");
            System.err.println("  --port <name>  Output port name (default: auto-detect)");
            System.err.println("  --width <N>    Image width (default: 512)");
            System.err.println("  --height <N>   Image height (default: 512)");
            System.exit(1);
        }

        String dslPath = args[0];
        String outputPath = args[1];
        String nodeId = null;
        String portName = null;
        int width = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;

        // Parse optional arguments
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--node" -> nodeId = args[++i];
                case "--port" -> portName = args[++i];
                case "--width" -> width = Integer.parseInt(args[++i]);
                case "--height" -> height = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
                }
            }
        }

        System.out.println("[RenderDsl] Rendering: " + dslPath + " -> " + outputPath);
        System.out.println("[RenderDsl] Resolution: " + width + "x" + height);

        HeadlessPlatform platform = new HeadlessPlatform(width, height);
        int platformId = 1;
        platform.setPlatformID(platformId);

        try {
            // Initialize Platforms singleton (HeadlessGL creates real OpenGL context)
            HeadlessGL gl = platform.getGL();
            gl.setPlatformID(platformId);
            Platforms.init(platform, gl);

            // Read DSL file from disk
            String dslCode = Files.readString(Paths.get(dslPath));
            System.out.println("[RenderDsl] Loaded DSL (" + dslCode.length() + " chars)");

            // Parse DSL
            PythonLexer lexer = new PythonLexer(dslCode);
            PythonParser parser = new PythonParser(lexer);
            List<PythonParser.ParsedNode> ast = parser.parseGraph();
            System.out.println("[RenderDsl] Parsed " + ast.size() + " nodes");

            // Determine final node
            String finalNodeId = nodeId != null ? nodeId : ast.get(ast.size() - 1).id;
            System.out.println("[RenderDsl] Final node: " + finalNodeId);

            // Execute graph
            NodeGraphRuntime runtime = new NodeGraphRuntime();
            runtime.registerAllFromAnnotationRegistry();

            MeshTopology mesh = executeWithPortFallback(runtime, ast, finalNodeId, portName);

            if (mesh == null) {
                System.err.println("[RenderDsl] ERROR: No mesh produced from graph");
                System.exit(1);
            }

            System.out.println("[RenderDsl] Mesh: verts=" + mesh.vertexCount()
                    + " faces=" + mesh.faceCount()
                    + " radius=" + String.format("%.3f", mesh.radius()));

            // Enable depth testing before rendering
            gl.enable(gl.DEPTH_TEST());

            // Set viewport and clear
            gl.viewport(0, 0, width, height);
            gl.clearColor(0.12f, 0.12f, 0.14f, 1.0f);
            gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());

            // Create camera with orbit position (matches MeshNodeViewerScene angles)
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

            // Upload and render mesh (uses same shader pipeline as MeshNodeViewerScene)
            HalfEdgeMeshRuntime meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(mesh);
            meshRuntime.render(camera);

            // Capture pixels before swap (glReadPixels reads from back buffer)
            platform.screenshot(outputPath);

            // Clean up GL resources
            meshRuntime.dispose();

            System.out.println("[RenderDsl] Done: " + outputPath);

        } catch (Exception e) {
            System.err.println("[RenderDsl] ERROR: " + e.getMessage());
            for (Throwable t = e; t != null; t = t.getCause()) {
                System.err.println("  Caused by: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            System.exit(1);
        } finally {
            platform.shutdown();
        }
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

        // Try "mesh" first (most common), fall back to "geometry" (loft/sweep/patch nodes)
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
}
