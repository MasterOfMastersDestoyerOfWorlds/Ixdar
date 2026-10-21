package ixdar.tools.trade;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.tsp.knot.GrowRecord;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.OperationRecord;
import ixdar.geometry.tsp.knot.PipeRecord;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.EditHistory;
import ixdar.tools.Tool;

/**
 * Tool for planning and building trade routes between cities. Supports Pipe,
 * Grow, and Collapse operations with undo/redo. Activated after the player
 * selects their headquarters.
 */
@ToolAnnotation(id = RoutePlanningTool.TOOL_NAME)
public class RoutePlanningTool implements Tool {
    public static final String TOOL_NAME = "routeplanning";
    public static final String P = "P";
    public static final String G = "G";
    public static final String C = "C";
    public static final String Z = "Z";
    public static final String STATE = " state=";
    public static final String MODE = " mode=";
    public static final String SELECTED_ROUTE_CITY = "Selected route city: ";
    public static final String SELECTED = "Selected: ";
    public static final String TO = " to ";
    public static final String SEGMENTS = " segments";
    public static final float ROUTE_LINE_WIDTH = 3f;
    public static final float CITY_HIGHLIGHT_RADIUS = 25f;
    public static final float PREVIEW_LINE_WIDTH = 2f;
    public static final float EDGE_HIGHLIGHT_LINE_WIDTH = 4f;
    public static final int UNDO_BUTTON_INDEX = 3;
    public static final int CONFIRM_BUTTON_INDEX = 4;

    
    private static final Color ROUTE_COLOR = Color.PURPLE_PINK;
    private static final Color ROUTE_PREVIEW_COLOR = Color.PURPLE_PINK_50;
    private static final Color EDGE_REMOVE_COLOR = Color.RED_LIGHT_80;
    private static final Color EDGE_ADD_COLOR = Color.GREEN_80;
    private static final Color CROSSING_COLOR = Color.BLUE_TEAL_LIGHT_80;
    private static final Color BUTTON_COLOR = Color.GREY_DARK_90;
    private static final Color BUTTON_SELECTED_COLOR = Color.BLUE_GREY_LIGHT_90;
    private static final Color BUTTON_HOVER_COLOR = Color.BLUE_GREY_DARK_90;
    private static final Color SELECTED_KNOT_COLOR = Color.YELLOW_50;

    
    private static final float TOOLBAR_HEIGHT = 50f;
    private static final float BUTTON_SIZE = 40f;
    private static final float BUTTON_PADDING = 10f;

    /** The route operations, each record undoing and redoing itself; kept without limit. */
    public final EditHistory<OperationRecord> history = new EditHistory<>();

    private TradeScene tradeScene;
    private CityNetwork network;

    
    private Mode currentMode = Mode.PIPE;
    private OperationState state = OperationState.IDLE;

    
    private Knot selectedKnotA;
    private Knot selectedKnotB;
    private Segment selectedEdgeA;
    private Segment selectedEdgeB;
    private City selectedCityA;
    private City selectedCityB;


    private Knot currentViewKnot;
    private ArrayList<Knot> hierarchyPath = new ArrayList<>(); 


    private Segment hoveredEdge;

    
    private SDFLine routeLine = new SDFLine();
    private SDFCircle buttonCircle = new SDFCircle();
    private SDFCircle highlightCircle = new SDFCircle();

    
    private int hoveredButton = -1; 

    

    /**
     * Build the route-planning tool over the owning trade scene, editing the cities and knot
     * mappings of the scene's city network.
     *
     * @param tradeScene the trade scene that owns this tool
     */
    public RoutePlanningTool(TradeScene tradeScene) {
        this.tradeScene = tradeScene;
        this.network = tradeScene.network;
        history.capacity = Integer.MAX_VALUE;
    }



