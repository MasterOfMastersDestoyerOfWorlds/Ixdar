package ixdar.platform.automation;

import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.automation.AutomationRouteRegistry_AutomationRoutes;
import ixdar.annotations.automation.AutomationRoute;

public class AutomationRouteMap {

    public static final Map<String, Supplier<? extends AutomationRoute>> MAP;

    static {
        MAP = AutomationRouteRegistry_AutomationRoutes.MAP;
    }
}
