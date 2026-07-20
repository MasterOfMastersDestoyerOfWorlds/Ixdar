package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.List;
import java.util.Set;

/**
 * Thrown when {@link EmbeddedTMesh#dragArcEndOntoVertex} cannot re-route an arc onto the survivor
 * node of a zero-arc collapse. It carries the geometry of the wall so a scene can render it: the
 * arc that could not move, the collapsing pivot and the survivor vertices, the freed channel, and
 * the two disconnected unclaimed regions the router was stuck between — the arc's body region and
 * the survivor's channel region, joined only through the pivot.
 *
 * <p>It extends {@link IllegalStateException} so callers that only catch the general failure (and
 * tests that assert one) are unaffected; callers that want the structured context catch this type.
 */
public final class ArcRerouteFailure extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public final int arcId;
    public final int pivotVertex;
    public final int survivorVertex;
    public final List<Integer> arcBody;
    public final List<Integer> channel;
    public final Set<Integer> bodyComponent;
    public final Set<Integer> channelComponent;
    public final Set<Integer> fenceVertices;
    public final List<Integer> pivotSpokes;

    /**
     * Records the failure context.
     *
     * @param message          the diagnostic message
     * @param arcId            the arc that could not be re-routed
     * @param pivotVertex      the collapsing node's copy vertex (n0)
     * @param survivorVertex   the survivor node's copy vertex (n1)
     * @param arcBody          the failing arc's copy-vertex path, oriented to end at the pivot
     * @param channel          the freed collapsing-arc channel, from pivot toward survivor
     * @param bodyComponent    the unclaimed region containing the arc's body
     * @param channelComponent the unclaimed region reaching the survivor
     * @param fenceVertices    the claimed vertices ringing the body region — the wall the router
     *                         may not step through
     * @param pivotSpokes      the pivot's unclaimed incident edges, as flat vertex pairs — where
     *                         the router may legally step off the pivot
     */
    public ArcRerouteFailure(String message, int arcId, int pivotVertex, int survivorVertex,
            List<Integer> arcBody, List<Integer> channel, Set<Integer> bodyComponent,
            Set<Integer> channelComponent, Set<Integer> fenceVertices, List<Integer> pivotSpokes) {
        super(message);
        this.arcId = arcId;
        this.pivotVertex = pivotVertex;
        this.survivorVertex = survivorVertex;
        this.arcBody = arcBody;
        this.channel = channel;
        this.bodyComponent = bodyComponent;
        this.channelComponent = channelComponent;
        this.fenceVertices = fenceVertices;
        this.pivotSpokes = pivotSpokes;
    }
}
