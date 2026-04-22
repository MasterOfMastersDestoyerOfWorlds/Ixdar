package ixdar.geometry.mesh.data;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Self-contained software rasterizer for rendering a mesh with per-face patch
 * colors from eight canonical angles and compositing them into a 4x2 grid PNG.
 * Independent of the running game viewer so it works in tests and in headless
 * preprocessing runs.
 */
public final class PatchRenderer {

    private static final int CELL_W = 512;
    private static final int CELL_H = 512;
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

    public static BufferedImage renderMultiview(ArrayMesh mesh, PatchDecomposition decomposition) {
        return renderMultiviewImpl(mesh, decomposition, /*flat=*/ false);
    }

    /**
     * Flat-shaded render: every face is written with a globally-unique RGB
     * derived from its patch id (golden-ratio hue), no Lambert shading.
     * A VLM labeler can pixel-sample this image to determine which patch
     * covers any (x, y) point exactly, without the ambiguity Lambert shading
     * introduces when two palette colours look similar at grazing angles.
     */
    public static BufferedImage renderMultiviewFlat(ArrayMesh mesh, PatchDecomposition decomposition) {
        return renderMultiviewImpl(mesh, decomposition, /*flat=*/ true);
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

    private static BufferedImage renderMultiviewImpl(ArrayMesh mesh, PatchDecomposition decomposition, boolean flat) {
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

        for (int i = 0; i < 8; i++) {
            BufferedImage view = renderSingleView(mesh, facePatchColor, VIEWS[i][0], VIEWS[i][1], flat, bg);
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
        return composite;
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

    private static BufferedImage renderSingleView(ArrayMesh mesh, int[] facePatchColor,
                                                  float azimuth, float elevation,
                                                  boolean flat, int bg) {
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
        float scale = (CELL_W * 0.42f) / radius;
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
