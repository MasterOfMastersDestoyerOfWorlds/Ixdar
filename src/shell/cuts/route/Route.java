package shell.cuts.route;

import java.util.ArrayList;
import java.util.HashMap;

import shell.cuts.CutInfo;
import shell.cuts.engines.InternalPathEngine;
import shell.cuts.enums.RouteType;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.color.Color;
import shell.render.text.HyperString;

public class Route implements Comparable<Route> {
    public RouteType routeType;
    public RouteType ancestorRouteType = RouteType.None;
    public VirtualPoint neighbor;
    public double delta;
    public VirtualPoint ancestor;
    public ArrayList<Integer> ourGroup;
    public ArrayList<Integer> otherGroup;
    public ArrayList<Segment> cuts;
    public ArrayList<Segment> matches;
    public int routeId;
    public RouteInfo parent;
    public boolean needToCalculateGroups = false;
    public Segment neighborSegment;
    public Route ancestorRoute;
    public double copyTime = 0.0;
    public int greatestRotDistAncestorOtherGroup = -1;
    public int numPoints = -1;
    public GroupInfo[] groupInfo;

    public Route(RouteType routeType, double delta, VirtualPoint neighbor, int pointId, RouteInfo parent) {
        this.routeType = routeType;
        this.delta = delta;
        this.neighbor = neighbor;
        neighborSegment = parent.node.getSegment(neighbor);
        this.parent = parent;
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
        routeId = routeType.idTransform(pointId);

    }

    /**
     * Copy constructor
     * 
     * @param routeToCopy
     * @param upperCutPoint
     * @param upperKnotPoint
     * @param parent
     * 
     */
    public Route(Route routeToCopy, VirtualPoint upperCutPoint, VirtualPoint upperKnotPoint, RouteInfo parent,
            CutInfo c, ArrayList<Route> routesToCheck) {

        this.routeType = routeToCopy.routeType;
        this.ancestorRouteType = routeToCopy.ancestorRouteType;
        this.neighbor = routeToCopy.neighbor;
        this.neighborSegment = routeToCopy.neighborSegment;
        this.delta = routeToCopy.delta;
        this.ancestor = routeToCopy.ancestor;
        this.ancestorRoute = routeToCopy.ancestorRoute;
        this.cuts = new ArrayList<>(routeToCopy.cuts);
        this.matches = new ArrayList<>(routeToCopy.matches);
        this.routeId = routeToCopy.routeId;
        this.parent = parent;
        this.needToCalculateGroups = true;
        this.ourGroup = new ArrayList<>(routeToCopy.ourGroup);
        this.otherGroup = new ArrayList<>(routeToCopy.otherGroup);
        this.numPoints = c.shell.pointMap.size();
        this.groupInfo = new GroupInfo[numPoints];
        this.greatestRotDistAncestorOtherGroup = routeToCopy.greatestRotDistAncestorOtherGroup;

        RouteInfo otherParent = routeToCopy.parent;
        boolean cutContains = false;
        for (Segment cut : cuts) {
            if (cut.equals(c.upperCutSegment) || cut.equals(otherParent.c.upperCutSegment)) {
                cutContains = true;
                break;
            }
        }

        if (cutContains || this.neighborSegment.equals(otherParent.c.upperCutSegment) || delta == Double.MAX_VALUE
                || delta == 0) {
            this.reset(routesToCheck);
        }
    }

    private Route() {
    }

    public void reset(ArrayList<Route> routesToCheck) {
        routesToCheck.remove(this);
        if (ancestorRouteType != RouteType.None) {
            routesToCheck.add(ancestorRoute);
        }
        delta = Double.MAX_VALUE;
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
        ancestorRoute = null;
        ancestor = null;
        ancestorRouteType = RouteType.None;
        ourGroup = null;
        otherGroup = null;
        groupInfo = null;
        needToCalculateGroups = false;
    }

    public Route copy(RouteInfo parent) {
        Route r = new Route();
        r.routeType = routeType;
        r.ancestorRouteType = ancestorRouteType;
        r.neighbor = neighbor;
        r.delta = delta;
        r.ancestor = ancestor;
        if (ourGroup != null) {
            r.ourGroup = new ArrayList<>(ourGroup);
            r.otherGroup = new ArrayList<>(otherGroup);
            r.groupInfo = groupInfo;
        }
        if (cuts != null) {
            r.cuts = new ArrayList<>(cuts);
            r.matches = new ArrayList<>(matches);
        }
        r.routeId = routeId;
        r.parent = parent;
        r.needToCalculateGroups = needToCalculateGroups;
        r.neighborSegment = neighborSegment;
        r.ancestorRoute = ancestorRoute;
        return r;
    }

    public void calculateGroupsFromCutMatches(ArrayList<Segment> cuts, ArrayList<Segment> matches)
            throws SegmentBalanceException {
        this.needToCalculateGroups = false;
        for (int i = cuts.size() - 1; i >= 0; i--) {
            Segment cut = cuts.get(i);
            Segment match = matches.get(i);
            VirtualPoint neighbor = match.getOverlap(cut);
            VirtualPoint node = cut.getOther(neighbor);
            calculateGroups(ourGroup, otherGroup, groupInfo, node.id, neighbor.id);
        }
    }

    public void calculateGroupsFromAncestor(Route ancestorRoute) throws SegmentBalanceException {
        calculateGroups(ancestorRoute.ourGroup, ancestorRoute.otherGroup, ancestorRoute.groupInfo, parent.node.id,
                neighbor.id);
    }