    /**
     * Render the planning overlay: the current route knot, selection
     * highlights, the operation preview (or a hover-to-target preview line
     * during city selection), and the bottom toolbar.
     *
     * @param camera        the scene camera
     * @param lineThickness base line thickness in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float lineThickness) {
        City hoveredCity = tradeScene.hoveredCity;
        Knot currentRoute = getCurrentRoute();
        if (currentRoute != null) {
            drawKnot(camera, currentRoute, ROUTE_COLOR);
        }

        
        if (selectedCityA != null) {
            drawCityHighlight(camera, selectedCityA, SELECTED_KNOT_COLOR);
        }
        if (selectedCityB != null) {
            drawCityHighlight(camera, selectedCityB, SELECTED_KNOT_COLOR);
        }

        
        if (state == OperationState.PREVIEW) {
            drawOperationPreview(camera);
        } else if (state == OperationState.KNOT_A_SELECTED && hoveredCity != null) {
            
            if (selectedCityA != null && hoveredCity != selectedCityA) {
                drawPreviewLine(camera, selectedCityA, hoveredCity);
            }
        }

        
        drawToolbar(camera);
    }

    private void drawKnot(Camera2D camera, Knot knot, Color color) {
        if (knot == null || knot.manifoldSegments == null) {
            return;
        }

        routeLine.setStroke(ROUTE_LINE_WIDTH, false);

        for (Segment seg : knot.manifoldSegments) {
            if (seg.first.p != null && seg.last.p != null) {
                float x1 = camera.pointTransformX((float) seg.first.p.getScreenX());
                float y1 = camera.pointTransformY((float) seg.first.p.getScreenY());
                float x2 = camera.pointTransformX((float) seg.last.p.getScreenX());
                float y2 = camera.pointTransformY((float) seg.last.p.getScreenY());
                routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), color, camera);
            }
        }
    }

    private void drawCityHighlight(Camera2D camera, City city, Color color) {
        if (city == null) {
            return;
        }
        float x = camera.pointTransformX(city.getX());
        float y = camera.pointTransformY(city.getY());
        highlightCircle.draw(new Vector2f(x, y), CITY_HIGHLIGHT_RADIUS, color, camera);
    }

    private void drawPreviewLine(Camera2D camera, City from, City to) {
        if (from == null || to == null) {
            return;
        }
        routeLine.setStroke(PREVIEW_LINE_WIDTH, true);
        float x1 = camera.pointTransformX(from.getX());
        float y1 = camera.pointTransformY(from.getY());
        float x2 = camera.pointTransformX(to.getX());
        float y2 = camera.pointTransformY(to.getY());

        
        routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), ROUTE_PREVIEW_COLOR, camera);
        routeLine.draw(new Vector2f(x2, y2), new Vector2f(x1, y1), ROUTE_PREVIEW_COLOR, camera);
    }

    private void drawOperationPreview(Camera2D camera) {
        switch (currentMode) {
        case PIPE:
            drawPipePreview(camera);
            break;
        case GROW:
            drawGrowPreview(camera);
            break;
        case COLLAPSE:
            drawCollapsePreview(camera);
            break;
        }
    }

    private void drawPipePreview(Camera2D camera) {
        if (selectedCityA == null || selectedCityB == null) {
            return;
        }

        routeLine.setStroke(ROUTE_LINE_WIDTH, false);


        float x1 = camera.pointTransformX(selectedCityA.getX());
        float y1 = camera.pointTransformY(selectedCityA.getY());
        float x2 = camera.pointTransformX(selectedCityB.getX());
        float y2 = camera.pointTransformY(selectedCityB.getY());
        routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), EDGE_ADD_COLOR, camera);
        routeLine.draw(new Vector2f(x2, y2), new Vector2f(x1, y1), EDGE_ADD_COLOR, camera);

        
        if (selectedEdgeA != null) {
            drawEdgeHighlight(camera, selectedEdgeA, EDGE_REMOVE_COLOR, true);
        }
        if (selectedEdgeB != null) {
            drawEdgeHighlight(camera, selectedEdgeB, EDGE_REMOVE_COLOR, true);
        }
    }

    private void drawGrowPreview(Camera2D camera) {
        if (selectedCityB == null || selectedEdgeA == null) {
            return;
        }

        routeLine.setStroke(ROUTE_LINE_WIDTH, false);


        drawEdgeHighlight(camera, selectedEdgeA, EDGE_REMOVE_COLOR, true);

        
        if (selectedEdgeA.first.p != null && selectedCityB != null) {
            float x1 = camera.pointTransformX((float) selectedEdgeA.first.p.getScreenX());
            float y1 = camera.pointTransformY((float) selectedEdgeA.first.p.getScreenY());
            float x2 = camera.pointTransformX(selectedCityB.getX());
            float y2 = camera.pointTransformY(selectedCityB.getY());
            routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), EDGE_ADD_COLOR, camera);
        }
        if (selectedEdgeA.last.p != null && selectedCityB != null) {
            float x1 = camera.pointTransformX(selectedCityB.getX());
            float y1 = camera.pointTransformY(selectedCityB.getY());
            float x2 = camera.pointTransformX((float) selectedEdgeA.last.p.getScreenX());
            float y2 = camera.pointTransformY((float) selectedEdgeA.last.p.getScreenY());
            routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), EDGE_ADD_COLOR, camera);
        }
    }

    private void drawCollapsePreview(Camera2D camera) {
        
    }

    private void drawEdgeHighlight(Camera2D camera, Segment edge, Color color, boolean dashed) {
        if (edge == null || edge.first.p == null || edge.last.p == null) {
            return;
        }

        routeLine.setStroke(EDGE_HIGHLIGHT_LINE_WIDTH, dashed);
        float x1 = camera.pointTransformX((float) edge.first.p.getScreenX());
        float y1 = camera.pointTransformY((float) edge.first.p.getScreenY());
        float x2 = camera.pointTransformX((float) edge.last.p.getScreenX());
        float y2 = camera.pointTransformY((float) edge.last.p.getScreenY());
        routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), color, camera);
    }

    private void drawToolbar(Camera2D camera) {
        float wWidth = Platforms.get().getFrameBufferWidth();

        
        float toolbarY = TOOLBAR_HEIGHT / 2;
        float startX = wWidth / 2 - (UNDO_BUTTON_INDEX * BUTTON_SIZE + 2 * BUTTON_PADDING) / 2;


        drawToolbarButton(camera, startX, toolbarY, 0, P, currentMode == Mode.PIPE);

        
        drawToolbarButton(camera, startX + BUTTON_SIZE + BUTTON_PADDING, toolbarY, 1, G, currentMode == Mode.GROW);

        
        drawToolbarButton(camera, startX + 2 * (BUTTON_SIZE + BUTTON_PADDING), toolbarY, 2, C,
                currentMode == Mode.COLLAPSE);


        if (history.undoDepth > 0) {
            drawToolbarButton(camera, BUTTON_PADDING + BUTTON_SIZE / 2, toolbarY, UNDO_BUTTON_INDEX, Z, false);
        }


        if (state == OperationState.PREVIEW) {
            drawToolbarButton(camera, wWidth - BUTTON_PADDING - BUTTON_SIZE / 2, toolbarY, CONFIRM_BUTTON_INDEX, "OK", false);
        }
    }

    private void drawToolbarButton(Camera2D camera, float screenX, float screenY, int buttonIndex, String label,
            boolean selected) {
        Color bgColor = BUTTON_COLOR;
        if (selected) {
            bgColor = BUTTON_SELECTED_COLOR;
        } else if (hoveredButton == buttonIndex) {
            bgColor = BUTTON_HOVER_COLOR;
        }

        
        
        buttonCircle.draw(new Vector2f(screenX, screenY), BUTTON_SIZE / 2, bgColor, camera);

        
        
    }

    

    /**
     * Handle a city click during route planning.
     *
     * @param city the city that was clicked
     */
    public void onCityClick(City city) {
        System.out.println("[RoutePlanningTool] onCityClick: " + (city != null ? city.name : "null") +
                STATE + state + MODE + currentMode);

        if (city == null) {
            return;
        }

        switch (state) {
        case IDLE:
            System.out.println("[RoutePlanningTool] Handling IDLE click");
            handleIdleClick(city);
            break;
        case KNOT_A_SELECTED:
            System.out.println("[RoutePlanningTool] Handling KNOT_A_SELECTED click");
            handleSecondClick(city);
            break;
        case PREVIEW:
            System.out.println("[RoutePlanningTool] Handling PREVIEW click");
            handlePreviewClick(city);
            break;
        case CONFIRMED:
            System.out.println("[RoutePlanningTool] Handling CONFIRMED click - resetting");
            
            resetOperation();
            handleIdleClick(city);
            break;
        }
        System.out.println("[RoutePlanningTool] After click: state=" + state);
    }

