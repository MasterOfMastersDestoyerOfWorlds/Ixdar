package ixdar.geometry.mesh.data;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Set;

/**
 * Self-contained software rasterizer for rendering a mesh with per-face patch
 * colors from eight canonical angles and compositing them into a 4x2 grid PNG.
 * Independent of the running game viewer so it works in tests and in headless
 * preprocessing runs.
 */
public final class PatchRenderer {

    // 480 keeps the 4x2 composite at 1920x960 — under Claude's ~2000px image limit.
    private static final int CELL_W = 480;
    private static final int CELL_H = 480;
    private static final int BG_RGB = 0xF2F2F2;  // light grey, so patch colors pop

    private static final float[][] VIEWS = {
        {(float) (Math.PI / 2), 0f},
        {0f, 0f},
        {(float) (-Math.PI / 2), 0f},
        {(float) Math.PI, 0f},
        {(float) (Math.PI / 2), 1.45f},
        {(float) (Math.PI / 2), -1.45f},
        {(float) (Math.PI / 4), 0.4f},
        {(float) (3 * Math.PI / 4), 0.4f},
    };
    private static final String[] LABELS = {
        "Front", "Right", "Back", "Left", "Top", "Bottom", "3/4 Front-R", "3/4 Front-L"
    };

    private static final float[] LIGHT_DIR;
    static {
        float lx = -0.3f, ly = 0.8f, lz = 0.5f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        LIGHT_DIR = new float[]{lx / len, ly / len, lz / len};
    }

    private PatchRenderer() {}

    /**
     * Composite plus the 8 individual per-view renders. Emitting the per-view
     * images lets a caller read a specific view (e.g. Right) at native CELL_W
     * × CELL_H without the 4-way horizontal compression of the composite,
     * which matters when looking for fine detail like individual teeth.
     */
    public record MultiviewResult(BufferedImage composite, BufferedImage[] perView, String[] labels) {}

    public static BufferedImage renderMultiview(ArrayMesh mesh, PatchDecomposition decomposition) {
        return renderMultiview(mesh, decomposition, 1.0f);
    }

    public static BufferedImage renderMultiview(ArrayMesh mesh, PatchDecomposition decomposition, float zoom) {
        return renderMultiviewImpl(mesh, decomposition, /*flat=*/ false, zoom).composite();
    }

    /**
     * Flat-shaded render: every face is written with a globally-unique RGB
     * derived from its patch id (golden-ratio hue), no Lambert shading.
     * A VLM labeler can pixel-sample this image to determine which patch
     * covers any (x, y) point exactly, without the ambiguity Lambert shading
     * introduces when two palette colours look similar at grazing angles.
     */
    public static BufferedImage renderMultiviewFlat(ArrayMesh mesh, PatchDecomposition decomposition) {
        return renderMultiviewFlat(mesh, decomposition, 1.0f);
    }

    public static BufferedImage renderMultiviewFlat(ArrayMesh mesh, PatchDecomposition decomposition, float zoom) {
        return renderMultiviewImpl(mesh, decomposition, /*flat=*/ true, zoom).composite();
    }

    public static MultiviewResult renderMultiviewWithPerView(ArrayMesh mesh,
                                                             PatchDecomposition decomposition,
                                                             boolean flat) {
        return renderMultiviewWithPerView(mesh, decomposition, flat, 1.0f);
    }

    public static MultiviewResult renderMultiviewWithPerView(ArrayMesh mesh,
                                                             PatchDecomposition decomposition,
                                                             boolean flat, float zoom) {
        return renderMultiviewImpl(mesh, decomposition, flat, zoom);
    }

    /**
     * Globally-unique RGB for a patch id, using golden-ratio hue progression
     * for maximum pairwise hue separation across any two patch ids.
     */
    public static int uniquePatchColor(int pid) {
        float h = (float) ((pid * 0.6180339887498949) % 1.0);
        return hslToRgb(h, 0.65f, 0.55f);
    }

    /** Hex string (no leading #) of {@link #uniquePatchColor(int)}. */
    public static String uniquePatchColorHex(int pid) {
        int rgb = uniquePatchColor(pid);
        return String.format("%06X", rgb & 0xFFFFFF);
    }

