function [perimeter, closest_node] = RecalculatePerimeter(node_list, perimeter)
%Finds the closest node to the perimeter and inserts the node into the
%correct position in the path
    syms x t;
    list_length = numel(node_list);
    num_segments = numel(perimeter)-1;
    closest_node = node_list(1);
    closest_distance = Inf;
    segment_location = 0;

    for i = 1:list_length
        for j = 1:num_segments
            %finding segment vector created from point j to the connector
            %of point j
            segment = [perimeter(j).xcoord - perimeter(j).connected.xcoord , perimeter(j).ycoord - perimeter(j).connected.ycoord,0];
            point_vector = [perimeter(j).xcoord - node_list(i).xcoord,perimeter(j).ycoord - node_list(i).ycoord,0];
            mag_segment = norm(segment);
            %projecting the vector from the test point within the circle to
            %the segment vector
            projection_multiplier = (dot(segment, point_vector)/(mag_segment)^2);

            distance_to_segment = Inf;
            %when the projection is less than one or greater than one, then the point is not over the 
            %line segment
            if(projection_multiplier <= 0 || projection_multiplier >= 1)
                distance_to_segment = norm([perimeter(j).xcoord - node_list(i).xcoord, perimeter(j).ycoord - node_list(i).ycoord]);
                segment = perimeter(j);
            %otherwise it is over the line segement
            elseif(projection_multiplier < 1)
                distance_to_segment = norm(cross(segment,point_vector))/norm(segment);
                segment = perimeter(j);
            end
            if(distance_to_segment<closest_distance)
                visible_perimeter = true;
                %see if the line segment from the internal point to the
                %ends of the segment intersects any other line segments
                %from the perimeter
                for k = 1:num_segments+1
                    if(k ~= j)
                        if(IntersectSegments([node_list(i).xcoord, node_list(i).ycoord],[perimeter(j).xcoord, perimeter(j).ycoord],[perimeter(k).xcoord, perimeter(k).ycoord], [perimeter(k).connected.xcoord, perimeter(k).connected.ycoord]))
                            visible_perimeter = false;
                        end
                        if(IntersectSegments([node_list(i).xcoord, node_list(i).ycoord],[perimeter(j).connected.xcoord, perimeter(j).connected.ycoord],[perimeter(k).xcoord, perimeter(k).ycoord], [perimeter(k).connected.xcoord, perimeter(k).connected.ycoord]))
                            visible_perimeter = false;
                        end
                    end
                end
                if(visible_perimeter)
                    closest_node = node_list(i);
                    closest_distance = distance_to_segment;
                    segment_location = perimeter(j);
                end
            end
        end
    end
    %put the node in the linked list 
    segment_location.insertNode(closest_node);

    perimeter = [perimeter, closest_node];
end