package ixdar.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.joml.Vector2f;

public class IrregularQuadLayoutGenerator {
    public static final String STR = ":";
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_1_55 = 1.55f;
    public static final int NUM_6 = 6;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_8660254 = 0.8660254f;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_0_35 = 0.35f;
    public static final float NUM_0_65 = 0.65f;

    /**
     * TODO: document {@code generate}.
     *
     * @param targetCities TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param margin TODO: describe
     * @param seed TODO: describe
     * @param relaxIterations TODO: describe
     * @param jitterRatio TODO: describe
     * @return TODO: describe
     */
    public static Layout generate(int targetCities, float width, float height, float margin, long seed,
            int relaxIterations,
            float jitterRatio) {
        int safeTarget = Math.max(NUM_4, targetCities);
        int radius = Math.max(NUM_3, (int) Math.ceil(Math.sqrt(safeTarget)) + 1);
        float triSize = NUM_1 / radius;
        Layout layout = generateTownscaperHex(radius, triSize, seed, relaxIterations);
        fitLayoutToBounds(layout, width, height, margin);
        return layout;
    }

    /**
     * TODO: document {@code generateTownscaperHex}.
     *
     * @param hexRadius TODO: describe
     * @param triangleSize TODO: describe
     * @param seed TODO: describe
     * @param relaxIterations TODO: describe
     * @return TODO: describe
     */
    public static Layout generateTownscaperHex(int hexRadius, float triangleSize, long seed, int relaxIterations) {
        int radius = Math.max(2, hexRadius);
        float size = Math.max(NUM_0_01, triangleSize);
        Random random = new Random(seed);
        float hexWorldRadius = radius * size * NUM_1_55;
        int extent = radius * NUM_3 + NUM_6;

        ArrayList<Vector2f> baseVertices = new ArrayList<>();
        Map<String, Integer> vertexLookup = new HashMap<>();
        ArrayList<Triangle> triangles = new ArrayList<>();

        for (int j = -extent; j <= extent; j++) {
            for (int i = -extent; i <= extent; i++) {
                int v00 = latticeVertexId(i, j, size, hexWorldRadius, baseVertices, vertexLookup);
                int v10 = latticeVertexId(i + 1, j, size, hexWorldRadius, baseVertices, vertexLookup);
                int v01 = latticeVertexId(i, j + 1, size, hexWorldRadius, baseVertices, vertexLookup);
                int v11 = latticeVertexId(i + 1, j + 1, size, hexWorldRadius, baseVertices, vertexLookup);

                if (v00 >= 0 && v10 >= 0 && v01 >= 0
                        && triangleInsideHex(baseVertices.get(v00), baseVertices.get(v10), baseVertices.get(v01),
                                hexWorldRadius)) {
                    triangles.add(new Triangle(v00, v10, v01));
                }
                if (v10 >= 0 && v11 >= 0 && v01 >= 0
                        && triangleInsideHex(baseVertices.get(v10), baseVertices.get(v11), baseVertices.get(v01),
                                hexWorldRadius)) {
                    triangles.add(new Triangle(v10, v11, v01));
                }
            }
        }

        Map<String, ArrayList<Integer>> edgeToTriangles = buildTriangleAdjacency(triangles);
        ArrayList<Integer> triangleOrder = new ArrayList<>();
        for (int i = 0; i < triangles.size(); i++) {
            triangleOrder.add(i);
        }
        Collections.shuffle(triangleOrder, random);
        boolean[] consumed = new boolean[triangles.size()];
        ArrayList<int[]> faces = new ArrayList<>();
        for (int triIdx : triangleOrder) {
            if (consumed[triIdx]) {
                continue;
            }
            Triangle tri = triangles.get(triIdx);
            ArrayList<Integer> neighbors = unconsumedNeighbors(triIdx, tri, triangles, edgeToTriangles, consumed);
            if (!neighbors.isEmpty()) {
                int pairIdx = neighbors.get(random.nextInt(neighbors.size()));
                consumed[triIdx] = true;
                consumed[pairIdx] = true;
                Triangle pair = triangles.get(pairIdx);
                faces.add(orderedFace(unionVertices(tri.vertices(), pair.vertices()), baseVertices));
            } else {
                consumed[triIdx] = true;
                faces.add(orderedFace(tri.vertices(), baseVertices));
            }
        }

        ArrayList<Vector2f> points = new ArrayList<>(baseVertices);
        ArrayList<int[]> quads = subdivideToQuads(points, faces);
        relaxQuadMesh(points, quads, Math.max(0, relaxIterations));
        ArrayList<int[]> edges = uniqueEdges(quads);
        ArrayList<Vector2f> dualPoints = faceCentroids(quads, points);
        float[] stats = computeEdgeStatsByOrientation(points, edges);

        Layout out = new Layout();
        out.points = points;
        out.dualPoints = dualPoints;
        out.edges = edges;
        out.rows = 0;
        out.cols = 0;
        out.horizontalEdgeMean = stats[0];
        out.verticalEdgeMean = stats[1];
        out.horizontalEdgeStdDev = stats[2];
        out.verticalEdgeStdDev = stats[NUM_3];
        return out;
    }

