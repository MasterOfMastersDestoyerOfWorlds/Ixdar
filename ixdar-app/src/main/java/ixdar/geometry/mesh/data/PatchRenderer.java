package ixdar.geometry.mesh.data;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Set;

import java.util.HashSet;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Self-contained software rasterizer for rendering a mesh with per-face patch
 * colors from eight canonical angles and compositing them into a 4x2 grid PNG.
 * Independent of the running game viewer so it works in tests and in headless
 * preprocessing runs.
 */
public final class PatchRenderer {
    public static final String SADDLE = "saddle";
    public static final float NUM_0_3 = 0.3f;
    public static final float NUM_0_8 = 0.8f;
    public static final float NUM_0_5 = 0.5f;
    public static final double NUM_0_6180339887498949 = 0.6180339887498949;
    public static final float NUM_0_65 = 0.65f;
    public static final float NUM_0_55 = 0.55f;
    public static final int NUM_0xFFFFF = 0xFFFFFF;
    public static final float NUM_1 = 1f;
    public static final float NUM_2 = 2f;
    public static final float NUM_6 = 6f;
    public static final float NUM_0 = 0f;
    public static final float NUM_3 = 3f;
    public static final float NUM_4 = 4f;
    public static final float NUM_5 = 5f;
    public static final float NUM_255 = 255f;
    public static final int NUM_255_2 = 255;
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;
    public static final int NUM_3_2 = 3;
    public static final int NUM_0xB0B0B0 = 0xB0B0B0;
    public static final int NUM_4_2 = 4;
    public static final int NUM_18 = 18;
    public static final int NUM_180 = 180;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_0_42 = 0.42f;
    public static final float NUM_2_2 = 2.2f;
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_0_2 = 0.2f;
    public static final int NUM_9 = 9;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final int NUM_10 = 10;
    public static final int NUM_14 = 14;
    public static final int NUM_20 = 20;
    public static final int NUM_12 = 12;
    public static final int NUM_28 = 28;
    public static final int NUM_0xAAAAAA = 0xAAAAAA;
    public static final int NUM_0x101018 = 0x101018;
    public static final float NUM_0_05 = 0.05f;
    public static final float NUM_0_20 = 0.20f;
    public static final float NUM_0_15 = 0.15f;
    public static final float NUM_0_95 = 0.95f;
    public static final float NUM_0_45 = 0.45f;
    public static final float NUM_1_00 = 1.00f;
    public static final float NUM_0_70 = 0.70f;
    public static final float NUM_0_33 = 0.33f;
    public static final float NUM_0_66 = 0.66f;
    public static final float NUM_0_34 = 0.34f;
    public static final float NUM_1e_8 = 1e-8f;
    public static final int NUM_0xf = 0xff;
    public static final float NUM_0_35 = 0.35f;

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

    // PATCH-22: Morse-Smale overlay colors.
    private static final int MSC_ARC_COLOR       = 0x000000; // black arcs
    private static final int MSC_MAX_DOT_COLOR   = 0xFF3040; // red — tooth tips, ridge peaks
    private static final int MSC_MIN_DOT_COLOR   = 0x3080FF; // blue — socket interiors
    private static final int MSC_SADDLE_DOT_COLOR = 0xFFD700; // yellow — inter-tooth, ridge-saddle

    // Colors moved to FeatureEdgeColors so the live GL viewer shares them.
    private static final int EDGE_COLOR_DIHEDRAL      = FeatureEdgeColors.DIHEDRAL;
    private static final int EDGE_COLOR_PRINCIPAL     = FeatureEdgeColors.PRINCIPAL;
    private static final int EDGE_COLOR_CREST         = FeatureEdgeColors.CREST;
    private static final int EDGE_COLOR_SADDLE        = FeatureEdgeColors.SADDLE;
    private static final int EDGE_COLOR_MULTI         = FeatureEdgeColors.MULTI_SOURCE;
    private static final int EDGE_COLOR_BOUNDARY_ONLY = FeatureEdgeColors.BOUNDARY_ONLY;
    private static final int EDGE_COLOR_CREST_ONLY    = FeatureEdgeColors.CREST_IGNORED;
    private static final int EDGE_COLOR_ALIGNED       = FeatureEdgeColors.CREST_HONORED;
    static {
        float lx = -NUM_0_3, ly = NUM_0_8, lz = NUM_0_5;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        LIGHT_DIR = new float[]{lx / len, ly / len, lz / len};
    }

    private PatchRenderer() {}

    /**
     * Lambert-shaded 4x2 multiview composite of the mesh coloured by patch palette.
     *
     * @param mesh source mesh
     * @param decomposition patch assignment to colour faces with
     * @return 4-column, 2-row composite PNG of the eight canonical views
     */
    public static BufferedImage renderMultiview(ArrayMesh mesh, PatchDecomposition decomposition) {
        return renderMultiview(mesh, decomposition, 1.0f);
    }

