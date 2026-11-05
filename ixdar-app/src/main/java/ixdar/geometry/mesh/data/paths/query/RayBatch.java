package ixdar.geometry.mesh.data.paths.query;

import java.util.stream.IntStream;

/**
 * Casts many independent rays against one triangle tree in parallel: each worker keeps its own
 * scratch (corner marks, node stack, ray), allocated once and reused across batches.
 */
public final class RayBatch {

    /** One worker's scratch. */
    public static final class Rays {

        /** Mark by vertex id, for {@link TriangleBvh#nearestHitAvoiding}. */
        public final int[] markById;

        /** The last mark handed out; bump it before marking a ray's own corners. */
        public int mark;

        /** Node stack for the tree's searches. */
        public final int[] stack;

        /** The ray's origin xyz. */
        public final float[] origin = new float[3];

        /** The ray's direction xyz. */
        public final float[] direction = new float[3];

        /** The last search's hit parameter, in its first entry. */
        public final double[] parameter = new double[1];

        /**
         * Scratch for one worker.
         *
         * @param tree     the tree searched
         * @param idBound  the vertex id bound marks are kept to
         */
        Rays(TriangleBvh tree, int idBound) {
            markById = new int[idBound];
            stack = new int[Math.max(1, tree.nodeCount)];
        }
    }

    /** The work for one index, given the worker's scratch. */
    @FunctionalInterface
    public interface Body {

        /**
         * Cast the rays of one index.
         *
         * @param rays  the worker's scratch
         * @param index the index
         */
        void cast(Rays rays, int index);
    }

    /** The tree every ray is cast against; read-only while a batch runs. */
    public final TriangleBvh tree;

    private final Rays[] workers;

    /**
     * Scratch for as many workers as the machine has cores.
     *
     * @param tree    the tree searched
     * @param idBound the vertex id bound marks are kept to
     */
    public RayBatch(TriangleBvh tree, int idBound) {
        this.tree = tree;
        workers = new Rays[Math.max(1, Runtime.getRuntime().availableProcessors())];
        for (int worker = 0; worker < workers.length; worker++) {
            workers[worker] = new Rays(tree, idBound);
        }
    }

    /**
     * Run a body over indices, each worker taking every n-th; bodies must write only their own
     * index's results.
     *
     * @param count how many indices
     * @param body  the work for one index
     */
    public void forEach(int count, Body body) {
        IntStream.range(0, workers.length).parallel().forEach(worker -> {
            Rays rays = workers[worker];
            for (int index = worker; index < count; index += workers.length) {
                body.cast(rays, index);
            }
        });
    }
}
