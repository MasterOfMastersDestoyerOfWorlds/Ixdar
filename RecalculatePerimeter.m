function [closest_node, flag, add_perimeter_flag] = RecalculatePerimeter(node_list, perimeter, last_node_added)
%Finds the closest node to the perimeter and inserts the node into the
%correct position in the path
    list_length = numel(node_list);
    closest_node = node_list(1);
    closest_distance = Inf;
    segment_location = 0;
    temp_perimeter = perimeter;
    
    %TODO: if your segment gets overwritten then you have no minimum
    %distance and relook at all segments for that node ie. segmentid no
    %longer is connected to segmentconnectedid

    

    for i = 1:list_length
        node = node_list(i);
        if exist('last_node_added','var')
            if(node.id == last_node_added.id)
                perimeter = temp_perimeter;
%             elseif(node.segment(1).connected.id == node.segment(2).id)
%                 perimeter = temp_perimeter;
%                 %perimeter = [last_node_added.previous, last_node_added, node.segment(1)];
            else
%                 perimeter = temp_perimeter;
                perimeter = node.segment(1).nodesUntil(node.segment(2).id,[last_node_added.previous, last_node_added, last_node_added.connected]);
            end
        end
        num_segments = numel(perimeter);
        for j = 1:num_segments
            %NOTE: the cross product is not a multidimensional function and
            %can only work in 3 dimenisions and 7 dimensions avoid at all
            %costs
            %finding segment vector created from point j to the connector
            %of point j

            perim = perimeter(j);
            if(~node.in_perimeter || (perim.id ~= node.id && perim.id ~= node.previous.id))
                [distance_to_segment, curr_equidistant_under, curr_equidistant_over] = node.distanceToSegment(perim, perim.connected);
                if(distance_to_segment == -1)
                    [distance_to_segment, curr_equidistant_under, curr_equidistant_over] = node.calculateDistanceToSegment(perim);
                end
                if(node.in_perimeter && node.distanceToSegment(node.previous, node.connected) == -1)
                    [distance_to_base_segment, curr_equidistant_under_base, curr_equidistant_over_base] = node.calculateDistanceToBaseSegment();
                    node.setDistanceToSegment(node.previous, node.connected, distance_to_base_segment, ...
                        curr_equidistant_under_base, curr_equidistant_over_base);
                end
                
    %if the node is in the perimeter(i.e already added to the list), the ncheck
    %if the distance is the lowest distance and if it is a lower distance than
    %the distance to its segment
                if((~node.in_perimeter && distance_to_segment < closest_distance) || ...
                    (node.in_perimeter && distance_to_segment < closest_distance && ...
                    distance_to_segment < node.distanceToSegment(node.previous, node.connected)))
    %             if(min_dist_flag || distance_to_segment < closest_distance)
                    segment = node.findClosestSegment(perim, curr_equidistant_under, curr_equidistant_over);
                    if(~node.in_perimeter)
                        node.setClosestSegment(segment);
                        closest_distance = distance_to_segment;
                        closest_node = node;
                        segment_location = segment;
                    else
                        curr_distance = norm([node.xcoord-node.connected.xcoord,node.ycoord-node.connected.ycoord]) + ...
                            norm([node.xcoord-node.previous.xcoord, node.ycoord-node.previous.ycoord]) + ...
                            norm([segment.xcoord-segment.connected.xcoord,segment.ycoord-segment.connected.ycoord]);
                        
                        next_distance = norm([node.xcoord-segment.xcoord, node.ycoord-segment.ycoord]) + ...
                            norm([node.xcoord-segment.connected.xcoord, node.ycoord-segment.connected.ycoord]) + ...
                            norm([node.previous.xcoord-node.connected.xcoord, node.previous.ycoord-node.connected.ycoord]);
                        if(next_distance < curr_distance && node.id ~= segment.id && node.previous.id ~= segment.id)
                            node.setClosestSegment(segment);
                            closest_distance = distance_to_segment;
                            closest_node = node;
                            segment_location = segment;
                        end
                    end
    %                 else
    %                     closest_distance = distance_to_segment;
    %                     closest_node = node;
    %                     segment_location = node.segment;
    %                 end
                end
    %             end
            end
        end
    end
    %put the node in the linked list 
    add_perimeter_flag = ~closest_node.in_perimeter;
    flag = (segment_location ~= 0);
    if(flag)
        segment_location.insertNode(closest_node);
    end
end