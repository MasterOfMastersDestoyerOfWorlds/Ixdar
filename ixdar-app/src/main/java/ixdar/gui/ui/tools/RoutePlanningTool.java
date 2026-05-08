package ixdar.gui.ui.tools;

import java.util.ArrayList;

import org.joml.Vector2f;

import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.knot.GrowRecord;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.OperationRecord;
import ixdar.geometry.knot.PipeRecord;
import ixdar.geometry.knot.RouteOperationStack;
import ixdar.geometry.knot.Segment;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.sdf.SDFCircleSimple;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.scenes.trade.TradeScene;

/**
 * Tool for planning and building trade routes between cities. Supports Pipe,
 * Grow, and Collapse operations with undo/redo. Activated after the player
 * selects their headquarters.
 */
public class RoutePlanningTool extends Tool {
    public static final String P = "P";
    public static final String STATE = " state=";
    public static final String MODE = " mode=";
    public static final String SELECTED_ROUTE_CITY = "Selected route city: ";
    public static final String SELECTED = "Selected: ";
    public static final String STR = " <-> ";
    public static final String TO = " to ";
    public static final String SEGMENTS = " segments";
    public static final float NUM_3 = 3f;
    public static final float NUM_25 = 25f;
    public static final float NUM_2 = 2f;
    public static final float NUM_4 = 4f;
    public static final int NUM_3_2 = 3;
    public static final int NUM_4_2 = 4;
    public static final int NUM_80 = 80;
    public static final int NUM_71 = 71;
    public static final int NUM_67 = 67;
    public static final int NUM_257 = 257;
    public static final int NUM_256 = 256;
    public static final int NUM_90 = 90;
    public static final int NUM_89 = 89;
    public static final int NUM_259 = 259;

    
    private static final Color ROUTE_COLOR = Color.MAGENTA;
    private static final Color ROUTE_PREVIEW_COLOR = new ColorRGB(1f, 0f, 1f, 0.5f);
    private static final Color EDGE_REMOVE_COLOR = new ColorRGB(1f, 0.2f, 0.2f, 0.8f);
    private static final Color EDGE_ADD_COLOR = new ColorRGB(0.2f, 1f, 0.2f, 0.8f);
    private static final Color CROSSING_COLOR = new ColorRGB(0.2f, 0.5f, 1f, 0.8f);
    private static final Color BUTTON_COLOR = new ColorRGB(0.3f, 0.3f, 0.3f, 0.9f);
    private static final Color BUTTON_SELECTED_COLOR = new ColorRGB(0.5f, 0.5f, 0.8f, 0.9f);
    private static final Color BUTTON_HOVER_COLOR = new ColorRGB(0.4f, 0.4f, 0.5f, 0.9f);
    private static final Color SELECTED_KNOT_COLOR = new ColorRGB(1f, 1f, 0f, 0.5f);

    
    private static final float TOOLBAR_HEIGHT = 50f;
    private static final float BUTTON_SIZE = 40f;
    private static final float BUTTON_PADDING = 10f;

    

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

    
    private RouteOperationStack operationStack = new RouteOperationStack();

    
    private Knot currentRoute;

    
    private Knot currentViewKnot; 
    private ArrayList<Knot> hierarchyPath = new ArrayList<>(); 

    
    private City hoveredCity;
    private Segment hoveredEdge;

    
    private SDFLine routeLine = new SDFLine();
    private SDFCircleSimple buttonCircle = new SDFCircleSimple();
    private SDFCircleSimple highlightCircle = new SDFCircleSimple();

    
    private int hoveredButton = -1; 

    

