package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * A hand-authored T-mesh layout a model scene can load from its model menu the way it loads a
 * mesh file. {@link #build()} returns fresh state every call, so reloading is resetting.
 */
public interface LayoutFixture {

    /**
     * Name the model menu lists this fixture under.
     *
     * @return a short display name
     */
    String displayName();

    /**
     * Builds the fixture's layout from scratch: a fresh carrier surface, topology and T-mesh,
     * with no state shared with any earlier build.
     *
     * @return the freshly built T-mesh; its carrier surface is {@code tmesh.topology.copy}
     */
    EmbeddedTMesh build();
}