    /**
     * Lambert-shaded multiview composite with a zoom factor on the orthographic projection.
     *
     * @param mesh source mesh
     * @param decomposition patch assignment to colour faces with
     * @param zoom orthographic zoom; values &gt; 1 enlarge the mesh in each cell
     * @return 4-column, 2-row composite PNG of the eight canonical views
     */
    public static BufferedImage renderMultiview(ArrayMesh mesh, PatchDecomposition decomposition, float zoom) {
        return renderMultiviewImpl(mesh, decomposition, /*flat=*/ false, zoom).composite();
    }

    /**
     * Flat-shaded render: every face is written with a globally-unique RGB
     * derived from its patch id (golden-ratio hue), no Lambert shading.
     * A VLM labeler can pixel-sample this image to determine which patch
     * covers any (x, y) point exactly, without the ambiguity Lambert shading
     * introduces when two palette colours look similar at grazing angles.
     *
     * @param mesh source mesh
     * @param decomposition patch assignment whose ids drive the flat unique colours
     * @return 4-column, 2-row composite PNG with no Lambert shading
     */
    public static BufferedImage renderMultiviewFlat(ArrayMesh mesh, PatchDecomposition decomposition) {
        return renderMultiviewFlat(mesh, decomposition, 1.0f);
    }

    /**
     * Flat-shaded multiview composite with a zoom factor on the orthographic projection.
     *
     * @param mesh source mesh
     * @param decomposition patch assignment whose ids drive the flat unique colours
     * @param zoom orthographic zoom factor
     * @return 4-column, 2-row composite PNG with no Lambert shading
     */
    public static BufferedImage renderMultiviewFlat(ArrayMesh mesh, PatchDecomposition decomposition, float zoom) {
        return renderMultiviewImpl(mesh, decomposition, /*flat=*/ true, zoom).composite();
    }

    /**
     * Multiview render that exposes the eight per-view sub-images alongside the composite.
     *
     * @param mesh source mesh
     * @param decomposition patch assignment driving face colours
     * @param flat {@code true} for unique flat colours, {@code false} for Lambert shading
     * @return composite + per-view buffer + view labels
     */
    public static MultiviewResult renderMultiviewWithPerView(ArrayMesh mesh,
                                                             PatchDecomposition decomposition,
                                                             boolean flat) {
        return renderMultiviewWithPerView(mesh, decomposition, flat, 1.0f);
    }

    /**
     * Multiview render with per-view buffers and a zoom factor.
     *
     * @param mesh source mesh
     * @param decomposition patch assignment driving face colours
     * @param flat {@code true} for unique flat colours, {@code false} for Lambert shading
     * @param zoom orthographic zoom factor
     * @return composite + per-view buffer + view labels
     */
    public static MultiviewResult renderMultiviewWithPerView(ArrayMesh mesh,
                                                             PatchDecomposition decomposition,
                                                             boolean flat, float zoom) {
        return renderMultiviewImpl(mesh, decomposition, flat, zoom);
    }

    /**
     * Globally-unique RGB for a patch id, using golden-ratio hue progression
     * for maximum pairwise hue separation across any two patch ids.
     *
     * @param pid patch id
     * @return packed 0xRRGGBB integer
     */
    public static int uniquePatchColor(int pid) {
        float h = (float) ((pid * NUM_0_6180339887498949) % 1.0);
        return hslToRgb(h, NUM_0_65, NUM_0_55);
    }

    /**
     * Hex string (no leading #) of {@link #uniquePatchColor(int)}.
     *
     * @param pid patch id
     * @return uppercase 6-digit hex of the RGB triple
     */
    public static String uniquePatchColorHex(int pid) {
        int rgb = uniquePatchColor(pid);
        return String.format("%06X", rgb & NUM_0xFFFFF);
    }

    private static int hslToRgb(float h, float s, float l) {
        float c = (NUM_1 - Math.abs(NUM_2 * l - NUM_1)) * s;
        float hp = h * NUM_6;
        float x = c * (NUM_1 - Math.abs(hp % NUM_2 - NUM_1));
        float r1 = NUM_0, g1 = NUM_0, b1 = NUM_0;
        if (hp < NUM_1)      { r1 = c; g1 = x; }
        else if (hp < NUM_2) { r1 = x; g1 = c; }
        else if (hp < NUM_3) { g1 = c; b1 = x; }
        else if (hp < NUM_4) { g1 = x; b1 = c; }
        else if (hp < NUM_5) { r1 = x; b1 = c; }
        else              { r1 = c; b1 = x; }
        float m = l - c * NUM_0_5;
        int r = Math.round((r1 + m) * NUM_255);
        int g = Math.round((g1 + m) * NUM_255);
        int b = Math.round((b1 + m) * NUM_255);
        r = Math.max(0, Math.min(NUM_255_2, r));
        g = Math.max(0, Math.min(NUM_255_2, g));
        b = Math.max(0, Math.min(NUM_255_2, b));
        return (r << NUM_16) | (g << NUM_8) | b;
    }