    private static int hslToRgb(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hp = h * 6f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r1 = 0f, g1 = 0f, b1 = 0f;
        if (hp < 1f)      { r1 = c; g1 = x; }
        else if (hp < 2f) { r1 = x; g1 = c; }
        else if (hp < 3f) { g1 = c; b1 = x; }
        else if (hp < 4f) { g1 = x; b1 = c; }
        else if (hp < 5f) { r1 = x; b1 = c; }
        else              { r1 = c; b1 = x; }
        float m = l - c * 0.5f;
        int r = Math.round((r1 + m) * 255f);
        int g = Math.round((g1 + m) * 255f);
        int b = Math.round((b1 + m) * 255f);
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Feature-edge overlay diagnostic modes. {@link #STAGES} renders a
     * grey Lambert-shaded mesh and overlays each per-source edge set in a
     * distinct color, so you can see where dihedral / principal / crest
     * each fire (or don't). {@link #PATCHES_VS_CREST} renders the flat
     * patch decomposition and overlays crest edges vs. actual patch
     * boundaries in three categories (crest only / boundary only / both),
     * so you can see at a glance which crest signals were honored and
     * which were overridden downstream.
     */
    public enum OverlayMode { STAGES, PATCHES_VS_CREST }

    // Colors moved to FeatureEdgeColors so the live GL viewer shares them.
    private static final int EDGE_COLOR_DIHEDRAL      = FeatureEdgeColors.DIHEDRAL;
    private static final int EDGE_COLOR_PRINCIPAL     = FeatureEdgeColors.PRINCIPAL;
    private static final int EDGE_COLOR_CREST         = FeatureEdgeColors.CREST;
    private static final int EDGE_COLOR_SADDLE        = FeatureEdgeColors.SADDLE;
    private static final int EDGE_COLOR_MULTI         = FeatureEdgeColors.MULTI_SOURCE;
    private static final int EDGE_COLOR_BOUNDARY_ONLY = FeatureEdgeColors.BOUNDARY_ONLY;
    private static final int EDGE_COLOR_CREST_ONLY    = FeatureEdgeColors.CREST_IGNORED;
    private static final int EDGE_COLOR_ALIGNED       = FeatureEdgeColors.CREST_HONORED;

    public static MultiviewResult renderFeatureEdgeMultiview(ArrayMesh mesh,
                                                             SemanticPatchDecomposer.DecompositionDiagnostics diag,
                                                             OverlayMode mode) {
        return renderFeatureEdgeMultiview(mesh, diag, mode, 1.0f);
    }

    public static MultiviewResult renderFeatureEdgeMultiview(ArrayMesh mesh,
                                                             SemanticPatchDecomposer.DecompositionDiagnostics diag,
                                                             OverlayMode mode,
                                                             float zoom) {
        int faceCount = mesh.copyFaceIndices().length / 3;
        int[] facePatchColor;
        boolean flat;
        int bg;
        if (mode == OverlayMode.PATCHES_VS_CREST) {
            facePatchColor = buildFlatFaceColors(faceCount, diag.decomposition());
            flat = true;
            bg = 0x000000;
        } else {
            facePatchColor = new int[faceCount];
            Arrays.fill(facePatchColor, 0xB0B0B0);
            flat = false;
            bg = BG_RGB;
        }

        float[] vertexNormals = computeVertexNormals(mesh);

        BufferedImage composite = new BufferedImage(4 * CELL_W, 2 * CELL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(bg));
        g.fillRect(0, 0, composite.getWidth(), composite.getHeight());
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
        g.setFont(labelFont);

        BufferedImage[] perView = new BufferedImage[8];
        for (int i = 0; i < 8; i++) {
            BufferedImage view = renderSingleView(mesh, facePatchColor, VIEWS[i][0], VIEWS[i][1], flat, bg, zoom);
            drawFeatureEdgeOverlay(view, mesh, vertexNormals, VIEWS[i][0], VIEWS[i][1], diag, mode, zoom);
            perView[i] = view;
            int col = i % 4;
            int row = i / 4;
            int dx = col * CELL_W;
            int dy = row * CELL_H;
            g.drawImage(view, dx, dy, null);
            int tx = dx + 8;
            int ty = dy + labelFont.getSize() + 4;
            g.setColor(new Color(0, 0, 0, 180));
            g.drawString(LABELS[i], tx + 1, ty + 1);
            g.setColor(Color.WHITE);
            g.drawString(LABELS[i], tx, ty);
        }
        drawLegend(g, mode, labelFont);
        g.dispose();
        return new MultiviewResult(composite, perView, LABELS.clone());
    }

    private static float[] computeVertexNormals(ArrayMesh mesh) {
        int[] faceIdx = mesh.copyFaceIndices();
        float[] positions = mesh.copyPositions();
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / 3;
        float[] vn = new float[nv * 3];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3] * 3;
            int b = faceIdx[f * 3 + 1] * 3;
            int c = faceIdx[f * 3 + 2] * 3;
            float ex = positions[b]     - positions[a];
            float ey = positions[b + 1] - positions[a + 1];
            float ez = positions[b + 2] - positions[a + 2];
            float gx = positions[c]     - positions[a];
            float gy = positions[c + 1] - positions[a + 1];
            float gz = positions[c + 2] - positions[a + 2];
            float nx = ey * gz - ez * gy;
            float ny = ez * gx - ex * gz;
            float nz = ex * gy - ey * gx;
            for (int k = 0; k < 3; k++) {
                int v = faceIdx[f * 3 + k];
                vn[v * 3]     += nx;
                vn[v * 3 + 1] += ny;
                vn[v * 3 + 2] += nz;
            }
        }
        for (int v = 0; v < nv; v++) {
            float nx = vn[v * 3];
            float ny = vn[v * 3 + 1];
            float nz = vn[v * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-20f) {
                vn[v * 3]     = nx / len;
                vn[v * 3 + 1] = ny / len;
                vn[v * 3 + 2] = nz / len;
            }
        }
        return vn;
    }

    private static void drawFeatureEdgeOverlay(BufferedImage view, ArrayMesh mesh, float[] vertexNormals,
                                               float azimuth, float elevation,
                                               SemanticPatchDecomposer.DecompositionDiagnostics diag,
                                               OverlayMode mode,
                                               float zoom) {
        // Duplicate the camera setup from renderSingleView so edges project
        // onto the exact pixels their faces just rasterized onto. Any
        // refactor to extract this must stay in lockstep.
        float[] positions = mesh.copyPositions();
        int nv = positions.length / 3;

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 3) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float radius = 0.5f * (float) Math.sqrt(
                (maxX - minX) * (maxX - minX)
              + (maxY - minY) * (maxY - minY)
              + (maxZ - minZ) * (maxZ - minZ));
        if (radius < 1e-6f) radius = 1f;

        float cosA = (float) Math.cos(azimuth), sinA = (float) Math.sin(azimuth);
        float cosE = (float) Math.cos(elevation), sinE = (float) Math.sin(elevation);
        float fwdX = -sinA * cosE;
        float fwdY = -sinE;
        float fwdZ = -cosA * cosE;
        float rx = -fwdZ, ry = 0f, rz = fwdX;
        float rlen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rlen > 1e-6f) { rx /= rlen; rz /= rlen; } else { rx = 1f; rz = 0f; }
        float ux = ry * fwdZ - rz * fwdY;
        float uy = rz * fwdX - rx * fwdZ;
        float uz = rx * fwdY - ry * fwdX;
        float scale = (CELL_W * 0.42f * zoom) / radius;
        float originX = CELL_W * 0.5f;
        float originY = CELL_H * 0.5f;

