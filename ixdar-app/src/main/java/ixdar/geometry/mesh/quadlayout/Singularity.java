package ixdar.geometry.mesh.quadlayout;

/**
 * A 4-RoSy field singularity at vertex {@code vertexId}. Index is stored as
 * {@code index4} = 4 x (true index), so values are integer multiples of 1/4
 * (e.g. index4=+1 means +1/4, index4=-1 means -1/4, index4=+2 means +1/2).
 *
 * <p>Poincare-Hopf: sum of (index4) over all singularities equals
 * 4 x chi(M).
 */
public record Singularity(int vertexId, int index4) {
    public static final double NUM_4_0 = 4.0;
    public double index() { return index4 / NUM_4_0; }
}
