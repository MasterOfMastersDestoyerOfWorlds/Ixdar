package ixdar.graphics.render.color;

import java.util.Arrays;

/**
 * Palette colours for the nodes of an adjacency graph (ring regions, layout patches): nodes that
 * neighbour each other always take different entries, and a node an update left alone keeps its
 * colour.
 */
public final class RegionColouring {

    public static final double MINIMUM_PALETTE_DISTANCE = 0.45;

    public static final int UNCOLOURED = -1;

    /**
     * Colour of each node: an index into the cycle last coloured with, or past it a shade that
     * {@link Palette#shadedEntry} draws.
     */
    public int[] colourByNode = new int[0];

    /**
     * Colour the nodes: those that kept their content keep their colour, a changed node keeps its
     * former node's colour unless a neighbour already holds it, and the rest go greedily, most
     * neighbours first (Welsh and Powell), to the least used entry no neighbour uses.
     *
     * @param neighboursByNode nodes adjacent to each node
     * @param sizeByNode       size of each node, larger nodes claiming former colours first
     * @param formerNodeByNode node of the last colouring each node continues, negative for none,
     *                         or {@code null} for a fresh colouring
     * @param keptByNode       whether each node is unchanged since the last colouring, or
     *                         {@code null} for none
     * @param cycle            the palette cycle whose entries are handed out
     */
    public void colour(int[][] neighboursByNode, int[] sizeByNode, int[] formerNodeByNode,
            boolean[] keptByNode, Color[] cycle) {
        int[] previous = colourByNode;
        int count = neighboursByNode.length;
        int[] next = new int[count];
        Arrays.fill(next, UNCOLOURED);
        int[] former = new int[count];
        Arrays.fill(former, UNCOLOURED);
        if (formerNodeByNode != null) {
            System.arraycopy(formerNodeByNode, 0, former, 0, count);
        }
        Integer[] bySize = new Integer[count];
        for (int node = 0; node < count; node++) {
            bySize[node] = node;
            if (keptByNode != null && keptByNode[node] && former[node] >= 0
                    && former[node] < previous.length) {
                next[node] = previous[former[node]];
            }
        }
        Arrays.sort(bySize, (first, second) -> sizeByNode[first] != sizeByNode[second]
                ? Integer.compare(sizeByNode[second], sizeByNode[first])
                : Integer.compare(first, second));
        for (int node : bySize) {
            if (next[node] != UNCOLOURED || former[node] < 0 || former[node] >= previous.length) {
                continue;
            }
            boolean taken = false;
            for (int neighbour : neighboursByNode[node]) {
                taken |= next[neighbour] == previous[former[node]];
            }
            next[node] = taken ? UNCOLOURED : previous[former[node]];
        }
        Integer[] byDegree = bySize.clone();
        Arrays.sort(byDegree, (first, second) -> neighboursByNode[first].length
                != neighboursByNode[second].length
                        ? Integer.compare(neighboursByNode[second].length,
                                neighboursByNode[first].length)
                        : 0);
        // How many nodes hold each entry, so a free node takes the least used palette entry its
        // neighbours leave free and the whole palette gets used, not just its first four entries.
        int[] uses = new int[cycle.length + count];
        for (int node = 0; node < count; node++) {
            if (next[node] != UNCOLOURED) {
                uses[next[node]]++;
            }
        }
        boolean[] used = new boolean[uses.length];
        for (int node : byDegree) {
            if (next[node] != UNCOLOURED) {
                continue;
            }
            Arrays.fill(used, false);
            for (int neighbour : neighboursByNode[node]) {
                if (next[neighbour] != UNCOLOURED) {
                    used[next[neighbour]] = true;
                }
            }
            int chosen = UNCOLOURED;
            for (int entry = 0; entry < cycle.length; entry++) {
                if (!used[entry] && (chosen == UNCOLOURED || uses[entry] < uses[chosen])) {
                    chosen = entry;
                }
            }
            // Only a node whose neighbours hold every palette entry goes past it, to a shade.
            if (chosen == UNCOLOURED) {
                chosen = cycle.length;
                while (used[chosen]) {
                    chosen++;
                }
            }
            next[node] = chosen;
            uses[chosen]++;
        }
        colourByNode = next;
    }
}