    public void calculateGroups(ArrayList<Integer> ancestorOurGroup, ArrayList<Integer> ancestorOtherGroup,
            GroupInfo[] ancestorGroupInfo, Integer node, Integer neighbor)
            throws SegmentBalanceException {
        // 14%
        this.needToCalculateGroups = false;
        GroupInfo nodeGroupInfo = ancestorGroupInfo[node];
        GroupInfo neighborGroupInfo = ancestorGroupInfo[neighbor];
        if (nodeGroupInfo.isOurGroup) {
            ArrayList<Integer> grp = ancestorOurGroup;
            int idxNeighbor = neighborGroupInfo.isOurGroup ? neighborGroupInfo.index : -1;
            int rotateIdx = nodeGroupInfo.index;
            this.otherGroup = ancestorOtherGroup;

            if (rotateIdx >= grp.size()) {
                float z = 0;
            }

            ArrayList<Integer> reverseList = new ArrayList<Integer>(grp.size());
            if (idxNeighbor > rotateIdx || idxNeighbor == -1) {
                for (int i = 0; i < rotateIdx + 1; i++) {
                    reverseList.add(0, grp.get(i));
                }
                for (int i = rotateIdx + 1; i < grp.size(); i++) {
                    reverseList.add(grp.get(i));
                }
                this.ourGroup = reverseList;
            } else {
                for (int i = rotateIdx; i < grp.size(); i++) {
                    reverseList.add(0, grp.get(i));
                }
                for (int i = 0; i < rotateIdx; i++) {
                    reverseList.add(grp.get(i));
                }
                this.ourGroup = reverseList;

            }

        } else {

            ArrayList<Integer> grp = ancestorOtherGroup;
            ArrayList<Integer> otherGrp = ancestorOurGroup;
            int idxNeighbor = neighborGroupInfo.isOurGroup ? -1 : neighborGroupInfo.index;
            int rotateIdx = nodeGroupInfo.index;
            this.otherGroup = ancestorOtherGroup;
            ArrayList<Integer> remainList = new ArrayList<Integer>();
            ArrayList<Integer> reverseList = new ArrayList<Integer>(otherGrp);
            if (idxNeighbor > rotateIdx || idxNeighbor == -1) {
                for (int i = 0; i < rotateIdx + 1; i++) {
                    remainList.add(0, grp.get(i));
                }
                for (int i = rotateIdx + 1; i < grp.size(); i++) {
                    reverseList.add(0, grp.get(i));
                }
            } else {
                for (int i = rotateIdx; i < grp.size(); i++) {
                    remainList.add(grp.get(i));
                }
                for (int i = rotateIdx - 1; i >= 0; i--) {
                    reverseList.add(0, grp.get(i));
                }
            }
            this.ourGroup = remainList;
            this.otherGroup = reverseList;
        }
        //2%
        for (int i = 0; i < ourGroup.size(); i++) {
            int vp = ourGroup.get(i);
            GroupInfo g = groupInfo[vp];
            g.index = i;
            g.isOurGroup = true;
        }
        for (int i = 0; i < otherGroup.size(); i++) {
            int vp = otherGroup.get(i);
            GroupInfo g = groupInfo[vp];
            g.index = i;
            g.isOurGroup = false;
        }
        if (this.cuts.size() != this.matches.size()) {
            throw new SegmentBalanceException();
        }
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == null) {
            return false;
        }
        if (obj.getClass() != Route.class) {
            return false;
        } else {
            Route r2 = (Route) obj;

            return (this.routeId == r2.routeId);
        }
    }

    public boolean sameRoute(Route rt) {

        if (rt == null) {
            return false;
        }
        if (this.delta != rt.delta) {
            return false;
        }
        if (this.cuts.size() != rt.cuts.size()) {
            return false;
        }
        if (this.matches.size() != rt.matches.size()) {
            return false;
        }
        for (int i = 0; i < this.cuts.size(); i++) {
            if (!this.cuts.get(i).equals(rt.cuts.get(i))) {
                return false;
            }
        }
        for (int i = 0; i < this.matches.size(); i++) {
            if (!this.matches.get(i).equals(rt.matches.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(Route o) {
        return Double.compare(delta, o.delta);
    }

    @Override
    public String toString() {
        return routeType.name() + ", " + (ancestor == null ? "NULL"
                : ancestor.id) + ", " + (delta == Double.MAX_VALUE ? "INF" : String.format("%.2f", delta));
    }

    public HyperString compareHyperString(Route otherRoute, Color matchColor, Color cutColor) {
        HyperString h = new HyperString();
        int maxSize = Math.max(this.matches.size(), this.cuts.size());
        for (int i = 0; i < maxSize; i++) {
            Segment match = i < this.matches.size() ? this.matches.get(i) : null;
            Segment cut = i < this.cuts.size() ? this.cuts.get(i) : null;
            if (match != null) {
                if (otherRoute.matches.contains(match)) {
                    h.addHyperString(match.toHyperString(Color.CYAN, false));
                } else {
                    h.addHyperString(match.toHyperString(matchColor, false));
                }
            }
            if (cut != null) {
                if (otherRoute.cuts.contains(cut)) {
                    h.addHyperString(cut.toHyperString(Color.ORANGE, false));
                } else {
                    h.addHyperString(cut.toHyperString(cutColor, false));
                }
                h.newLine();
            }
        }
        return h;
    }

}