function [xcoords, ycoords] = TriangulatePoints(distance_matrix)
    %needs at least 3 points in order to run
    size_matrix = size(distance_maxtrix);
    size_matrix = size_matrix(1);
    
    xcoords = zeros(1, size_matrix);
    ycoords = zeros(1, size_matrix);
    
    xaxis = [distance_matrix(1,2), 0, 2];
    xcoords(xaxis(3)) = xaxis(1);
    ycoords(xaxis(3)) = xaxis(2);
    
    angle_to_anchor = acos((distance_matrix(2,3)^2-distance_matrix(1,2)^2-distance_matrix(1,3)^2)/((-1)*distance_matrix(1,2)*distance_matrix(1,3)));
    anchor_point = [cos(angle_to_anchor)*distance_matrix(1,3), sin(angle_to_anchor)*distance(1,3), 3];
    xcoords(anchor_point(3)) = anchor_point(1);
    ycoords(anchor_point(3)) = anchor_point(2);
    
    for i = 4:size_matrix
        first_intersection = circcirc(0,0,distance_matrix(1,i), xaxis(1),xaxis(2), distance_matrix(xaxis(3),i));
        second_intersection = circcirc(0,0,distance_matrix(1,i), anchor_point(1),anchor_point(2), distance_matrix(xaxis(3),i));
        for j = 1:numel(first_intersection)
            for k = 1:numel(second_intersection)
                if(first_intersection(1,j) == second_intersection(1,k) && first_inersection(2,j) == second(2,k))
                    xcoords(i) = first_intersection(1,j);
                    ycoords(i) = first_intersection(2,j);
                end
            end
        end
        
    end
end