    private static float[] computeEdgeStatsByOrientation(ArrayList<Vector2f> points, ArrayList<int[]> edges) {
        ArrayList<Float> horizontal = new ArrayList<>();
        ArrayList<Float> vertical = new ArrayList<>();
        for (int[] edge : edges) {
            Vector2f pa = points.get(edge[0]);
            Vector2f pb = points.get(edge[1]);
            float len = new Vector2f(pb).sub(pa).length();
            float dx = Math.abs(pb.x - pa.x);
            float dy = Math.abs(pb.y - pa.y);
            if (dx >= dy) {
                horizontal.add(len);
            } else {
                vertical.add(len);
            }
        }
        float horizontalMean = mean(horizontal);
        float verticalMean = mean(vertical);
        float horizontalStdDev = stdDev(horizontal, horizontalMean);
        float verticalStdDev = stdDev(vertical, verticalMean);
        return new float[] { horizontalMean, verticalMean, horizontalStdDev, verticalStdDev };
    }

    private static float mean(ArrayList<Float> values) {
        if (values.isEmpty()) {
            return NUM_0;
        }
        float sum = NUM_0;
        for (float value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static float stdDev(ArrayList<Float> values, float mean) {
        if (values.isEmpty()) {
            return NUM_0;
        }
        float accum = NUM_0;
        for (float value : values) {
            float delta = value - mean;
            accum += delta * delta;
        }
        return (float) Math.sqrt(accum / values.size());
    }

    private static void fitLayoutToBounds(Layout layout, float width, float height, float margin) {
        if (layout == null || layout.points == null || layout.points.isEmpty()) {
            return;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Vector2f p : layout.points) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        float srcW = Math.max(NUM_1e_6, maxX - minX);
        float srcH = Math.max(NUM_1e_6, maxY - minY);
        float dstMinX = margin;
        float dstMinY = margin;
        float dstMaxX = Math.max(dstMinX + NUM_1, width - margin);
        float dstMaxY = Math.max(dstMinY + NUM_1, height - margin);
        float dstW = dstMaxX - dstMinX;
        float dstH = dstMaxY - dstMinY;
        float scale = Math.min(dstW / srcW, dstH / srcH);
        float padX = (dstW - (srcW * scale)) * NUM_0_5;
        float padY = (dstH - (srcH * scale)) * NUM_0_5;
        transformPoints(layout.points, minX, minY, dstMinX + padX, dstMinY + padY, scale);
        if (layout.dualPoints != null) {
            transformPoints(layout.dualPoints, minX, minY, dstMinX + padX, dstMinY + padY, scale);
        }
    }

    private static void transformPoints(ArrayList<Vector2f> points, float srcMinX, float srcMinY, float dstMinX,
            float dstMinY,
            float scale) {
        for (Vector2f p : points) {
            p.x = dstMinX + ((p.x - srcMinX) * scale);
            p.y = dstMinY + ((p.y - srcMinY) * scale);
        }
    }

    private static int latticeVertexId(int i, int j, float triangleSize, float hexWorldRadius,
            ArrayList<Vector2f> points,
            Map<String, Integer> lookup) {
        String key = i + STR + j;
        Integer existing = lookup.get(key);
        if (existing != null) {
            return existing;
        }
        Vector2f p = latticePoint(i, j, triangleSize);
        if (!insideHex(p, hexWorldRadius)) {
            return -1;
        }
        int id = points.size();
        points.add(p);
        lookup.put(key, id);
        return id;
    }

    private static Vector2f latticePoint(int i, int j, float triangleSize) {
        float x = triangleSize * (i + (NUM_0_5 * j));
        float y = triangleSize * (NUM_0_8660254 * j);
        return new Vector2f(x, y);
    }

    private static boolean triangleInsideHex(Vector2f a, Vector2f b, Vector2f c, float hexRadius) {
        Vector2f centroid = new Vector2f(a).add(b).add(c).mul(NUM_1 / NUM_3_2);
        return insideHex(centroid, hexRadius);
    }

    private static boolean insideHex(Vector2f p, float r) {
        float h = NUM_0_8660254 * r;
        Vector2f[] hex = new Vector2f[] {
                new Vector2f(r, NUM_0),
                new Vector2f(r * NUM_0_5, h),
                new Vector2f(-r * NUM_0_5, h),
                new Vector2f(-r, NUM_0),
                new Vector2f(-r * NUM_0_5, -h),
                new Vector2f(r * NUM_0_5, -h)
        };
        return insideConvexPolygon(p, hex);
    }

    private static boolean insideConvexPolygon(Vector2f p, Vector2f[] poly) {
        float sign = NUM_0;
        for (int i = 0; i < poly.length; i++) {
            Vector2f a = poly[i];
            Vector2f b = poly[(i + 1) % poly.length];
            float cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
            if (Math.abs(cross) < NUM_1e_6) {
                continue;
            }
            if (sign == NUM_0) {
                sign = cross > NUM_0 ? NUM_1 : -NUM_1;
            } else if ((cross > NUM_0 ? NUM_1 : -NUM_1) != sign) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, ArrayList<Integer>> buildTriangleAdjacency(ArrayList<Triangle> triangles) {
        Map<String, ArrayList<Integer>> edgeToTriangles = new HashMap<>();
        for (int i = 0; i < triangles.size(); i++) {
            Triangle tri = triangles.get(i);
            addTriEdge(edgeToTriangles, tri.a, tri.b, i);
            addTriEdge(edgeToTriangles, tri.b, tri.c, i);
            addTriEdge(edgeToTriangles, tri.c, tri.a, i);
        }
        return edgeToTriangles;
    }

    private static void addTriEdge(Map<String, ArrayList<Integer>> edgeToTriangles, int a, int b, int triIdx) {
        String key = edgeKey(a, b);
        edgeToTriangles.computeIfAbsent(key, k -> new ArrayList<>()).add(triIdx);
    }

    private static ArrayList<Integer> unconsumedNeighbors(int triIdx, Triangle tri, ArrayList<Triangle> triangles,
            Map<String, ArrayList<Integer>> edgeToTriangles, boolean[] consumed) {
        ArrayList<Integer> neighbors = new ArrayList<>();
        int[][] edges = new int[][] { { tri.a, tri.b }, { tri.b, tri.c }, { tri.c, tri.a } };
        for (int[] edge : edges) {
            ArrayList<Integer> incident = edgeToTriangles.get(edgeKey(edge[0], edge[1]));
            if (incident == null) {
                continue;
            }
            for (int candidate : incident) {
                if (candidate != triIdx && !consumed[candidate]) {
                    neighbors.add(candidate);
                }
            }
        }
        return neighbors;
    }

    private static int[] unionVertices(int[] first, int[] second) {
        Set<Integer> unique = new HashSet<>();
        for (int v : first) {
            unique.add(v);
        }
        for (int v : second) {
            unique.add(v);
        }
        int[] out = new int[unique.size()];
        int i = 0;
        for (int v : unique) {
            out[i++] = v;
        }
        return out;
    }

    private static int[] orderedFace(int[] faceVertices, ArrayList<Vector2f> points) {
        Vector2f centroid = new Vector2f();
        for (int idx : faceVertices) {
            centroid.add(points.get(idx));
        }
        centroid.mul(NUM_1 / faceVertices.length);
        ArrayList<Integer> indices = new ArrayList<>();
        for (int v : faceVertices) {
            indices.add(v);
        }
        indices.sort((a, b) -> {
            Vector2f pa = points.get(a);
            Vector2f pb = points.get(b);
            double aa = Math.atan2(pa.y - centroid.y, pa.x - centroid.x);
            double ab = Math.atan2(pb.y - centroid.y, pb.x - centroid.x);
            return Double.compare(aa, ab);
        });
        int[] ordered = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            ordered[i] = indices.get(i);
        }
        return ordered;
    }

    private static ArrayList<int[]> subdivideToQuads(ArrayList<Vector2f> points, ArrayList<int[]> faces) {
        ArrayList<int[]> quads = new ArrayList<>();
        Map<String, Integer> midpointByEdge = new HashMap<>();
        for (int[] face : faces) {
            Vector2f center = new Vector2f();
            for (int v : face) {
                center.add(points.get(v));
            }
            center.mul(NUM_1 / face.length);
            int centerIdx = points.size();
            points.add(center);

            int[] edgeMid = new int[face.length];
            for (int i = 0; i < face.length; i++) {
                int a = face[i];
                int b = face[(i + 1) % face.length];
                String key = edgeKey(a, b);
                Integer midIdx = midpointByEdge.get(key);
                if (midIdx == null) {
                    Vector2f mid = new Vector2f(points.get(a)).add(points.get(b)).mul(NUM_0_5);
                    midIdx = points.size();
                    points.add(mid);
                    midpointByEdge.put(key, midIdx);
                }
                edgeMid[i] = midIdx;
            }

            for (int i = 0; i < face.length; i++) {
                int vertex = face[i];
                int nextMid = edgeMid[i];
                int prevMid = edgeMid[(i - 1 + face.length) % face.length];
                quads.add(new int[] { vertex, nextMid, centerIdx, prevMid });
            }
        }
        return quads;
    }

    private static void relaxQuadMesh(ArrayList<Vector2f> points, ArrayList<int[]> quads, int iterations) {
        if (iterations <= 0 || points.isEmpty()) {
            return;
        }
        Map<String, Integer> edgeUses = new HashMap<>();
        ArrayList<Set<Integer>> neighbors = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            neighbors.add(new HashSet<>());
        }
        for (int[] quad : quads) {
            for (int i = 0; i < quad.length; i++) {
                int a = quad[i];
                int b = quad[(i + 1) % quad.length];
                neighbors.get(a).add(b);
                neighbors.get(b).add(a);
                String key = edgeKey(a, b);
                edgeUses.put(key, edgeUses.getOrDefault(key, 0) + 1);
            }
        }
        boolean[] boundary = new boolean[points.size()];
        for (Map.Entry<String, Integer> entry : edgeUses.entrySet()) {
            if (entry.getValue() == 1) {
                int[] edge = parseEdgeKey(entry.getKey());
                boundary[edge[0]] = true;
                boundary[edge[1]] = true;
            }
        }
        for (int iter = 0; iter < iterations; iter++) {
            ArrayList<Vector2f> next = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                Vector2f current = points.get(i);
                if (boundary[i] || neighbors.get(i).isEmpty()) {
                    next.add(new Vector2f(current));
                    continue;
                }
                Vector2f avg = new Vector2f();
                for (int n : neighbors.get(i)) {
                    avg.add(points.get(n));
                }
                avg.mul(NUM_1 / neighbors.get(i).size());
                next.add(new Vector2f(current).mul(NUM_0_35).add(avg.mul(NUM_0_65)));
            }
            points.clear();
            points.addAll(next);
        }
    }

    private static ArrayList<int[]> uniqueEdges(ArrayList<int[]> quads) {
        Set<String> keys = new HashSet<>();
        ArrayList<int[]> edges = new ArrayList<>();
        for (int[] quad : quads) {
            for (int i = 0; i < quad.length; i++) {
                int a = quad[i];
                int b = quad[(i + 1) % quad.length];
                String key = edgeKey(a, b);
                if (keys.add(key)) {
                    edges.add(new int[] { Math.min(a, b), Math.max(a, b) });
                }
            }
        }
        return edges;
    }

    private static ArrayList<Vector2f> faceCentroids(ArrayList<int[]> faces, ArrayList<Vector2f> points) {
        ArrayList<Vector2f> centroids = new ArrayList<>();
        for (int[] face : faces) {
            Vector2f center = new Vector2f();
            for (int v : face) {
                center.add(points.get(v));
            }
            center.mul(NUM_1 / face.length);
            centroids.add(center);
        }
        return centroids;
    }

    private static String edgeKey(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        return min + STR + max;
    }

    private static int[] parseEdgeKey(String key) {
        int split = key.indexOf(':');
        int a = Integer.parseInt(key.substring(0, split));
        int b = Integer.parseInt(key.substring(split + 1));
        return new int[] { a, b };
    }

    public static class Layout {
        public ArrayList<Vector2f> points;
        public ArrayList<Vector2f> dualPoints;
        public ArrayList<int[]> edges;
        public int rows;
        public int cols;
        public float horizontalEdgeMean;
        public float verticalEdgeMean;
        public float horizontalEdgeStdDev;
        public float verticalEdgeStdDev;
    }

    private static class Triangle {
        int a;
        int b;
        int c;

        Triangle(int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        int[] vertices() {
            return new int[] { a, b, c };
        }
    }

}
