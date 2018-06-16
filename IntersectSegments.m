function [ flag ] = IntersectSegments( p1, ep1, p2, ep2 )
    %INTERSECTSEGMENTS
    %   Checks to see if the line segments formed by the points intersect
    if ep1(1) < p1(1)
        endpoint1 = p1;
        point1 = ep1;
    else
        endpoint1 = ep1;
        point1 = p1;
    end
    
    if ep2(1) < p2(1)
        endpoint2 = p2;
        point2 = ep2;
    else
        endpoint2 = ep2;
        point2 = p2;
    end
    
    flag = false;
    
    slope1 = (endpoint1(2) - point1(2)) / (endpoint1(1) - point1(1));
    slope2 = (endpoint2(2) - point2(2)) / (endpoint2(1) - point2(1));
    
    intercept1 = point1(2) - point1(1) * slope1;
    intercept2 = point2(2) - point2(1) * slope2;
    
    x_intersect = (intercept2-intercept1)/(slope1-slope2);
    y_intersect = x_intersect*slope1 + intercept1;
    
    if x_intersect < endpoint1(1) && x_intersect > point1(1)
        if x_intersect < endpoint2(1) && x_intersect > point2(1)
            if (y_intersect < endpoint1(2) && y_intersect > point1(2)) || (y_intersect > endpoint1(2) && y_intersect < point1(2))
                if (y_intersect < endpoint2(2) && y_intersect > point2(2)) || (y_intersect > endpoint2(2) && y_intersect < point2(2))
                    flag = true;
                end
            end
        end
    end
end