    private void handleIdleClick(City city) {
        Knot currentRoute = getCurrentRoute();
        switch (currentMode) {
        case PIPE:
            
            if (currentRoute == null) {
                if (city == network.headquartersCity) {
                    selectedCityA = city;
                    state = OperationState.KNOT_A_SELECTED;
                    System.out.println("Selected HQ: " + city.name);
                } else {
                    System.out.println("First route must start from HQ");
                }
            } else {
                
                Knot cityKnot = network.getKnotForCity(city);
                if (cityKnot != null && currentRoute.contains(cityKnot)) {
                    
                    selectedCityA = city;
                    state = OperationState.KNOT_A_SELECTED;
                    System.out.println(SELECTED_ROUTE_CITY + city.name + " (in route)");
                } else if (cityKnot != null && cityKnot.isSingleton()) {
                    
                    selectedCityB = city;
                    computeDefaultGrowEdge();
                    state = OperationState.PREVIEW;
                    System.out.println("Preview: Add " + city.name + " to route (Enter to confirm)");
                } else {
                    
                    selectedCityA = city;
                    state = OperationState.KNOT_A_SELECTED;
                    System.out.println(SELECTED + city.name);
                }
            }
            break;

        case GROW:
            
            if (currentRoute != null) {
                selectedCityA = city;
                state = OperationState.KNOT_A_SELECTED;
                System.out.println(SELECTED_ROUTE_CITY + city.name);
            } else {
                System.out.println("No route to grow - create one first with Pipe");
            }
            break;

        case COLLAPSE:
            
            if (currentRoute != null && !currentRoute.isSingleton()) {
                selectedCityA = city;
                state = OperationState.KNOT_A_SELECTED;
                System.out.println("Collapse start: " + city.name);
            } else {
                System.out.println("Need a complex route for collapse");
            }
            break;
        }
    }

