function [perimeter, closest_node] = RecalculatePerimeter(node_list, perimeter)
%Finds the closest node to the perimeter and inserts the node into the
%correct position in the path
    syms x;
    list_length = numel(node_list);
    num_segments = numel(perimeter)-1;
    closest_node = node_list(1);
    closest_distance = Inf;
    segment_location = 0;
    for i = 1:list_length
        for j = 1:num_segments
            segment = [perimeter(j).vector,0];
            point_vector = [perimeter(j).xcoord - node_list(i).xcoord, perimeter(j).ycoord - node_list(i).ycoord,0];
            mag_segment = norm(segment);
            projection_multiplier = (dot(segment, point_vector)/(mag_segment)^2);
            
            distance_to_segment = Inf;
            flag = false;
            if(projection_multiplier <= 0)
                distance_to_segment = norm([perimeter(j).xcoord - node_list(i).xcoord, perimeter(j).ycoord - node_list(i).ycoord]);
            elseif(projection_multiplier >= 1)
                flag = true;
                distance_to_segment = norm([perimeter(j).connected.xcoord - node_list(i).xcoord, perimeter(j).connected.ycoord - node_list(i).ycoord]);
            else
                distance_to_segment = norm(cross(segment,point_vector))/norm(segment);
            end
            if(distance_to_segment == closest_distance)
                first = [closest_node.xcoord - segment_location.xcoord, closest_node.ycoord - segment_location.ycoord];
                dot_product = dot(first, segment_location.basevector);
                first_angle = acos(dot_product/(norm(first)*norm(segment_location.basevector)))*180/pi;
                
                second = [node_list(i).xcoord - perimeter(j).xcoord, node_list(i).ycoord - perimeter(j).ycoord];
                dot_product = dot(first, perimeter(j).basevector);
                second_angle = acos(dot_product/(norm(second)*norm(perimeter(j).basevector)))*180/pi;

                if(first_angle > second_angle)
                    closest_node = node_list(i);
                    closest_distance = distance_to_segment;
                    if(~flag)
                        segment_location = perimeter(j);
                    else
                        segment_location = perimeter(j).connected;
                    end
                end
            end
            if(distance_to_segment<closest_distance)
                closest_node = node_list(i);
                closest_distance = distance_to_segment;
                if(~flag)
                    segment_location = perimeter(j);
                else
                    segment_location = perimeter(j).connected;
                end
            end
        end
    end
    segment_location.insertNode(closest_node);

    perimeter = [perimeter, closest_node];
end