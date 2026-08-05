package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Triangulates one source face several arcs cross, cutting it along every chord at
 * once and ear-clipping the regions between them, so the face interior gains no
 * vertex at all.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class SourceFaceTriangulation {

    /** Corners of a triangle. */
    public static final int CORNERS = 3;

    /** Local vertex index meaning "no vertex". */
    public static final int NONE = -1;

    /** Barycentric of each local vertex in the source face, indexed by local index. */
    public final double[][] barycentric;

    /** Local indices around the face's boundary, in the face's own winding. */
    public final int[] boundaryCycle;

    /** Local index each chord runs from, indexed by chord. */
    public final int[] chordFrom;

    /** Local index each chord runs to, indexed by chord. */
    public final int[] chordTo;

    /**
     * Owner of each chord, indexed by chord. Two chords meeting at an interior vertex
     * pair into one cut only when they share an owner, which is what keeps a crossing
     * of two arcs from being cut as a single bent curve.
     */
    public final int[] chordOwner;

    /** Triangles produced, as local index triples wound like the face. */
    public final List<int[]> triangles = new ArrayList<>();

    /** Cuts inserted, each a chord chain running boundary to boundary. */
    public int cutCount;

    /**
     * Stores one face's arrangement: its boundary and the chords crossing it.
     *
     * @param barycentric   barycentric of each local vertex in the source face
     * @param boundaryCycle local indices around the boundary, in the face's winding
     * @param chordFrom     local index each chord runs from
     * @param chordTo       local index each chord runs to
     * @param chordOwner    owner of each chord, pairing chords at interior vertices
     */
    public SourceFaceTriangulation(double[][] barycentric, int[] boundaryCycle, int[] chordFrom,
            int[] chordTo, int[] chordOwner) {
        this.barycentric = barycentric;
        this.boundaryCycle = boundaryCycle;
        this.chordFrom = chordFrom;
        this.chordTo = chordTo;
        this.chordOwner = chordOwner;
    }

    /**
     * Cuts the face along every chord chain, then ear-clips each region.
     *
     * @throws IllegalStateException when a chain does not run between two vertices of
     *                               one region, so the chords are not the
     *                               non-crossing family this construction needs
     * @return this, triangulated
     */
    public SourceFaceTriangulation build() {
        List<List<Integer>> regions = new ArrayList<>();
        List<Integer> start = new ArrayList<>(boundaryCycle.length);
        for (int local : boundaryCycle) {
            start.add(local);
        }
        regions.add(start);
        List<List<Integer>> pending = chains();
        while (!pending.isEmpty()) {
            List<List<Integer>> deferred = new ArrayList<>();
            for (List<Integer> chain : pending) {
                if (cut(regions, chain)) {
                    cutCount++;
                } else {
                    deferred.add(chain);
                }
            }
            if (deferred.size() == pending.size()) {
                throw new IllegalStateException("no region holds both ends of any of " + deferred
                        + "; the chords crossing this face are not a non-crossing family");
            }
            pending = deferred;
        }
        for (List<Integer> region : regions) {
            earClip(region);
        }
        return this;
    }

    /**
     * Links the chords into chains that run from one boundary vertex to another,
     * pairing at an interior vertex only chords of the same owner.
     *
     * @return each chain as its sequence of local indices
     */
    private List<List<Integer>> chains() {
        boolean[] used = new boolean[chordFrom.length];
        List<List<Integer>> chains = new ArrayList<>();
        for (int seed = 0; seed < chordFrom.length; seed++) {
            if (used[seed]) {
                continue;
            }
            used[seed] = true;
            List<Integer> chain = new ArrayList<>();
            chain.add(chordFrom[seed]);
            chain.add(chordTo[seed]);
            extend(chain, seed, used);
            Collections.reverse(chain);
            extend(chain, seed, used);
            chains.add(chain);
        }
        return chains;
    }

    /**
     * Walks a chain forward from its last vertex, taking chords of the same owner
     * until none continues.
     *
     * @param chain   chain so far, extended in place
     * @param arriving chord the chain's owner is taken from
     * @param used    chords already taken into a chain
     */
    private void extend(List<Integer> chain, int arriving, boolean[] used) {
        int head = chain.get(chain.size() - 1);
        while (true) {
            int next = continuation(arriving, head, used);
            if (next == NONE || chain.contains(
                    chordFrom[next] == head ? chordTo[next] : chordFrom[next])) {
                return;
            }
            used[next] = true;
            head = chordFrom[next] == head ? chordTo[next] : chordFrom[next];
            chain.add(head);
        }
    }

    /**
     * The unused chord continuing a chain through an interior vertex: the other chord
     * of the same owner meeting there.
     *
     * @param arriving chord the chain arrived on
     * @param at       interior local vertex the chain is passing through
     * @param used     chords already taken into a chain
     * @return the continuing chord, or {@link #NONE} when none carries on
     */
    private int continuation(int arriving, int at, boolean[] used) {
        for (int chord = 0; chord < chordFrom.length; chord++) {
            if (used[chord] || chordOwner[chord] != chordOwner[arriving]) {
                continue;
            }
            if (chordFrom[chord] == at || chordTo[chord] == at) {
                return chord;
            }
        }
        return NONE;
    }

    /**
     * Splits the one region holding a chain's two ends into the two regions either
     * side of it, threading the chain's interior vertices onto both.
     *
     * @param regions regions so far, replaced in place
     * @param chain   local indices from one boundary vertex to another
     * @return whether a region held both ends, so the cut was made
     */
    private boolean cut(List<List<Integer>> regions, List<Integer> chain) {
        int from = chain.get(0);
        int to = chain.get(chain.size() - 1);
        for (int index = 0; index < regions.size(); index++) {
            List<Integer> region = regions.get(index);
            int at = region.indexOf(from);
            int across = region.indexOf(to);
            if (at < 0 || across < 0) {
                continue;
            }
            List<Integer> forward = new ArrayList<>();
            for (int step = at; step != across; step = (step + 1) % region.size()) {
                forward.add(region.get(step));
            }
            for (int step = chain.size() - 1; step >= 1; step--) {
                forward.add(chain.get(step));
            }
            List<Integer> backward = new ArrayList<>();
            for (int step = across; step != at; step = (step + 1) % region.size()) {
                backward.add(region.get(step));
            }
            for (int step = 0; step <= chain.size() - 2; step++) {
                backward.add(chain.get(step));
            }
            regions.set(index, forward);
            regions.add(backward);
            return true;
        }
        return false;
    }

    /**
     * Ear-clips one region, testing ears with the exact orientation predicate so a
     * region bent at an interior vertex is triangulated without inverting anything.
     *
     * @param region local indices around the region, in the face's winding
     * @throws IllegalStateException when no ear can be found, so the region is not a
     *                               simple polygon
     */
    private void earClip(List<Integer> region) {
        List<Integer> remaining = new ArrayList<>(region);
        while (remaining.size() > CORNERS) {
            int clipped = NONE;
            for (int corner = 0; corner < remaining.size(); corner++) {
                int previous = remaining.get((corner + remaining.size() - 1) % remaining.size());
                int at = remaining.get(corner);
                int next = remaining.get((corner + 1) % remaining.size());
                if (!isEar(remaining, previous, at, next)) {
                    continue;
                }
                triangles.add(new int[] { previous, at, next });
                remaining.remove(corner);
                clipped = at;
                break;
            }
            if (clipped == NONE) {
                throw new IllegalStateException("region " + remaining + " has no ear; the cut"
                        + " produced a polygon that is not simple");
            }
        }
        if (remaining.size() == CORNERS) {
            triangles.add(new int[] { remaining.get(0), remaining.get(1), remaining.get(2) });
        }
    }

    /**
     * Whether three consecutive region vertices form an ear: wound like the face, and
     * containing no other vertex of the region.
     *
     * @param remaining region vertices still to be clipped
     * @param previous  local index before the candidate
     * @param at        candidate local index
     * @param next      local index after the candidate
     * @return true when the triangle may be clipped off
     */
    private boolean isEar(List<Integer> remaining, int previous, int at, int next) {
        if (ExactBarycentricOrient.sign(barycentric[previous], barycentric[at],
                barycentric[next]) <= 0) {
            return false;
        }
        for (int other : remaining) {
            if (other == previous || other == at || other == next) {
                continue;
            }
            if (ExactBarycentricOrient.sign(barycentric[previous], barycentric[at],
                            barycentric[other]) >= 0
                    && ExactBarycentricOrient.sign(barycentric[at], barycentric[next],
                            barycentric[other]) >= 0
                    && ExactBarycentricOrient.sign(barycentric[next], barycentric[previous],
                            barycentric[other]) >= 0) {
                return false;
            }
        }
        return true;
    }
}