    private void handleSecondClick(City city) {
        if (city == selectedCityA) {
            return; 
        }

        switch (currentMode) {
        case PIPE:
            selectedCityB = city;
            
            computeDefaultPipeEdges();
            state = OperationState.PREVIEW;
            System.out.println("Pipe preview: " + selectedCityA.name + " <-> " + city.name);
            break;

        case GROW:
            selectedCityB = city;
            
            computeDefaultGrowEdge();
            state = OperationState.PREVIEW;
            System.out.println("Grow preview: insert " + city.name);
            break;

        case COLLAPSE:
            selectedCityB = city;
            state = OperationState.PREVIEW;
            System.out.println("Collapse preview: " + selectedCityA.name + TO + city.name);
            break;
        }
    }

    private void handlePreviewClick(City city) {
        
        
        
    }

    private void computeDefaultPipeEdges() {

        if (getCurrentRoute() == null) {
            selectedEdgeA = null;
            selectedEdgeB = null;
            return;
        }

        
        selectedEdgeA = null;
        selectedEdgeB = null;
    }

    private void computeDefaultGrowEdge() {
        Knot currentRoute = getCurrentRoute();
        if (currentRoute == null || selectedCityB == null) {
            selectedEdgeA = null;
            return;
        }

        
        
        if (!currentRoute.manifoldSegments.isEmpty()) {
            selectedEdgeA = currentRoute.manifoldSegments.get(0);
        }
    }

    /**
     * The route keys: P, G and C pick the operation, Enter applies the preview, Z and Y undo and
     * redo and keep stepping while held, Backspace climbs the route hierarchy.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint(Keys.P, P, "pipe: join two cities or knots into one loop",
                () -> setMode(Mode.PIPE)));
        controls.add(new ControlHint(Keys.G, G, "grow: insert a city into a route edge",
                () -> setMode(Mode.GROW)));
        controls.add(new ControlHint(Keys.C, C, "collapse: compress a chain, keeping one loop",
                () -> setMode(Mode.COLLAPSE)));
        controls.add(new ControlHint(Keys.ENTER, "enter", "apply the preview", this::confirm));
        controls.add(ControlHint.repeating(Keys.Z, Z, "undo the last route operation", this::undo));
        controls.add(ControlHint.repeating(Keys.Y, "Y", "redo", this::redo));
        controls.add(new ControlHint(Keys.BACKSPACE, "backspace", "up the route hierarchy", this::navigateUp));
    }

    /**
     * Esc cancels the operation in progress, so it never reaches the scene's return to the menu.
     *
     * @return true
     */
    @Override
    public boolean escapePressed() {
        resetOperation();
        return true;
    }

    /**
     * What Esc does under this tool.
     *
     * @return the phrase
     */
    @Override
    public String escapeDescription() {
        return "cancel the operation";
    }

    private void setMode(Mode mode) {
        if (currentMode != mode) {
            currentMode = mode;
            resetOperation();
            System.out.println("Mode: " + mode.name());
        }
    }

    

    private void executeOperation() {
        switch (currentMode) {
        case PIPE:
            executePipe();
            break;
        case GROW:
            executeGrow();
            break;
        case COLLAPSE:
            executeCollapse();
            break;
        }

        state = OperationState.CONFIRMED;
        resetOperation();
    }