        float[] vx = new float[nv];
        float[] vy = new float[nv];
        for (int i = 0; i < nv; i++) {
            float dx = positions[i * 3] - cx;
            float dy = positions[i * 3 + 1] - cy;
            float dz = positions[i * 3 + 2] - cz;
            vx[i] = originX + scale * (dx * rx + dy * ry + dz * rz);
            vy[i] = originY - scale * (dx * ux + dy * uy + dz * uz);
        }

        Graphics2D g = view.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (mode == OverlayMode.STAGES) {
            Set<Long> dih = diag.dihedralFeatureEdges();
            Set<Long> prin = diag.principalFeatureEdges();
            Set<Long> crest = diag.crestEdges();
            Set<Long> saddle = diag.saddleSeparatorEdges();
            Set<Long> all = new java.util.HashSet<>(dih);
            all.addAll(prin);
            all.addAll(crest);
            all.addAll(saddle);
            drawEdgeCategory(g, all, dih, prin, crest, EdgeCategory.DIHEDRAL_ONLY,
                    vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
            drawEdgeCategory(g, all, dih, prin, crest, EdgeCategory.PRINCIPAL_ONLY,
                    vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
            drawEdgeCategory(g, all, dih, prin, crest, EdgeCategory.CREST_ONLY,
                    vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
            drawEdgeCategory(g, all, dih, prin, crest, EdgeCategory.MULTI,
                    vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
            // Saddle separators drawn last in distinct purple so they're
            // unmistakable even when overlapping other categories.
            for (long key : saddle) {
                drawEdge(g, key, EDGE_COLOR_SADDLE, vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
            }
        } else {
            Set<Long> crest = diag.crestEdges();
            Set<Long> boundary = diag.patchBoundaryEdges();
            Set<Long> all = new java.util.HashSet<>(crest);
            all.addAll(boundary);
            for (long key : all) {
                boolean isCrest = crest.contains(key);
                boolean isBoundary = boundary.contains(key);
                if (isCrest && isBoundary) continue;
                int color = isBoundary ? EDGE_COLOR_BOUNDARY_ONLY : EDGE_COLOR_CREST_ONLY;
                drawEdge(g, key, color, vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
            }
            for (long key : all) {
                if (crest.contains(key) && boundary.contains(key)) {
                    drawEdge(g, key, EDGE_COLOR_ALIGNED, vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
                }
            }
        }
        g.dispose();
    }

    private enum EdgeCategory { DIHEDRAL_ONLY, PRINCIPAL_ONLY, CREST_ONLY, MULTI }

    private static void drawEdgeCategory(Graphics2D g, Set<Long> all,
                                         Set<Long> dih, Set<Long> prin, Set<Long> crest,
                                         EdgeCategory cat,
                                         float[] vx, float[] vy, float[] vertexNormals,
                                         float fwdX, float fwdY, float fwdZ) {
        int color = switch (cat) {
            case DIHEDRAL_ONLY  -> EDGE_COLOR_DIHEDRAL;
            case PRINCIPAL_ONLY -> EDGE_COLOR_PRINCIPAL;
            case CREST_ONLY     -> EDGE_COLOR_CREST;
            case MULTI          -> EDGE_COLOR_MULTI;
        };
        for (long key : all) {
            boolean d = dih.contains(key);
            boolean p = prin.contains(key);
            boolean c = crest.contains(key);
            int sourceCount = (d ? 1 : 0) + (p ? 1 : 0) + (c ? 1 : 0);
            boolean matches = switch (cat) {
                case DIHEDRAL_ONLY  -> d && sourceCount == 1;
                case PRINCIPAL_ONLY -> p && sourceCount == 1;
                case CREST_ONLY     -> c && sourceCount == 1;
                case MULTI          -> sourceCount >= 2;
            };
            if (!matches) continue;
            drawEdge(g, key, color, vx, vy, vertexNormals, fwdX, fwdY, fwdZ);
        }
    }

    private static void drawEdge(Graphics2D g, long edgeKey, int rgb,
                                 float[] vx, float[] vy, float[] vertexNormals,
                                 float fwdX, float fwdY, float fwdZ) {
        int u = (int) (edgeKey >> 32);
        int v = (int) (edgeKey & 0xffffffffL);
        float nu = vertexNormals[u * 3] * fwdX + vertexNormals[u * 3 + 1] * fwdY + vertexNormals[u * 3 + 2] * fwdZ;
        float nv = vertexNormals[v * 3] * fwdX + vertexNormals[v * 3 + 1] * fwdY + vertexNormals[v * 3 + 2] * fwdZ;
        if (nu > 0.1f && nv > 0.1f) return;
        g.setColor(new Color(rgb));
        g.drawLine(Math.round(vx[u]), Math.round(vy[u]),
                   Math.round(vx[v]), Math.round(vy[v]));
    }

    private static void drawLegend(Graphics2D g, OverlayMode mode, Font labelFont) {
        int y = 2 * CELL_H - 18;
        int x = 10;
        g.setFont(labelFont);
        if (mode == OverlayMode.STAGES) {
            x = drawSwatch(g, x, y, EDGE_COLOR_DIHEDRAL,  "dihedral");
            x = drawSwatch(g, x, y, EDGE_COLOR_PRINCIPAL, "principal");
            x = drawSwatch(g, x, y, EDGE_COLOR_CREST,     "crest");
            x = drawSwatch(g, x, y, EDGE_COLOR_SADDLE,    "saddle");
            x = drawSwatch(g, x, y, EDGE_COLOR_MULTI,     "multi-source");
        } else {
            x = drawSwatch(g, x, y, EDGE_COLOR_BOUNDARY_ONLY, "boundary only");
            x = drawSwatch(g, x, y, EDGE_COLOR_CREST_ONLY,    "crest ignored");
            x = drawSwatch(g, x, y, EDGE_COLOR_ALIGNED,       "crest honored");
        }
    }

    private static int drawSwatch(Graphics2D g, int x, int y, int rgb, String label) {
        g.setColor(new Color(rgb));
        g.fillRect(x, y, 14, 14);
        g.setColor(Color.BLACK);
        g.drawRect(x, y, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString(label, x + 20, y + 12);
        return x + 28 + g.getFontMetrics().stringWidth(label);
    }

    private static MultiviewResult renderMultiviewImpl(ArrayMesh mesh, PatchDecomposition decomposition, boolean flat, float zoom) {
        int faceCount = mesh.copyFaceIndices().length / 3;
        int[] facePatchColor = flat
                ? buildFlatFaceColors(faceCount, decomposition)
                : buildFaceColors(faceCount, decomposition);

        // Flat-mode background is pure black so the VLM labeler's pixel
        // sampler never confuses a patch colour with the backdrop — no
        // HSL(h, 0.65, 0.55) colour collides with 0x000000.
        int bg = flat ? 0x000000 : BG_RGB;
        BufferedImage composite = new BufferedImage(4 * CELL_W, 2 * CELL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(bg));
        g.fillRect(0, 0, composite.getWidth(), composite.getHeight());
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
        g.setFont(labelFont);

        BufferedImage[] perView = new BufferedImage[8];
        for (int i = 0; i < 8; i++) {
            BufferedImage view = renderSingleView(mesh, facePatchColor, VIEWS[i][0], VIEWS[i][1], flat, bg, zoom);
            perView[i] = view;
            int col = i % 4;
            int row = i / 4;
            int dx = col * CELL_W;
            int dy = row * CELL_H;
            g.drawImage(view, dx, dy, null);
            int tx = dx + 8;
            int ty = dy + labelFont.getSize() + 4;
            g.setColor(new Color(0, 0, 0, 180));
            g.drawString(LABELS[i], tx + 1, ty + 1);
            g.setColor(Color.WHITE);
            g.drawString(LABELS[i], tx, ty);
        }
        g.dispose();
        return new MultiviewResult(composite, perView, LABELS.clone());
    }

    private static int[] buildFaceColors(int faceCount, PatchDecomposition decomposition) {
        int[] out = new int[faceCount];
        Arrays.fill(out, 0xAAAAAA);
        for (Patch p : decomposition.patches()) {
            int rgb = Integer.parseInt(p.color(), 16);
            for (int f : p.faceIndices()) {
                if (f >= 0 && f < faceCount) out[f] = rgb;
            }
        }
        return out;
    }

    private static int[] buildFlatFaceColors(int faceCount, PatchDecomposition decomposition) {
        int[] out = new int[faceCount];
        Arrays.fill(out, 0xAAAAAA);
        for (Patch p : decomposition.patches()) {
            int rgb = uniquePatchColor(p.id());
            for (int f : p.faceIndices()) {
                if (f >= 0 && f < faceCount) out[f] = rgb;
            }
        }
        return out;
    }

    /**
     * Render a per-vertex scalar field as a thermal heatmap multiview
     * (PATCH-15 infra). Scalar values are interpolated barycentrically
     * across each triangle and mapped through the same dark→bright
     * thermal ramp as the GL {@code mesh_scalar.fs} shader, keeping the
     * CPU PNG diagnostic pixel-congruent with the live viewer's SCALAR
     * mode. Pass {@code scalarMin == scalarMax == NaN} to autoscale
     * from the array.
     */
    public static MultiviewResult renderScalarMultiview(ArrayMesh mesh, float[] vertexScalar,
                                                        float scalarMin, float scalarMax, float zoom) {
        if (vertexScalar == null || vertexScalar.length < mesh.vertexCount()) {
            throw new IllegalArgumentException("vertexScalar must be at least mesh.vertexCount() long");
        }
        if (Float.isNaN(scalarMin) || Float.isNaN(scalarMax)) {
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < mesh.vertexCount(); i++) {
                float v = vertexScalar[i];
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            scalarMin = lo;
            scalarMax = hi;
        }
        float range = Math.max(scalarMax - scalarMin, 1e-6f);
        int nv = mesh.vertexCount();
        int[] vertexRgb = new int[nv];
        for (int i = 0; i < nv; i++) {
            float v = (vertexScalar[i] - scalarMin) / range;
            vertexRgb[i] = scalarRampColor(v);
        }
        BufferedImage composite = new BufferedImage(4 * CELL_W, 2 * CELL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(0x101018));
        g.fillRect(0, 0, composite.getWidth(), composite.getHeight());
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
        g.setFont(labelFont);

        BufferedImage[] perView = new BufferedImage[8];
        for (int i = 0; i < 8; i++) {
            BufferedImage view = renderSingleViewScalar(mesh, vertexRgb,
                    VIEWS[i][0], VIEWS[i][1], 0x101018, zoom);
            perView[i] = view;
            int col = i % 4;
            int row = i / 4;
            int dx = col * CELL_W;
            int dy = row * CELL_H;
            g.drawImage(view, dx, dy, null);
            int tx = dx + 8;
            int ty = dy + labelFont.getSize() + 4;
            g.setColor(new Color(0, 0, 0, 180));
            g.drawString(LABELS[i], tx + 1, ty + 1);
            g.setColor(Color.WHITE);
            g.drawString(LABELS[i], tx, ty);
        }
        g.dispose();
        return new MultiviewResult(composite, perView, LABELS.clone());
    }

    /**
     * CPU-side thermal ramp. Mirrors the four-stop palette in
     * {@code mesh_scalar.fs} so offline PNG diagnostics match the live
     * GL view pixel-for-pixel (within floating-point rounding).
     */
    public static int scalarRampColor(float v) {
        if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
        float r, g, b;
        float c0r = 0.05f, c0g = 0.05f, c0b = 0.20f;  // deep indigo
        float c1r = 0.55f, c1g = 0.05f, c1b = 0.15f;  // wine
        float c2r = 0.95f, c2g = 0.45f, c2b = 0.05f;  // orange
        float c3r = 1.00f, c3g = 0.95f, c3b = 0.70f;  // pale yellow
        if (v < 0.33f) {
            float t = v / 0.33f;
            r = c0r + (c1r - c0r) * t;
            g = c0g + (c1g - c0g) * t;
            b = c0b + (c1b - c0b) * t;
        } else if (v < 0.66f) {
            float t = (v - 0.33f) / 0.33f;
            r = c1r + (c2r - c1r) * t;
            g = c1g + (c2g - c1g) * t;
            b = c1b + (c2b - c1b) * t;
        } else {
            float t = (v - 0.66f) / 0.34f;
            r = c2r + (c3r - c2r) * t;
            g = c2g + (c3g - c2g) * t;
            b = c2b + (c3b - c2b) * t;
        }
        int ri = Math.round(r * 255f);
        int gi = Math.round(g * 255f);
        int bi = Math.round(b * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static BufferedImage renderSingleViewScalar(ArrayMesh mesh, int[] vertexRgb,
                                                        float azimuth, float elevation,
                                                        int bg, float zoom) {
        float[] positions = mesh.copyPositions();
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / 3;

        // Centered bounding sphere.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 3) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float radius = 0.5f * (float) Math.sqrt(
                (maxX - minX) * (maxX - minX)
              + (maxY - minY) * (maxY - minY)
              + (maxZ - minZ) * (maxZ - minZ));
        if (radius < 1e-6f) radius = 1f;

        float cosA = (float) Math.cos(azimuth), sinA = (float) Math.sin(azimuth);
        float cosE = (float) Math.cos(elevation), sinE = (float) Math.sin(elevation);
        float fwdX = -sinA * cosE;
        float fwdY = -sinE;
        float fwdZ = -cosA * cosE;
        float rx = -fwdZ, ry = 0f, rz = fwdX;
        float rlen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rlen > 1e-6f) { rx /= rlen; rz /= rlen; } else { rx = 1f; rz = 0f; }
        float ux = ry * fwdZ - rz * fwdY;
        float uy = rz * fwdX - rx * fwdZ;
        float uz = rx * fwdY - ry * fwdX;
        float scale = (CELL_W * 0.42f * zoom) / radius;
        float originX = CELL_W * 0.5f;
        float originY = CELL_H * 0.5f;

        int nv = positions.length / 3;
        float[] vx = new float[nv];
        float[] vy = new float[nv];
        float[] vz = new float[nv];
        for (int i = 0; i < nv; i++) {
            float dx = positions[i * 3] - cx;
            float dy = positions[i * 3 + 1] - cy;
            float dz = positions[i * 3 + 2] - cz;
            vx[i] = originX + scale * (dx * rx + dy * ry + dz * rz);
            vy[i] = originY - scale * (dx * ux + dy * uy + dz * uz);
            vz[i] = dx * fwdX + dy * fwdY + dz * fwdZ;
        }

        int[] pixels = new int[CELL_W * CELL_H];
        float[] depth = new float[CELL_W * CELL_H];
        Arrays.fill(pixels, bg);
        Arrays.fill(depth, Float.POSITIVE_INFINITY);

        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3];
            int b = faceIdx[f * 3 + 1];
            int c = faceIdx[f * 3 + 2];
            drawTriangleGouraud(pixels, depth,
                    vx[a], vy[a], vz[a], vertexRgb[a],
                    vx[b], vy[b], vz[b], vertexRgb[b],
                    vx[c], vy[c], vz[c], vertexRgb[c]);
        }

        BufferedImage img = new BufferedImage(CELL_W, CELL_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CELL_H; y++) {
            for (int x = 0; x < CELL_W; x++) {
                img.setRGB(x, y, pixels[y * CELL_W + x]);
            }
        }
        return img;
    }

    /** Per-pixel barycentric color blend over a triangle — cheaper than a full shader dispatch. */
    private static void drawTriangleGouraud(int[] pixels, float[] depth,
                                            float ax, float ay, float az, int ca,
                                            float bx, float by, float bz, int cb,
                                            float cxp, float cyp, float cz, int cc) {
        float minX = Math.min(ax, Math.min(bx, cxp));
        float minY = Math.min(ay, Math.min(by, cyp));
        float maxX = Math.max(ax, Math.max(bx, cxp));
        float maxY = Math.max(ay, Math.max(by, cyp));
        int ix0 = Math.max(0, (int) Math.floor(minX));
        int iy0 = Math.max(0, (int) Math.floor(minY));
        int ix1 = Math.min(CELL_W - 1, (int) Math.ceil(maxX));
        int iy1 = Math.min(CELL_H - 1, (int) Math.ceil(maxY));
        if (ix0 > ix1 || iy0 > iy1) return;
        float denom = (by - cyp) * (ax - cxp) + (cxp - bx) * (ay - cyp);
        if (Math.abs(denom) < 1e-8f) return;
        float invDenom = 1f / denom;
        int ra = (ca >> 16) & 0xff, ga = (ca >> 8) & 0xff, ba = ca & 0xff;
        int rb = (cb >> 16) & 0xff, gb = (cb >> 8) & 0xff, bb = cb & 0xff;
        int rc = (cc >> 16) & 0xff, gc = (cc >> 8) & 0xff, bc = cc & 0xff;
        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float w1 = ((by - cyp) * (px - cxp) + (cxp - bx) * (py - cyp)) * invDenom;
                float w2 = ((cyp - ay) * (px - cxp) + (ax - cxp) * (py - cyp)) * invDenom;
                float w3 = 1f - w1 - w2;
                if (w1 < 0f || w2 < 0f || w3 < 0f) continue;
                float z = w1 * az + w2 * bz + w3 * cz;
                int idx = y * CELL_W + x;
                if (z < depth[idx]) {
                    depth[idx] = z;
                    int r = Math.round(w1 * ra + w2 * rb + w3 * rc);
                    int g = Math.round(w1 * ga + w2 * gb + w3 * gc);
                    int bl = Math.round(w1 * ba + w2 * bb + w3 * bc);
                    pixels[idx] = (r << 16) | (g << 8) | bl;
                }
            }
        }
    }

    private static BufferedImage renderSingleView(ArrayMesh mesh, int[] facePatchColor,
                                                  float azimuth, float elevation,
                                                  boolean flat, int bg, float zoom) {
        float[] positions = mesh.copyPositions();
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / 3;

        // Centered bounding sphere.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 3) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float radius = 0.5f * (float) Math.sqrt(
                (maxX - minX) * (maxX - minX)
              + (maxY - minY) * (maxY - minY)
              + (maxZ - minZ) * (maxZ - minZ));
        if (radius < 1e-6f) radius = 1f;

        // Camera basis vectors. Azimuth rotates around Y-up; elevation around the
        // azimuth-rotated X.
        float cosA = (float) Math.cos(azimuth), sinA = (float) Math.sin(azimuth);
        float cosE = (float) Math.cos(elevation), sinE = (float) Math.sin(elevation);
        // forward = direction from camera to mesh center.
        float fwdX = -sinA * cosE;
        float fwdY = -sinE;
        float fwdZ = -cosA * cosE;
        // right = cross(forward, worldUp)
        float rx = -fwdZ;
        float ry = 0f;
        float rz = fwdX;
        float rlen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rlen > 1e-6f) {
            rx /= rlen;
            rz /= rlen;
        } else {
            rx = 1f; rz = 0f;
        }
        // up = cross(right, forward)
        float ux = ry * fwdZ - rz * fwdY;
        float uy = rz * fwdX - rx * fwdZ;
        float uz = rx * fwdY - ry * fwdX;

        // Orthographic projection: world point P → (dot(P-c, right), dot(P-c, up), dot(P-c, forward))
        float scale = (CELL_W * 0.42f * zoom) / radius;
        float originX = CELL_W * 0.5f;
        float originY = CELL_H * 0.5f;

        int[] pixels = new int[CELL_W * CELL_H];
        float[] depth = new float[CELL_W * CELL_H];
        Arrays.fill(pixels, bg);
        Arrays.fill(depth, Float.POSITIVE_INFINITY);

        // Project vertices once.
        int nv = positions.length / 3;
        float[] vx = new float[nv];
        float[] vy = new float[nv];
        float[] vz = new float[nv];
        for (int i = 0; i < nv; i++) {
            float dx = positions[i * 3] - cx;
            float dy = positions[i * 3 + 1] - cy;
            float dz = positions[i * 3 + 2] - cz;
            vx[i] = originX + scale * (dx * rx + dy * ry + dz * rz);
            vy[i] = originY - scale * (dx * ux + dy * uy + dz * uz);
            vz[i] = dx * fwdX + dy * fwdY + dz * fwdZ;  // depth (smaller = closer)
        }

        // Rasterize each triangle, flat shaded by face normal · light.
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3];
            int b = faceIdx[f * 3 + 1];
            int c = faceIdx[f * 3 + 2];
            float ex = positions[b * 3] - positions[a * 3];
            float ey = positions[b * 3 + 1] - positions[a * 3 + 1];
            float ez = positions[b * 3 + 2] - positions[a * 3 + 2];
            float gx = positions[c * 3] - positions[a * 3];
            float gy = positions[c * 3 + 1] - positions[a * 3 + 1];
            float gz = positions[c * 3 + 2] - positions[a * 3 + 2];
            float nx = ey * gz - ez * gy;
            float ny = ez * gx - ex * gz;
            float nz = ex * gy - ey * gx;
            float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen < 1e-20f) continue;
            nx /= nlen; ny /= nlen; nz /= nlen;
            int base = facePatchColor[f];
            int shaded;
            if (flat) {
                shaded = base;  // no Lambert — each face writes its exact patch colour
            } else {
                float lambert = nx * LIGHT_DIR[0] + ny * LIGHT_DIR[1] + nz * LIGHT_DIR[2];
                lambert = 0.5f + 0.5f * Math.abs(lambert);
                shaded = shade(base, lambert);
            }

