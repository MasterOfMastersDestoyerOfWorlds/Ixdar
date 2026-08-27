package ixdar.geometry.mesh.nodes.api;

/**
 * Atlas of charts covering a surface: the value behind
 * {@link PortType#CHART_ATLAS} ports. Producers include the seamless cut graph
 * (per-face charts) and the integer grid map's patch rectangle maps.
 */
public interface ChartAtlasValue {

    /**
     * The number of charts in the atlas.
     *
     * @return chart count
     */
    int chartCount();
}