    private void executePipe() {
        
        if (!network.isTradeInitialized()) {
            network.initTradeRoutes();
        }

        Knot currentRoute = getCurrentRoute();
        if (currentRoute != null && selectedCityA == null && selectedCityB != null) {
            Knot knotB = network.getKnotForCity(selectedCityB);
            if (knotB != null && knotB.isSingleton()) {
                Segment lowestCostEdge = selectedEdgeA != null ? selectedEdgeA
                        : currentRoute.getLowestCostGrowEdge(knotB);
                if (lowestCostEdge != null) {
                    GrowRecord record = currentRoute.grow(knotB, lowestCostEdge);
                    history.push(record.getDescription(), record, record);
                    System.out.println("Added " + selectedCityB.name + " to route. Route now has " +
                            currentRoute.knotPoints.size() + " cities.");
                }
            }
            return;
        }

        if (selectedCityA == null || selectedCityB == null) {
            return;
        }

        Knot knotA = network.getKnotForCity(selectedCityA);
        Knot knotB = network.getKnotForCity(selectedCityB);

        if (knotA == null || knotB == null) {
            System.out.println("Error: Could not get knots for cities");
            return;
        }

        if (currentRoute == null) {
            
            System.out.println("Creating initial route: " + selectedCityA.name + " <-> " + selectedCityB.name);

            PipeRecord record = Knot.pipeSimple(knotA, knotB);
            history.push(record.getDescription(), record, record);
            viewRoute();
        } else {
            
            System.out.println("Piping " + selectedCityB.name + " to route");

            if (knotB.isSingleton()) {
                
                Segment lowestCostEdge = currentRoute.getLowestCostGrowEdge(knotB);
                if (lowestCostEdge != null) {
                    GrowRecord record = currentRoute.grow(knotB, lowestCostEdge);
                    history.push(record.getDescription(), record, record);
                    System.out.println("Grew route to include " + selectedCityB.name);
                }
            } else {
                
                PipeRecord record = Knot.pipe(currentRoute, knotB, selectedEdgeA, selectedEdgeB);
                if (record != null) {
                    history.push(record.getDescription(), record, record);
                    viewRoute();
                }
            }
        }
    }

    private void executeGrow() {
        Knot currentRoute = getCurrentRoute();
        if (currentRoute == null || selectedCityB == null) {
            return;
        }

        
        if (!network.isTradeInitialized()) {
            network.initTradeRoutes();
        }

        Knot knotB = network.getKnotForCity(selectedCityB);
        if (knotB == null || !knotB.isSingleton()) {
            System.out.println("Grow requires a singleton city not already in route");
            return;
        }

        
        Segment edgeToSplit = selectedEdgeA;
        if (edgeToSplit == null) {
            edgeToSplit = currentRoute.getLowestCostGrowEdge(knotB);
        }

        if (edgeToSplit == null) {
            System.out.println("No valid edge to grow into");
            return;
        }

        System.out.println("Growing route with: " + selectedCityB.name);
        GrowRecord record = currentRoute.grow(knotB, edgeToSplit);
        history.push(record.getDescription(), record, record);
        System.out.println("Route now has " + currentRoute.manifoldSegments.size() + SEGMENTS);
    }

    private void executeCollapse() {
        if (getCurrentRoute() == null || selectedCityA == null || selectedCityB == null) {
            return;
        }

        System.out.println("Collapsing from " + selectedCityA.name + TO + selectedCityB.name);

        
        
        
    }

    /**
     * Undo the most recent route operation if the operation stack permits, then view the route
     * the operation before it left.
     */
    public void undo() {
        OperationRecord record = history.undo();
        if (record != null) {
            record.undo();
            viewRoute();
            System.out.println("Undo: " + record.getDescription());
        }
    }

    /**
     * Redo the most recently undone route operation if the operation stack permits, then view the
     * route it leaves.
     */
    public void redo() {
        OperationRecord record = history.redo();
        if (record != null) {
            record.redo();
            viewRoute();
            System.out.println("Redo: " + record.getDescription());
        }
    }

    

    /**
     * Navigate up one level in the knot hierarchy.
     *
     * @return true if navigation occurred
     */
    public boolean navigateUp() {
        if (currentViewKnot == null || currentViewKnot.topGroupKnot == null) {
            return false;
        }

        currentViewKnot = currentViewKnot.topGroupKnot;
        rebuildHierarchyPath();
        System.out.println("Navigated up to: " + getHierarchyPathString());
        return true;
    }

    /**
     * Navigate down into a child knot.
     *
     * @param child the child knot to navigate into
     * @return true if navigation occurred
     */
    public boolean navigateDown(Knot child) {
        if (currentViewKnot == null) {
            return false;
        }

        
        if (!currentViewKnot.knotPoints.contains(child)) {
            return false;
        }

        
        if (child.isSingleton()) {
            return false;
        }

        currentViewKnot = child;
        rebuildHierarchyPath();
        System.out.println("Navigated down to: " + getHierarchyPathString());
        return true;
    }

