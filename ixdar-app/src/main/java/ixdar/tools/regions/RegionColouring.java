package ixdar.tools.regions;

import java.util.Arrays;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.graphics.render.color.Palettes;

/**
 * Palette colours for ring regions: regions across a ring edge from each other always take
 * different entries, and a region an update left alone keeps its colour.
 */
public final class RegionColouring {

    public static final double MINIMUM_PALETTE_DISTANCE = 0.45;

    /**
     * Colour of each region: an index into {@link Palettes#REGION}, or past it a shade that
     * {@link ixdar.graphics.render.color.Palette#shadedColor} draws.
     */
    public int[] colourByRegion = new int[0];

    /**
     * Colour the regions: those that kept their faces keep their colour, a changed region keeps
     * its former region's colour unless a neighbour already holds it, and the rest go greedily,
     * most neighbours first (Welsh and Powell), to the least used entry no neighbour uses.
     *
     * @param regions regions just built or updated, whose former regions index the last colouring
     */
    public void colour(RingRegions regions) {
        int[] previous = colourByRegion;
        int count = regions.regionCount;
        int[] next = new int[count];
        Arrays.fill(next, MeshTopology.NONE);
        Integer[] bySize = new Integer[count];
        for (int region = 0; region < count; region++) {
            bySize[region] = region;
            int former = regions.formerRegionByRegion[region];
            if (regions.regionKeptFaces[region] && former >= 0 && former < previous.length) {
                next[region] = previous[former];
            }
        }
        Arrays.sort(bySize, (first, second) -> regions.regionFaceCount[first]
                != regions.regionFaceCount[second]
                        ? Integer.compare(regions.regionFaceCount[second],
                                regions.regionFaceCount[first])
                        : Integer.compare(first, second));
        for (int region : bySize) {
            int former = regions.formerRegionByRegion[region];
            if (next[region] != MeshTopology.NONE || former < 0 || former >= previous.length) {
                continue;
            }
            boolean taken = false;
            for (int neighbour : regions.neighboursByRegion[region]) {
                taken |= next[neighbour] == previous[former];
            }
            next[region] = taken ? MeshTopology.NONE : previous[former];
        }
        Integer[] byDegree = bySize.clone();
        Arrays.sort(byDegree, (first, second) -> regions.neighboursByRegion[first].length
                != regions.neighboursByRegion[second].length
                        ? Integer.compare(regions.neighboursByRegion[second].length,
                                regions.neighboursByRegion[first].length)
                        : 0);
        // How many regions hold each entry, so a free region takes the least used palette entry its
        // neighbours leave free and the whole palette gets used, not just its first four entries.
        int[] uses = new int[Palettes.REGION.size() + count];
        for (int region = 0; region < count; region++) {
            if (next[region] != MeshTopology.NONE) {
                uses[next[region]]++;
            }
        }
        boolean[] used = new boolean[uses.length];
        for (int region : byDegree) {
            if (next[region] != MeshTopology.NONE) {
                continue;
            }
            Arrays.fill(used, false);
            for (int neighbour : regions.neighboursByRegion[region]) {
                if (next[neighbour] != MeshTopology.NONE) {
                    used[next[neighbour]] = true;
                }
            }
            int chosen = MeshTopology.NONE;
            for (int entry = 0; entry < Palettes.REGION.size(); entry++) {
                if (!used[entry] && (chosen == MeshTopology.NONE || uses[entry] < uses[chosen])) {
                    chosen = entry;
                }
            }
            // Only a region whose neighbours hold every palette entry goes past it, to a shade.
            if (chosen == MeshTopology.NONE) {
                chosen = Palettes.REGION.size();
                while (used[chosen]) {
                    chosen++;
                }
            }
            next[region] = chosen;
            uses[chosen]++;
        }
        colourByRegion = next;
    }
}