    /**
     * Build the route-planning tool, binding it to the owning trade scene and
     * the city network whose cities and knot mappings it edits.
     *
     * @param tradeScene the trade scene that owns this tool
     * @param network    the city network being edited
     */
    public RoutePlanningTool(TradeScene tradeScene, CityNetwork network) {
        this.tradeScene = tradeScene;
        this.network = network;
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
    public void draw(Camera2D camera, float lineThickness) {
        
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

        routeLine.setStroke(NUM_3, false);

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
        highlightCircle.draw(new Vector2f(x, y), NUM_25, color, camera);
    }

    private void drawPreviewLine(Camera2D camera, City from, City to) {
        if (from == null || to == null) {
            return;
        }
        routeLine.setStroke(NUM_2, true);
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

        routeLine.setStroke(NUM_3, false);

        
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

        routeLine.setStroke(NUM_3, false);

        
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

        routeLine.setStroke(NUM_4, dashed);
        float x1 = camera.pointTransformX((float) edge.first.p.getScreenX());
        float y1 = camera.pointTransformY((float) edge.first.p.getScreenY());
        float x2 = camera.pointTransformX((float) edge.last.p.getScreenX());
        float y2 = camera.pointTransformY((float) edge.last.p.getScreenY());
        routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), color, camera);
    }

    private void drawToolbar(Camera2D camera) {
        float wWidth = Platforms.get().getWindowWidth();

        
        float toolbarY = TOOLBAR_HEIGHT / 2;
        float startX = wWidth / 2 - (NUM_3_2 * BUTTON_SIZE + 2 * BUTTON_PADDING) / 2;

        
        drawToolbarButton(camera, startX, toolbarY, 0, P, currentMode == Mode.PIPE);

        
        drawToolbarButton(camera, startX + BUTTON_SIZE + BUTTON_PADDING, toolbarY, 1, "G", currentMode == Mode.GROW);

        
        drawToolbarButton(camera, startX + 2 * (BUTTON_SIZE + BUTTON_PADDING), toolbarY, 2, "C",
                currentMode == Mode.COLLAPSE);

        
        if (operationStack.canUndo()) {
            drawToolbarButton(camera, BUTTON_PADDING + BUTTON_SIZE / 2, toolbarY, NUM_3_2, "Z", false);
        }

        
        if (state == OperationState.PREVIEW) {
            drawToolbarButton(camera, wWidth - BUTTON_PADDING - BUTTON_SIZE / 2, toolbarY, NUM_4_2, "OK", false);
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
        syncRouteReference();
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
            System.out.println("Pipe preview: " + selectedCityA.name + STR + city.name);
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
        
        if (currentRoute == null) {
            selectedEdgeA = null;
            selectedEdgeB = null;
            return;
        }

        
        selectedEdgeA = null;
        selectedEdgeB = null;
    }

    private void computeDefaultGrowEdge() {
        if (currentRoute == null || selectedCityB == null) {
            selectedEdgeA = null;
            return;
        }

        
        
        if (!currentRoute.manifoldSegments.isEmpty()) {
            selectedEdgeA = currentRoute.manifoldSegments.get(0);
        }
    }

    /**
     * Handle keyboard input.
     *
     * @param key the key code
     * @return true if the key was handled
     */
    public boolean onKeyPress(int key) {
        System.out.println("[RoutePlanningTool] onKeyPress: " + key + STATE + state + MODE + currentMode);

        
        if (key == NUM_80) { 
            System.out.println("[RoutePlanningTool] Setting PIPE mode");
            setMode(Mode.PIPE);
            return true;
        }
        
        if (key == NUM_71) { 
            System.out.println("[RoutePlanningTool] Setting GROW mode");
            setMode(Mode.GROW);
            return true;
        }
        
        if (key == NUM_67) { 
            System.out.println("[RoutePlanningTool] Setting COLLAPSE mode");
            setMode(Mode.COLLAPSE);
            return true;
        }
        
        if (key == NUM_257) { 
            System.out.println("[RoutePlanningTool] Enter pressed, state=" + state);
            if (state == OperationState.PREVIEW) {
                System.out.println("[RoutePlanningTool] Executing operation");
                executeOperation();
                return true;
            }
        }
        
        if (key == NUM_256) { 
            System.out.println("[RoutePlanningTool] Escape - cancelling");
            resetOperation();
            return true;
        }
        
        if (key == NUM_90) { 
            System.out.println("[RoutePlanningTool] Undo");
            undo();
            return true;
        }
        
        if (key == NUM_89) { 
            System.out.println("[RoutePlanningTool] Redo");
            redo();
            return true;
        }
        
        if (key == NUM_259) { 
            if (navigateUp()) {
                return true;
            }
        }

        return false;
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

        
        if (currentRoute != null && selectedCityA == null && selectedCityB != null) {
            Knot knotB = network.getKnotForCity(selectedCityB);
            if (knotB != null && knotB.isSingleton()) {
                Segment lowestCostEdge = selectedEdgeA != null ? selectedEdgeA
                        : currentRoute.getLowestCostGrowEdge(knotB);
                if (lowestCostEdge != null) {
                    GrowRecord record = currentRoute.grow(knotB, lowestCostEdge);
                    operationStack.push(record);
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
            
            System.out.println("Creating initial route: " + selectedCityA.name + STR + selectedCityB.name);

            PipeRecord record = Knot.pipeSimple(knotA, knotB);
            currentRoute = record.resultKnot;
            setCurrentRoute(currentRoute);

            operationStack.push(record);
            System.out.println("Route created! Loop has " + currentRoute.manifoldSegments.size() + SEGMENTS);
        } else {
            
            System.out.println("Piping " + selectedCityB.name + " to route");

            if (knotB.isSingleton()) {
                
                Segment lowestCostEdge = currentRoute.getLowestCostGrowEdge(knotB);
                if (lowestCostEdge != null) {
                    GrowRecord record = currentRoute.grow(knotB, lowestCostEdge);
                    operationStack.push(record);
                    System.out.println("Grew route to include " + selectedCityB.name);
                }
            } else {
                
                PipeRecord record = Knot.pipe(currentRoute, knotB, selectedEdgeA, selectedEdgeB);
                if (record != null) {
                    currentRoute = record.resultKnot;
                    setCurrentRoute(currentRoute);
                    operationStack.push(record);
                }
            }
        }
    }

    private void executeGrow() {
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
        operationStack.push(record);
        System.out.println("Route now has " + currentRoute.manifoldSegments.size() + SEGMENTS);
    }

    private void executeCollapse() {
        if (currentRoute == null || selectedCityA == null || selectedCityB == null) {
            return;
        }

        System.out.println("Collapsing from " + selectedCityA.name + TO + selectedCityB.name);

        
        
        
    }

    /**
     * Undo the most recent route operation if the operation stack permits,
     * resyncing the live route reference around the change.
     */
    public void undo() {
        syncRouteReference();
        if (operationStack.canUndo()) {
            OperationRecord record = operationStack.undo();
            syncRouteReference();
            System.out.println("Undo: " + record.getDescription());
        }
    }

    /**
     * Redo the most recently undone route operation if the operation stack
     * permits, resyncing the live route reference around the change.
     */
    public void redo() {
        syncRouteReference();
        if (operationStack.canRedo()) {
            OperationRecord record = operationStack.redo();
            syncRouteReference();
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
     * Set the root route and initialize view at top level.
     *
     * @param route the root route knot
     */
    public void setCurrentRoute(Knot route) {
        this.currentRoute = route;
        this.currentViewKnot = route;
        rebuildHierarchyPath();
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

    private void syncRouteReference() {
        if (currentRoute == null) {
            return;
        }
        boolean routeCleared = currentRoute.manifoldSegments == null || currentRoute.manifoldSegments.isEmpty();
        boolean routeMissingPoints = currentRoute.knotPoints == null || currentRoute.knotPoints.isEmpty();
        if (routeCleared && routeMissingPoints) {
            currentRoute = null;
            currentViewKnot = null;
            hierarchyPath.clear();
            resetOperation();
        }
    }

    

    /**
     * Update the hovered city.
     *
     * @param city the city being hovered, or null
     */
    public void updateHoveredCity(City city) {
        this.hoveredCity = city;
    }

    /**
     * Get the currently hovered city.
     *
     * @return the hovered city, or null
     */
    public City getHoveredCity() {
        return hoveredCity;
    }

    /**
     * Update hovered button based on mouse position.
     *
     * @param mouseX mouse X in screen coordinates
     * @param mouseY mouse Y in screen coordinates
     */
    public void updateHoveredButton(float mouseX, float mouseY) {
        float wWidth = Platforms.get().getWindowWidth();
        float wHeight = Platforms.get().getWindowHeight();
        
        float toolbarY = TOOLBAR_HEIGHT / 2; 
        float startX = wWidth / 2 - (NUM_3_2 * BUTTON_SIZE + 2 * BUTTON_PADDING) / 2;

        
        
        float mouseYOpenGL = wHeight - mouseY;

        hoveredButton = -1;

        
        if (mouseYOpenGL < TOOLBAR_HEIGHT) {
            
            if (isInButton(mouseX, mouseYOpenGL, startX, toolbarY)) {
                hoveredButton = 0; 
            } else if (isInButton(mouseX, mouseYOpenGL, startX + BUTTON_SIZE + BUTTON_PADDING, toolbarY)) {
                hoveredButton = 1; 
            } else if (isInButton(mouseX, mouseYOpenGL, startX + 2 * (BUTTON_SIZE + BUTTON_PADDING), toolbarY)) {
                hoveredButton = 2; 
            } else if (isInButton(mouseX, mouseYOpenGL, BUTTON_PADDING + BUTTON_SIZE / 2, toolbarY)
                    && operationStack.canUndo()) {
                hoveredButton = NUM_3_2; 
            } else if (isInButton(mouseX, mouseYOpenGL, wWidth - BUTTON_PADDING - BUTTON_SIZE / 2, toolbarY)
                    && state == OperationState.PREVIEW) {
                hoveredButton = NUM_4_2; 
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
            h.addWord("Connect two cities/knots into one loop", Color.LIGHT_GRAY);
            break;
        case 1:
            h.addWord("Grow (G)", Color.WHITE);
            h.wrap();
            h.addWord("Insert a city into an existing route edge", Color.LIGHT_GRAY);
            break;
        case 2:
            h.addWord("Collapse (C)", Color.WHITE);
            h.wrap();
            h.addWord("Compress a linked chain while preserving one loop", Color.LIGHT_GRAY);
            break;
        case NUM_3_2:
            h.addWord("Undo (Ctrl+Z)", Color.WHITE);
            h.wrap();
            h.addWord("Revert the last route operation", Color.LIGHT_GRAY);
            break;
        case NUM_4_2:
            h.addWord("Confirm (Enter)", Color.WHITE);
            h.wrap();
            h.addWord("Apply the current preview operation", Color.LIGHT_GRAY);
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
     * @param mouseX mouse X in screen coordinates
     * @param mouseY mouse Y in screen coordinates
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
        case NUM_3_2:
            undo();
            return true;
        case NUM_4_2:
            executeOperation();
            return true;
        default:
            return false;
        }
    }

    

    /**
     * Clear pending operation state and the hover caches (city and toolbar
     * button) on tool (de)activation.
     */
    @Override
    public void reset() {
        resetOperation();
        hoveredCity = null;
        hoveredButton = -1;
    }

    /**
     * If a preview is currently shown, execute the previewed operation
     * (Pipe / Grow / Collapse). No-op otherwise.
     */
    @Override
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
     * Get the current route.
     *
     * @return the current route knot, or null
     */
    public Knot getCurrentRoute() {
        return currentRoute;
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
        return operationStack.canUndo();
    }

    /**
     * Whether the operation stack has a redoable operation.
     *
     * @return true if the operation stack has a redoable operation
     */
    public boolean canRedoOperation() {
        return operationStack.canRedo();
    }

    /**
     * Build the side-panel text: hierarchy breadcrumb (if a route exists),
     * the active mode, a state-specific instruction, and a keybind cheat
     * sheet at the bottom.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();

        
        if (currentRoute != null && !hierarchyPath.isEmpty()) {
            h.addWord("Route: ", Color.LIGHT_GRAY);
            h.addWord(getHierarchyPathString(), Color.YELLOW);
            if (canNavigateUp()) {
                h.addWord(" [Backspace=Up]", Color.LIGHT_GRAY);
            }
            if (canNavigateDown()) {
                h.addWord(" [Click child=Down]", Color.LIGHT_GRAY);
            }
            h.wrap();
        }

        
        h.addWord("[" + currentMode.name() + "] ", Color.CYAN);

        switch (state) {
        case IDLE:
            switch (currentMode) {
            case PIPE:
                if (currentRoute == null) {
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
        h.addWord("P=Pipe G=Grow C=Collapse | Ctrl+Z=Undo | Backspace=Up", Color.LIGHT_GRAY);

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
    public String fullName() {
        return "routeplanning";
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