    /**
     * Navigate to a specific level in the hierarchy path.
     *
     * @param index the index in the hierarchy path (0 = root)
     * @return true if navigation occurred
     */
    public boolean navigateToLevel(int index) {
        if (index < 0 || index >= hierarchyPath.size()) {
            return false;
        }

        currentViewKnot = hierarchyPath.get(index);
        
        while (hierarchyPath.size() > index + 1) {
            hierarchyPath.remove(hierarchyPath.size() - 1);
        }
        System.out.println("Navigated to level " + index + ": " + getHierarchyPathString());
        return true;
    }

    /**
     * View the route the history leaves at its top level; with no route left, drop the
     * operation in progress, whose selection named its cities.
     */
    private void viewRoute() {
        currentViewKnot = getCurrentRoute();
        rebuildHierarchyPath();
        if (currentViewKnot == null) {
            resetOperation();
        }
    }

    /**
     * Get the currently viewed knot (current hierarchy level).
     *
     * @return the current view knot
     */
    public Knot getCurrentViewKnot() {
        return currentViewKnot;
    }

    /**
     * Get the hierarchy path from root to current view.
     *
     * @return list of knots from root to current
     */
    public ArrayList<Knot> getHierarchyPath() {
        return hierarchyPath;
    }

    /**
     * Rebuild the hierarchy path from root to current view.
     */
    private void rebuildHierarchyPath() {
        hierarchyPath.clear();

        if (currentViewKnot == null) {
            return;
        }

        
        ArrayList<Knot> reversePath = new ArrayList<>();
        Knot node = currentViewKnot;
        while (node != null) {
            reversePath.add(node);
            node = node.topGroupKnot;
        }

        
        for (int i = reversePath.size() - 1; i >= 0; i--) {
            hierarchyPath.add(reversePath.get(i));
        }
    }

    /**
     * Get a string representation of the current hierarchy path.
     *
     * @return breadcrumb string like "Route > SubRouteA > Circle1"
     */
    public String getHierarchyPathString() {
        if (hierarchyPath.isEmpty()) {
            return "No route";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hierarchyPath.size(); i++) {
            Knot k = hierarchyPath.get(i);
            if (k.isSingleton()) {
                sb.append(P).append(k.id);
            } else {
                sb.append("K").append(k.id);
            }
            if (i < hierarchyPath.size() - 1) {
                sb.append(" > ");
            }
        }
        return sb.toString();
    }

    /**
     * Get the depth of the current view in the hierarchy.
     *
     * @return depth (0 = root level)
     */
    public int getHierarchyDepth() {
        return hierarchyPath.size() - 1;
    }

    /**
     * Check if we can navigate up from current level.
     *
     * @return true if there's a parent to navigate to
     */
    public boolean canNavigateUp() {
        return currentViewKnot != null && currentViewKnot.topGroupKnot != null;
    }

    /**
     * Check if we can navigate down into any child.
     *
     * @return true if current view has non-singleton children
     */
    public boolean canNavigateDown() {
        if (currentViewKnot == null || currentViewKnot.knotPoints == null) {
            return false;
        }

        for (Knot child : currentViewKnot.knotPoints) {
            if (!child.isSingleton()) {
                return true;
            }
        }
        return false;
    }

    private void resetOperation() {
        selectedKnotA = null;
        selectedKnotB = null;
        selectedEdgeA = null;
        selectedEdgeB = null;
        selectedCityA = null;
        selectedCityB = null;
        state = OperationState.IDLE;
    }

    

    /**
     * Hover the toolbar button under the cursor and show its tooltip, else leave the city's or
     * none.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onHover(float x, float y) {
        updateHoveredButton(x, y);
        HyperString toolbarTip = buildHoveredToolbarTooltip();
        if (toolbarTip != null) {
            tradeScene.setTooltipText(toolbarTip);
        } else if (tradeScene.hoveredCity == null) {
            tradeScene.clearTooltipText();
        }
    }

    /**
     * A click on a toolbar button, else on the city under the cursor, which steps the operation.
     *
     * @param x framebuffer x of the click
     * @param y framebuffer y of the click, y up
     * @return true when a button or a city took the click
     */
    @Override
    public boolean onClick(float x, float y) {
        if (onToolbarClick(x, y)) {
            return true;
        }
        City city = tradeScene.getCityAt(tradeScene.mapCamera.screenTransformX(x),
                tradeScene.mapCamera.screenTransformY(y));
        if (city == null) {
            return false;
        }
        onCityClick(city);
        return true;
    }