    /**
     * Multiview render with a feature-edge overlay drawn on top of each view.
     *
     * @param mesh source mesh
     * @param diag decomposition diagnostics supplying the edge sets to overlay
     * @param mode which overlay to draw (see {@link OverlayMode})
     * @return composite + per-view buffer + labels
     */
    public static MultiviewResult renderFeatureEdgeMultiview(ArrayMesh mesh,
                                                             SemanticPatchDecomposer.DecompositionDiagnostics diag,
                                                             OverlayMode mode) {
        return renderFeatureEdgeMultiview(mesh, diag, mode, 1.0f);
    }

    /**
     * Feature-edge overlay multiview with a zoom factor.
     *
     * @param mesh source mesh
     * @param diag decomposition diagnostics supplying the edge sets to overlay
     * @param mode which overlay to draw (see {@link OverlayMode})
     * @param zoom orthographic zoom factor
     * @return composite + per-view buffer + labels
     */
    public static MultiviewResult renderFeatureEdgeMultiview(ArrayMesh mesh,
                                                             SemanticPatchDecomposer.DecompositionDiagnostics diag,
                                                             OverlayMode mode,
                                                             float zoom) {
        int faceCount = mesh.copyFaceIndices().length / NUM_3_2;
        int[] facePatchColor;
        boolean flat;
        int bg;
        if (mode == OverlayMode.PATCHES_VS_CREST) {
            facePatchColor = buildFlatFaceColors(faceCount, diag.decomposition());
            flat = true;
            bg = MSC_ARC_COLOR;
        } else {
            facePatchColor = new int[faceCount];
            Arrays.fill(facePatchColor, NUM_0xB0B0B0);
            flat = false;
            bg = BG_RGB;
        }

        float[] vertexNormals = computeVertexNormals(mesh);

        BufferedImage composite = new BufferedImage(NUM_4_2 * CELL_W, 2 * CELL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(bg));
        g.fillRect(0, 0, composite.getWidth(), composite.getHeight());
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, NUM_18);
        g.setFont(labelFont);

