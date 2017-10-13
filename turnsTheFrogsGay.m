function [perimeter] = turnsTheFrogsGay(node_list, perimeter)
    syms x;
    list_length = numel(node_list);
    num_segments = perimeter(1).segmentid;
    closest_node = node_list(1);
    closest_distance = Inf;
    segment_location = 1;
    for i = 1:list_length
        for j = 1:num_segments
            segment = [perimeter(j).xcoord - perimeter(j).connected.xcoord, perimeteer(j).ycoord - perimeter(j).connected.ycoord];
            point_vector = [perimeter(j).xcoord - node_list(i).xcoord, perimeter(j).ycoord - node_list(i).ycoord];
            mag_segment = norm(segment);
            projection_multiplier = solve(x.*segment.*(dot(segment, point_vector)/(norm(segment))^2) == segment, x);
            
            distance_to_segment = Inf;
            if(projection_multiplier <= 0)
                distance_to_multiplier = norm([perimeter(j).xcoord - node_list(i).xcoord, perimeter(j).ycoord - node_list(i).ycoord]);
            elseif(projection_multiplier > 1)
                distance_to_multiplier = norm([perimeter(j).connected.xcoord - node_list(i).xcoord, perimeter(j).connected.ycoord - node_list(i).ycoord]);
            else
                distance_to_multiplier = norm(cross(segment,point_vector))/norm(segment);
            end
            if(distance_to_segment<closest_distance)
                closest_node = node_list(i);
                closest_distance = distance_to_segment;
                segment_location = j;
            end
        end
    end
    perimeter(j).insertNode(closest_node);
    perimeter = [perimeter, closest_node];
end