    /**
     * Update hovered button based on mouse position.
     *
     * @param mouseX framebuffer x of the cursor
     * @param mouseYOpenGL framebuffer y of the cursor, y up
     */
    public void updateHoveredButton(float mouseX, float mouseYOpenGL) {
        float wWidth = Platforms.get().getFrameBufferWidth();

        float toolbarY = TOOLBAR_HEIGHT / 2;
        float startX = wWidth / 2 - (UNDO_BUTTON_INDEX * BUTTON_SIZE + 2 * BUTTON_PADDING) / 2;

        hoveredButton = -1;

        
        if (mouseYOpenGL < TOOLBAR_HEIGHT) {
            
            if (isInButton(mouseX, mouseYOpenGL, startX, toolbarY)) {
                hoveredButton = 0; 
            } else if (isInButton(mouseX, mouseYOpenGL, startX + BUTTON_SIZE + BUTTON_PADDING, toolbarY)) {
                hoveredButton = 1; 
            } else if (isInButton(mouseX, mouseYOpenGL, startX + 2 * (BUTTON_SIZE + BUTTON_PADDING), toolbarY)) {
                hoveredButton = 2; 
            } else if (isInButton(mouseX, mouseYOpenGL, BUTTON_PADDING + BUTTON_SIZE / 2, toolbarY)
                    && history.undoDepth > 0) {
                hoveredButton = UNDO_BUTTON_INDEX;
            } else if (isInButton(mouseX, mouseYOpenGL, wWidth - BUTTON_PADDING - BUTTON_SIZE / 2, toolbarY)
                    && state == OperationState.PREVIEW) {
                hoveredButton = CONFIRM_BUTTON_INDEX;
            }
        }
    }

    /**
     * Build tooltip text for the currently hovered toolbar button.
     *
     * @return tooltip text, or null when no toolbar button is hovered
     */
    public HyperString buildHoveredToolbarTooltip() {
        if (hoveredButton < 0) {
            return null;
        }

        HyperString h = new HyperString();
        switch (hoveredButton) {
        case 0:
            h.addWord("Pipe (P)", Color.WHITE);
            h.wrap();
            h.addWord("Connect two cities/knots into one loop", Color.GREY_DARK);
            break;
        case 1:
            h.addWord("Grow (G)", Color.WHITE);
            h.wrap();
            h.addWord("Insert a city into an existing route edge", Color.GREY_DARK);
            break;
        case 2:
            h.addWord("Collapse (C)", Color.WHITE);
            h.wrap();
            h.addWord("Compress a linked chain while preserving one loop", Color.GREY_DARK);
            break;
        case UNDO_BUTTON_INDEX:
            h.addWord("Undo (Ctrl+Z)", Color.WHITE);
            h.wrap();
            h.addWord("Revert the last route operation", Color.GREY_DARK);
            break;
        case CONFIRM_BUTTON_INDEX:
            h.addWord("Confirm (Enter)", Color.WHITE);
            h.wrap();
            h.addWord("Apply the current preview operation", Color.GREY_DARK);
            break;
        default:
            return null;
        }
        return h;
    }

    private boolean isInButton(float mouseX, float mouseY, float buttonX, float buttonY) {
        return Math.abs(mouseX - buttonX) < BUTTON_SIZE / 2 && Math.abs(mouseY - buttonY) < BUTTON_SIZE / 2;
    }

    /**
     * Handle toolbar button click.
     *
     * @param mouseX framebuffer x of the click
     * @param mouseY framebuffer y of the click, y up
     * @return true if a button was clicked
     */
    public boolean onToolbarClick(float mouseX, float mouseY) {
        updateHoveredButton(mouseX, mouseY);

        switch (hoveredButton) {
        case 0:
            setMode(Mode.PIPE);
            return true;
        case 1:
            setMode(Mode.GROW);
            return true;
        case 2:
            setMode(Mode.COLLAPSE);
            return true;
        case UNDO_BUTTON_INDEX:
            undo();
            return true;
        case CONFIRM_BUTTON_INDEX:
            executeOperation();
            return true;
        default:
            return false;
        }
    }

    

    /**
     * Start with no operation pending and no toolbar button hovered.
     */
    @Override
    public void activate() {
        resetOperation();
        hoveredButton = -1;
    }

    /** Hand over; the route and its history stay for the next activation. */
    @Override
    public void deactivate() {
    }