            drawTriangle(pixels, depth,
                    vx[a], vy[a], vz[a],
                    vx[b], vy[b], vz[b],
                    vx[c], vy[c], vz[c],
                    shaded);
        }

        BufferedImage img = new BufferedImage(CELL_W, CELL_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CELL_H; y++) {
            for (int x = 0; x < CELL_W; x++) {
                img.setRGB(x, y, pixels[y * CELL_W + x]);
            }
        }
        return img;
    }

    private static int shade(int rgb, float lambert) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        lambert = Math.max(0.35f, Math.min(1.0f, lambert));
        r = Math.round(r * lambert);
        g = Math.round(g * lambert);
        b = Math.round(b * lambert);
        return (r << 16) | (g << 8) | b;
    }

    private static void drawTriangle(int[] pixels, float[] depth,
                                     float ax, float ay, float az,
                                     float bx, float by, float bz,
                                     float cx, float cy, float cz,
                                     int color) {
        float minX = Math.min(ax, Math.min(bx, cx));
        float minY = Math.min(ay, Math.min(by, cy));
        float maxX = Math.max(ax, Math.max(bx, cx));
        float maxY = Math.max(ay, Math.max(by, cy));
        int ix0 = Math.max(0, (int) Math.floor(minX));
        int iy0 = Math.max(0, (int) Math.floor(minY));
        int ix1 = Math.min(CELL_W - 1, (int) Math.ceil(maxX));
        int iy1 = Math.min(CELL_H - 1, (int) Math.ceil(maxY));
        if (ix0 > ix1 || iy0 > iy1) return;

        float denom = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (Math.abs(denom) < 1e-8f) return;
        float invDenom = 1f / denom;

        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float w1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) * invDenom;
                float w2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) * invDenom;
                float w3 = 1f - w1 - w2;
                if (w1 < 0f || w2 < 0f || w3 < 0f) continue;
                float z = w1 * az + w2 * bz + w3 * cz;
                int idx = y * CELL_W + x;
                if (z < depth[idx]) {
                    depth[idx] = z;
                    pixels[idx] = color;
                }
            }
        }
    }
}