        BufferedImage[] perView = new BufferedImage[NUM_8];
        for (int i = 0; i < NUM_8; i++) {
            BufferedImage view = renderSingleView(mesh, facePatchColor, VIEWS[i][0], VIEWS[i][1], flat, bg, zoom);
            drawFeatureEdgeOverlay(view, mesh, vertexNormals, VIEWS[i][0], VIEWS[i][1], diag, mode, zoom);
            perView[i] = view;
            int col = i % NUM_4_2;
            int row = i / NUM_4_2;
            int dx = col * CELL_W;
            int dy = row * CELL_H;
            g.drawImage(view, dx, dy, null);
            int tx = dx + NUM_8;
            int ty = dy + labelFont.getSize() + NUM_4_2;
            g.setColor(new Color(0, 0, 0, NUM_180));
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
        int faceCount = faceIdx.length / NUM_3_2;
        float[] vn = new float[nv * NUM_3_2];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3_2] * NUM_3_2;
            int b = faceIdx[f * NUM_3_2 + 1] * NUM_3_2;
            int c = faceIdx[f * NUM_3_2 + 2] * NUM_3_2;
            float ex = positions[b]     - positions[a];
            float ey = positions[b + 1] - positions[a + 1];
            float ez = positions[b + 2] - positions[a + 2];
            float gx = positions[c]     - positions[a];
            float gy = positions[c + 1] - positions[a + 1];
            float gz = positions[c + 2] - positions[a + 2];
            float nx = ey * gz - ez * gy;
            float ny = ez * gx - ex * gz;
            float nz = ex * gy - ey * gx;
            for (int k = 0; k < NUM_3_2; k++) {
                int v = faceIdx[f * NUM_3_2 + k];
                vn[v * NUM_3_2]     += nx;
                vn[v * NUM_3_2 + 1] += ny;
                vn[v * NUM_3_2 + 2] += nz;
            }
        }
        for (int v = 0; v < nv; v++) {
            float nx = vn[v * NUM_3_2];
            float ny = vn[v * NUM_3_2 + 1];
            float nz = vn[v * NUM_3_2 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > NUM_1e_20) {
                vn[v * NUM_3_2]     = nx / len;
                vn[v * NUM_3_2 + 1] = ny / len;
                vn[v * NUM_3_2 + 2] = nz / len;
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
        int nv = positions.length / NUM_3_2;

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += NUM_3_2) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float cx = (minX + maxX) * NUM_0_5;
        float cy = (minY + maxY) * NUM_0_5;
        float cz = (minZ + maxZ) * NUM_0_5;
        float radius = NUM_0_5 * (float) Math.sqrt(
                (maxX - minX) * (maxX - minX)
              + (maxY - minY) * (maxY - minY)
              + (maxZ - minZ) * (maxZ - minZ));
        if (radius < NUM_1e_6) radius = NUM_1;

        float cosA = (float) Math.cos(azimuth), sinA = (float) Math.sin(azimuth);
        float cosE = (float) Math.cos(elevation), sinE = (float) Math.sin(elevation);
        float fwdX = -sinA * cosE;
        float fwdY = -sinE;
        float fwdZ = -cosA * cosE;
        float rx = -fwdZ, ry = NUM_0, rz = fwdX;
        float rlen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rlen > NUM_1e_6) { rx /= rlen; rz /= rlen; } else { rx = NUM_1; rz = NUM_0; }
        float ux = ry * fwdZ - rz * fwdY;
        float uy = rz * fwdX - rx * fwdZ;
        float uz = rx * fwdY - ry * fwdX;
        float scale = (CELL_W * NUM_0_42 * zoom) / radius;
        float originX = CELL_W * NUM_0_5;
        float originY = CELL_H * NUM_0_5;

        float[] vx = new float[nv];
        float[] vy = new float[nv];
        for (int i = 0; i < nv; i++) {
            float dx = positions[i * NUM_3_2] - cx;
            float dy = positions[i * NUM_3_2 + 1] - cy;
            float dz = positions[i * NUM_3_2 + 2] - cz;
            vx[i] = originX + scale * (dx * rx + dy * ry + dz * rz);
            vy[i] = originY - scale * (dx * ux + dy * uy + dz * uz);
        }

        Graphics2D g = view.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(NUM_2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (mode == OverlayMode.MSC) {
            MorseSmaleComplex.Result msc = diag.morseSmale();
            if (msc != null) {
                // Arcs first so dots sit on top of their endpoints.
                g.setStroke(new BasicStroke(NUM_2_2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(MSC_ARC_COLOR));
                for (MorseSmaleComplex.Arc arc : msc.arcs()) {
                    int[] verts = arc.vertices();
                    for (int i = 0; i + 1 < verts.length; i++) {
                        int arcU = verts[i];
                        int arcV = verts[i + 1];
                        float dotU = vertexNormals[arcU * NUM_3_2] * fwdX + vertexNormals[arcU * NUM_3_2 + 1] * fwdY + vertexNormals[arcU * NUM_3_2 + 2] * fwdZ;
                        float dotV = vertexNormals[arcV * NUM_3_2] * fwdX + vertexNormals[arcV * NUM_3_2 + 1] * fwdY + vertexNormals[arcV * NUM_3_2 + 2] * fwdZ;
                        if (dotU > NUM_0_1 && dotV > NUM_0_1) continue;
                        g.drawLine(Math.round(vx[arcU]), Math.round(vy[arcU]),
                                Math.round(vx[arcV]), Math.round(vy[arcV]));
                    }
                }
                // Dots last. Back-face cull using vertex normal.
                for (MorseSmaleComplex.CriticalPoint cp : msc.critical()) {
                    int cpV = cp.vertex();
                    float cpDot = vertexNormals[cpV * NUM_3_2] * fwdX + vertexNormals[cpV * NUM_3_2 + 1] * fwdY + vertexNormals[cpV * NUM_3_2 + 2] * fwdZ;
                    if (cpDot > NUM_0_2) continue;  // back-facing
                    int color = switch (cp.type()) {
                        case MAX    -> MSC_MAX_DOT_COLOR;
                        case MIN    -> MSC_MIN_DOT_COLOR;
                        case SADDLE -> MSC_SADDLE_DOT_COLOR;
                    };
                    g.setColor(new Color(color));
                    int dotX = Math.round(vx[cpV]);
                    int dotY = Math.round(vy[cpV]);
                    g.fillOval(dotX - NUM_4_2, dotY - NUM_4_2, NUM_9, NUM_9);
                    g.setColor(Color.BLACK);
                    g.drawOval(dotX - NUM_4_2, dotY - NUM_4_2, NUM_9, NUM_9);  // outline for contrast
                }
            }
        } else if (mode == OverlayMode.STAGES) {
            Set<Long> dih = diag.dihedralFeatureEdges();
            Set<Long> prin = diag.principalFeatureEdges();
            Set<Long> crest = diag.crestEdges();
            Set<Long> saddle = diag.saddleSeparatorEdges();
            Set<Long> all = new HashSet<>(dih);
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
            Set<Long> all = new HashSet<>(crest);
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
        int u = (int) (edgeKey >> NUM_32);
        int v = (int) (edgeKey & NUM_0xffffffff);
        float nu = vertexNormals[u * NUM_3_2] * fwdX + vertexNormals[u * NUM_3_2 + 1] * fwdY + vertexNormals[u * NUM_3_2 + 2] * fwdZ;
        float nv = vertexNormals[v * NUM_3_2] * fwdX + vertexNormals[v * NUM_3_2 + 1] * fwdY + vertexNormals[v * NUM_3_2 + 2] * fwdZ;
        if (nu > NUM_0_1 && nv > NUM_0_1) return;
        g.setColor(new Color(rgb));
        g.drawLine(Math.round(vx[u]), Math.round(vy[u]),
                   Math.round(vx[v]), Math.round(vy[v]));
    }

    private static void drawLegend(Graphics2D g, OverlayMode mode, Font labelFont) {
        int y = 2 * CELL_H - NUM_18;
        int x = NUM_10;
        g.setFont(labelFont);
        if (mode == OverlayMode.STAGES) {
            x = drawSwatch(g, x, y, EDGE_COLOR_DIHEDRAL,  "dihedral");
            x = drawSwatch(g, x, y, EDGE_COLOR_PRINCIPAL, "principal");
            x = drawSwatch(g, x, y, EDGE_COLOR_CREST,     "crest");
            x = drawSwatch(g, x, y, EDGE_COLOR_SADDLE,    SADDLE);
            x = drawSwatch(g, x, y, EDGE_COLOR_MULTI,     "multi-source");
        } else if (mode == OverlayMode.MSC) {
            x = drawSwatch(g, x, y, MSC_MAX_DOT_COLOR,    "max");
            x = drawSwatch(g, x, y, MSC_MIN_DOT_COLOR,    "min");
            x = drawSwatch(g, x, y, MSC_SADDLE_DOT_COLOR, SADDLE);
            x = drawSwatch(g, x, y, MSC_ARC_COLOR,        "integral arc");
        } else {
            x = drawSwatch(g, x, y, EDGE_COLOR_BOUNDARY_ONLY, "boundary only");
            x = drawSwatch(g, x, y, EDGE_COLOR_CREST_ONLY,    "crest ignored");
            x = drawSwatch(g, x, y, EDGE_COLOR_ALIGNED,       "crest honored");
        }
    }

    private static int drawSwatch(Graphics2D g, int x, int y, int rgb, String label) {
        g.setColor(new Color(rgb));
        g.fillRect(x, y, NUM_14, NUM_14);
        g.setColor(Color.BLACK);
        g.drawRect(x, y, NUM_14, NUM_14);
        g.setColor(Color.WHITE);
        g.drawString(label, x + NUM_20, y + NUM_12);
        return x + NUM_28 + g.getFontMetrics().stringWidth(label);
    }

    private static MultiviewResult renderMultiviewImpl(ArrayMesh mesh, PatchDecomposition decomposition, boolean flat, float zoom) {
        int faceCount = mesh.copyFaceIndices().length / NUM_3_2;
        int[] facePatchColor = flat
                ? buildFlatFaceColors(faceCount, decomposition)
                : buildFaceColors(faceCount, decomposition);

        // Flat-mode background is pure black so the VLM labeler's pixel
        // sampler never confuses a patch colour with the backdrop — no
        // HSL(h, 0.65, 0.55) colour collides with 0x000000.
        int bg = flat ? MSC_ARC_COLOR : BG_RGB;
        BufferedImage composite = new BufferedImage(NUM_4_2 * CELL_W, 2 * CELL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(bg));
        g.fillRect(0, 0, composite.getWidth(), composite.getHeight());
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, NUM_18);
        g.setFont(labelFont);

        BufferedImage[] perView = new BufferedImage[NUM_8];
        for (int i = 0; i < NUM_8; i++) {
            BufferedImage view = renderSingleView(mesh, facePatchColor, VIEWS[i][0], VIEWS[i][1], flat, bg, zoom);
            perView[i] = view;
            int col = i % NUM_4_2;
            int row = i / NUM_4_2;
            int dx = col * CELL_W;
            int dy = row * CELL_H;
            g.drawImage(view, dx, dy, null);
            int tx = dx + NUM_8;
            int ty = dy + labelFont.getSize() + NUM_4_2;
            g.setColor(new Color(0, 0, 0, NUM_180));
            g.drawString(LABELS[i], tx + 1, ty + 1);
            g.setColor(Color.WHITE);
            g.drawString(LABELS[i], tx, ty);
        }
        g.dispose();
        return new MultiviewResult(composite, perView, LABELS.clone());
    }

    private static int[] buildFaceColors(int faceCount, PatchDecomposition decomposition) {
        int[] out = new int[faceCount];
        Arrays.fill(out, NUM_0xAAAAAA);
        for (Patch p : decomposition.patches()) {
            int rgb = Integer.parseInt(p.color(), NUM_16);
            for (int f : p.faceIndices()) {
                if (f >= 0 && f < faceCount) out[f] = rgb;
            }
        }
        return out;
    }

    private static int[] buildFlatFaceColors(int faceCount, PatchDecomposition decomposition) {
        int[] out = new int[faceCount];
        Arrays.fill(out, NUM_0xAAAAAA);
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
     *
     * @param mesh source mesh
     * @param vertexScalar one scalar value per vertex (length must be at least {@code mesh.vertexCount()})
     * @param scalarMin lower bound of the colour ramp; pass {@link Float#NaN} together with {@code scalarMax} to autoscale
     * @param scalarMax upper bound of the colour ramp; pass {@link Float#NaN} together with {@code scalarMin} to autoscale
     * @param zoom orthographic zoom factor
     * @throws IllegalArgumentException if {@code vertexScalar} is null or shorter than the vertex count
     * @return composite + per-view buffer + labels
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
        float range = Math.max(scalarMax - scalarMin, NUM_1e_6);
        int nv = mesh.vertexCount();
        int[] vertexRgb = new int[nv];
        for (int i = 0; i < nv; i++) {
            float v = (vertexScalar[i] - scalarMin) / range;
            vertexRgb[i] = scalarRampColor(v);
        }
        BufferedImage composite = new BufferedImage(NUM_4_2 * CELL_W, 2 * CELL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(NUM_0x101018));
        g.fillRect(0, 0, composite.getWidth(), composite.getHeight());
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, NUM_18);
        g.setFont(labelFont);

        BufferedImage[] perView = new BufferedImage[NUM_8];
        for (int i = 0; i < NUM_8; i++) {
            BufferedImage view = renderSingleViewScalar(mesh, vertexRgb,
                    VIEWS[i][0], VIEWS[i][1], NUM_0x101018, zoom);
            perView[i] = view;
            int col = i % NUM_4_2;
            int row = i / NUM_4_2;
            int dx = col * CELL_W;
            int dy = row * CELL_H;
            g.drawImage(view, dx, dy, null);
            int tx = dx + NUM_8;
            int ty = dy + labelFont.getSize() + NUM_4_2;
            g.setColor(new Color(0, 0, 0, NUM_180));
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
     *
     * @param v ramp coordinate, clamped to [0, 1]
     * @return packed 0xRRGGBB integer along the indigo→wine→orange→pale-yellow palette
     */
    public static int scalarRampColor(float v) {
        if (v < NUM_0) v = NUM_0; else if (v > NUM_1) v = NUM_1;
        float r, g, b;
        float c0r = NUM_0_05, c0g = NUM_0_05, c0b = NUM_0_20;  // deep indigo
        float c1r = NUM_0_55, c1g = NUM_0_05, c1b = NUM_0_15;  // wine
        float c2r = NUM_0_95, c2g = NUM_0_45, c2b = NUM_0_05;  // orange
        float c3r = NUM_1_00, c3g = NUM_0_95, c3b = NUM_0_70;  // pale yellow
        if (v < NUM_0_33) {
            float t = v / NUM_0_33;
            r = c0r + (c1r - c0r) * t;
            g = c0g + (c1g - c0g) * t;
            b = c0b + (c1b - c0b) * t;
        } else if (v < NUM_0_66) {
            float t = (v - NUM_0_33) / NUM_0_33;
            r = c1r + (c2r - c1r) * t;
            g = c1g + (c2g - c1g) * t;
            b = c1b + (c2b - c1b) * t;
        } else {
            float t = (v - NUM_0_66) / NUM_0_34;
            r = c2r + (c3r - c2r) * t;
            g = c2g + (c3g - c2g) * t;
            b = c2b + (c3b - c2b) * t;
        }
        int ri = Math.round(r * NUM_255);
        int gi = Math.round(g * NUM_255);
        int bi = Math.round(b * NUM_255);
        return (ri << NUM_16) | (gi << NUM_8) | bi;
    }

    private static BufferedImage renderSingleViewScalar(ArrayMesh mesh, int[] vertexRgb,
                                                        float azimuth, float elevation,
                                                        int bg, float zoom) {
        float[] positions = mesh.copyPositions();
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3_2;

        // Centered bounding sphere.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += NUM_3_2) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float cx = (minX + maxX) * NUM_0_5;
        float cy = (minY + maxY) * NUM_0_5;
        float cz = (minZ + maxZ) * NUM_0_5;
        float radius = NUM_0_5 * (float) Math.sqrt(
                (maxX - minX) * (maxX - minX)
              + (maxY - minY) * (maxY - minY)
              + (maxZ - minZ) * (maxZ - minZ));
        if (radius < NUM_1e_6) radius = NUM_1;

        float cosA = (float) Math.cos(azimuth), sinA = (float) Math.sin(azimuth);
        float cosE = (float) Math.cos(elevation), sinE = (float) Math.sin(elevation);
        float fwdX = -sinA * cosE;
        float fwdY = -sinE;
        float fwdZ = -cosA * cosE;
        float rx = -fwdZ, ry = NUM_0, rz = fwdX;
        float rlen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rlen > NUM_1e_6) { rx /= rlen; rz /= rlen; } else { rx = NUM_1; rz = NUM_0; }
        float ux = ry * fwdZ - rz * fwdY;
        float uy = rz * fwdX - rx * fwdZ;
        float uz = rx * fwdY - ry * fwdX;
        float scale = (CELL_W * NUM_0_42 * zoom) / radius;
        float originX = CELL_W * NUM_0_5;
        float originY = CELL_H * NUM_0_5;

        int nv = positions.length / NUM_3_2;
        float[] vx = new float[nv];
        float[] vy = new float[nv];
        float[] vz = new float[nv];
        for (int i = 0; i < nv; i++) {
            float dx = positions[i * NUM_3_2] - cx;
            float dy = positions[i * NUM_3_2 + 1] - cy;
            float dz = positions[i * NUM_3_2 + 2] - cz;
            vx[i] = originX + scale * (dx * rx + dy * ry + dz * rz);
            vy[i] = originY - scale * (dx * ux + dy * uy + dz * uz);
            vz[i] = dx * fwdX + dy * fwdY + dz * fwdZ;
        }

        int[] pixels = new int[CELL_W * CELL_H];
        float[] depth = new float[CELL_W * CELL_H];
        Arrays.fill(pixels, bg);
        Arrays.fill(depth, Float.POSITIVE_INFINITY);

        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3_2];
            int b = faceIdx[f * NUM_3_2 + 1];
            int c = faceIdx[f * NUM_3_2 + 2];
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

    /**
     * Per-pixel barycentric color blend over a triangle — cheaper than a full shader dispatch.
     *
     * @param pixels CELL_W * CELL_H pixel buffer (writeable)
     * @param depth CELL_W * CELL_H depth buffer (smaller is closer)
     * @param ax screen-space x of vertex A
     * @param ay screen-space y of vertex A
     * @param az camera-space depth of vertex A
     * @param ca packed 0xRRGGBB colour at vertex A
     * @param bx screen-space x of vertex B
     * @param by screen-space y of vertex B
     * @param bz camera-space depth of vertex B
     * @param cb packed 0xRRGGBB colour at vertex B
     * @param cxp screen-space x of vertex C
     * @param cyp screen-space y of vertex C
     * @param cz camera-space depth of vertex C
     * @param cc packed 0xRRGGBB colour at vertex C
     */
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
        if (Math.abs(denom) < NUM_1e_8) return;
        float invDenom = NUM_1 / denom;
        int ra = (ca >> NUM_16) & NUM_0xf, ga = (ca >> NUM_8) & NUM_0xf, ba = ca & NUM_0xf;
        int rb = (cb >> NUM_16) & NUM_0xf, gb = (cb >> NUM_8) & NUM_0xf, bb = cb & NUM_0xf;
        int rc = (cc >> NUM_16) & NUM_0xf, gc = (cc >> NUM_8) & NUM_0xf, bc = cc & NUM_0xf;
        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                float px = x + NUM_0_5;
                float py = y + NUM_0_5;
                float w1 = ((by - cyp) * (px - cxp) + (cxp - bx) * (py - cyp)) * invDenom;
                float w2 = ((cyp - ay) * (px - cxp) + (ax - cxp) * (py - cyp)) * invDenom;
                float w3 = NUM_1 - w1 - w2;
                if (w1 < NUM_0 || w2 < NUM_0 || w3 < NUM_0) continue;
                float z = w1 * az + w2 * bz + w3 * cz;
                int idx = y * CELL_W + x;
                if (z < depth[idx]) {
                    depth[idx] = z;
                    int r = Math.round(w1 * ra + w2 * rb + w3 * rc);
                    int g = Math.round(w1 * ga + w2 * gb + w3 * gc);
                    int bl = Math.round(w1 * ba + w2 * bb + w3 * bc);
                    pixels[idx] = (r << NUM_16) | (g << NUM_8) | bl;
                }
            }
        }
    }

    private static BufferedImage renderSingleView(ArrayMesh mesh, int[] facePatchColor,
                                                  float azimuth, float elevation,
                                                  boolean flat, int bg, float zoom) {
        float[] positions = mesh.copyPositions();
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3_2;

        // Centered bounding sphere.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += NUM_3_2) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float cx = (minX + maxX) * NUM_0_5;
        float cy = (minY + maxY) * NUM_0_5;
        float cz = (minZ + maxZ) * NUM_0_5;
        float radius = NUM_0_5 * (float) Math.sqrt(
                (maxX - minX) * (maxX - minX)
              + (maxY - minY) * (maxY - minY)
              + (maxZ - minZ) * (maxZ - minZ));
        if (radius < NUM_1e_6) radius = NUM_1;

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
        float ry = NUM_0;
        float rz = fwdX;
        float rlen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rlen > NUM_1e_6) {
            rx /= rlen;
            rz /= rlen;
        } else {
            rx = NUM_1; rz = NUM_0;
        }
        // up = cross(right, forward)
        float ux = ry * fwdZ - rz * fwdY;
        float uy = rz * fwdX - rx * fwdZ;
        float uz = rx * fwdY - ry * fwdX;

        // Orthographic projection: world point P → (dot(P-c, right), dot(P-c, up), dot(P-c, forward))
        float scale = (CELL_W * NUM_0_42 * zoom) / radius;
        float originX = CELL_W * NUM_0_5;
        float originY = CELL_H * NUM_0_5;

        int[] pixels = new int[CELL_W * CELL_H];
        float[] depth = new float[CELL_W * CELL_H];
        Arrays.fill(pixels, bg);
        Arrays.fill(depth, Float.POSITIVE_INFINITY);

        // Project vertices once.
        int nv = positions.length / NUM_3_2;
        float[] vx = new float[nv];
        float[] vy = new float[nv];
        float[] vz = new float[nv];
        for (int i = 0; i < nv; i++) {
            float dx = positions[i * NUM_3_2] - cx;
            float dy = positions[i * NUM_3_2 + 1] - cy;
            float dz = positions[i * NUM_3_2 + 2] - cz;
            vx[i] = originX + scale * (dx * rx + dy * ry + dz * rz);
            vy[i] = originY - scale * (dx * ux + dy * uy + dz * uz);
            vz[i] = dx * fwdX + dy * fwdY + dz * fwdZ;  // depth (smaller = closer)
        }

        // Rasterize each triangle, flat shaded by face normal · light.
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3_2];
            int b = faceIdx[f * NUM_3_2 + 1];
            int c = faceIdx[f * NUM_3_2 + 2];
            float ex = positions[b * NUM_3_2] - positions[a * NUM_3_2];
            float ey = positions[b * NUM_3_2 + 1] - positions[a * NUM_3_2 + 1];
            float ez = positions[b * NUM_3_2 + 2] - positions[a * NUM_3_2 + 2];
            float gx = positions[c * NUM_3_2] - positions[a * NUM_3_2];
            float gy = positions[c * NUM_3_2 + 1] - positions[a * NUM_3_2 + 1];
            float gz = positions[c * NUM_3_2 + 2] - positions[a * NUM_3_2 + 2];
            float nx = ey * gz - ez * gy;
            float ny = ez * gx - ex * gz;
            float nz = ex * gy - ey * gx;
            float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen < NUM_1e_20) continue;
            nx /= nlen; ny /= nlen; nz /= nlen;
            int base = facePatchColor[f];
            int shaded;
            if (flat) {
                shaded = base;  // no Lambert — each face writes its exact patch colour
            } else {
                float lambert = nx * LIGHT_DIR[0] + ny * LIGHT_DIR[1] + nz * LIGHT_DIR[2];
                lambert = NUM_0_5 + NUM_0_5 * Math.abs(lambert);
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
        int r = (rgb >> NUM_16) & NUM_0xf;
        int g = (rgb >> NUM_8) & NUM_0xf;
        int b = rgb & NUM_0xf;
        lambert = Math.max(NUM_0_35, Math.min(1.0f, lambert));
        r = Math.round(r * lambert);
        g = Math.round(g * lambert);
        b = Math.round(b * lambert);
        return (r << NUM_16) | (g << NUM_8) | b;
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
        if (Math.abs(denom) < NUM_1e_8) return;
        float invDenom = NUM_1 / denom;

        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                float px = x + NUM_0_5;
                float py = y + NUM_0_5;
                float w1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) * invDenom;
                float w2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) * invDenom;
                float w3 = NUM_1 - w1 - w2;
                if (w1 < NUM_0 || w2 < NUM_0 || w3 < NUM_0) continue;
                float z = w1 * az + w2 * bz + w3 * cz;
                int idx = y * CELL_W + x;
                if (z < depth[idx]) {
                    depth[idx] = z;
                    pixels[idx] = color;
                }
            }
        }
    }

    /**
     * Composite plus the 8 individual per-view renders. Emitting the per-view
     * images lets a caller read a specific view (e.g. Right) at native CELL_W
     * × CELL_H without the 4-way horizontal compression of the composite,
     * which matters when looking for fine detail like individual teeth.
     */
    public record MultiviewResult(BufferedImage composite, BufferedImage[] perView, String[] labels) {}

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
    public enum OverlayMode { STAGES, PATCHES_VS_CREST, MSC }

    private enum EdgeCategory { DIHEDRAL_ONLY, PRINCIPAL_ONLY, CREST_ONLY, MULTI }
}