    /**
     * If a preview is currently shown, execute the previewed operation
     * (Pipe / Grow / Collapse). No-op otherwise.
     */
    public void confirm() {
        if (state == OperationState.PREVIEW) {
            executeOperation();
        }
    }

    /**
     * Cancel the current operation.
     */
    public void cancel() {
        resetOperation();
        System.out.println("Operation cancelled");
    }

    /**
     * Get the current operation state.
     *
     * @return the current state
     */
    public OperationState getOperationState() {
        return state;
    }

    /**
     * Get the current mode.
     *
     * @return the current mode
     */
    public Mode getCurrentMode() {
        return currentMode;
    }

    /**
     * The route the last applied operation left, read off the history so undo and redo cannot
     * leave it stale.
     *
     * @return the current route knot, or null when no operation is applied
     */
    public Knot getCurrentRoute() {
        return history.undoDepth == 0 ? null : history.statesAfter.get(history.undoDepth - 1).route();
    }

    /**
     * Display name of the first selected city.
     *
     * @return the name of the first selected city, or an empty string if none
     */
    public String getSelectedCityAName() {
        return selectedCityA == null ? "" : selectedCityA.name;
    }

    /**
     * Display name of the second selected city.
     *
     * @return the name of the second selected city, or an empty string if none
     */
    public String getSelectedCityBName() {
        return selectedCityB == null ? "" : selectedCityB.name;
    }

    /**
     * Whether the operation stack has an undoable operation.
     *
     * @return true if the operation stack has an undoable operation
     */
    public boolean canUndoOperation() {
        return history.undoDepth > 0;
    }

    /**
     * Whether the operation stack has a redoable operation.
     *
     * @return true if the operation stack has a redoable operation
     */
    public boolean canRedoOperation() {
        return history.redoDepth() > 0;
    }

    /**
     * Build the side-panel text: hierarchy breadcrumb (if a route exists),
     * the active mode, a state-specific instruction, and a keybind cheat
     * sheet at the bottom.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString info() {
        HyperString h = new HyperString();

        
        if (getCurrentRoute() != null && !hierarchyPath.isEmpty()) {
            h.addWord("Route: ", Color.GREY_DARK);
            h.addWord(getHierarchyPathString(), Color.YELLOW);
            if (canNavigateUp()) {
                h.addWord(" [Backspace=Up]", Color.GREY_DARK);
            }
            if (canNavigateDown()) {
                h.addWord(" [Click child=Down]", Color.GREY_DARK);
            }
            h.wrap();
        }

        
        h.addWord("[" + currentMode.name() + "] ", Color.TEAL);

        switch (state) {
        case IDLE:
            switch (currentMode) {
            case PIPE:
                if (getCurrentRoute() == null) {
                    h.addWord("Click HQ to start a route", Color.WHITE);
                } else {
                    h.addWord("Click a city to pipe", Color.WHITE);
                }
                break;
            case GROW:
                h.addWord("Click route, then a city to insert", Color.WHITE);
                break;
            case COLLAPSE:
                h.addWord("Click start and end of collapse", Color.WHITE);
                break;
            }
            break;

        case KNOT_A_SELECTED:
            h.addWord(SELECTED + (selectedCityA != null ? selectedCityA.name : "?"), Color.YELLOW);
            h.addWord(" - Click second city", Color.WHITE);
            break;

        case PREVIEW:
            h.addWord("Preview - ", Color.GREEN);
            h.addWord("Enter to confirm, Esc to cancel", Color.WHITE);
            break;

        case CONFIRMED:
            h.addWord("Operation complete", Color.GREEN);
            break;
        }

        
        h.wrap();
        h.addWord("P=Pipe G=Grow C=Collapse | Ctrl+Z=Undo | Backspace=Up", Color.GREY_DARK);

        return h;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the display name "Route Planning"
     */
    @Override
    public String displayName() {
        return "Route Planning";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the full terminal name {@code "routeplanning"}
     */
    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the short terminal alias {@code "rp"}
     */
    @Override
    public String shortName() {
        return "rp";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line description of this tool's purpose
     */
    @Override
    public String desc() {
        return "Plan and build trade routes using Pipe, Grow, and Collapse operations.";
    }

    

    /**
     * Operation modes for route planning.
     */
    public enum Mode {
        PIPE, 
        GROW, 
        COLLAPSE 
    }

    /**
     * State within an operation.
     */
    public enum OperationState {
        IDLE, 
        KNOT_A_SELECTED, 
        PREVIEW, 
        CONFIRMED 
    }
}
