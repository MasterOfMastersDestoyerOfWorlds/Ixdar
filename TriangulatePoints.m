function [xcoords, ycoords] = TriangulatePoints(distance_matrix)
    %Turns a matrix of distances in relation to each node into a graph that
    %is realizable (needs at least 3 points in order to run) and returns a
    %list of coordinates describing this graph
    size_matrix = size(distance_matrix);
    size_matrix = size_matrix(1);
    
    %Set the orgin at abritrary point
    xcoords = zeros(1, size_matrix);
    ycoords = zeros(1, size_matrix);
    
    %Set the point that defines the x axis (i.e. unit vector <1,0> from the
    %origin)
    xaxis = [distance_matrix(1,2), 0, 2];
    xcoords(xaxis(3)) = xaxis(1);
    ycoords(xaxis(3)) = xaxis(2);
    
    %Find thrid point that will define the direction of the first and
    %second quadrant(this is needed for tirangulation and to prevent
    %mirroring errors[i.e. the third and fourth quadrant points found to be
    %in the first and second quadrant erronously])
    angle_to_anchor = (distance_matrix(2,3)^2-distance_matrix(1,2)^2-distance_matrix(1,3)^2)/((-1)*2*distance_matrix(1,2)*distance_matrix(1,3))
    angle_to_anchor = acos(angle_to_anchor)
    anchor_point = [cos(angle_to_anchor)*distance_matrix(1,3), sin(angle_to_anchor)*distance_matrix(1,3), 3];
    xcoords(anchor_point(3)) = anchor_point(1);
    ycoords(anchor_point(3)) = anchor_point(2);
    
    %Triangulate the rest of the points using the intersection of the three
    %circles with centers at the origin, the x axis point and the anchor
    %point with radii defined by the distances from those points to the
    %point in question
    for i = 4:size_matrix
        first_intersection = CircIntersection(0,0,distance_matrix(1,i), xaxis(1),xaxis(2), distance_matrix(xaxis(3),i));
        second_intersection = CircIntersection(0,0,distance_matrix(1,i), anchor_point(1),anchor_point(2), distance_matrix(anchor_point(3),i));
        for j = 1:2
            for k = 1:2
                if(first_intersection(1,j) == second_intersection(1,k) && first_intersection(2,j) == second_intersection(2,k))
                    xcoords(i) = first_intersection(1,j);
                    ycoords(i) = first_intersection(2,j);
                end
            end
        end
        
    end
end