classdef Node < matlab.mixin.SetGet
    
    properties
        connected
        previous
        xcoord
        ycoord
        basevector
        vector
        basemag
        segment_distance
        segment
        in_perimeter
        min_distance
        id
    end
    
    methods

        function nodeObj = Node(xcoord, ycoord, num_cities, id)
            nodeObj.min_distance = Inf;
            nodeObj.xcoord = xcoord;
            nodeObj.ycoord = ycoord;
            nodeObj.id = id;
            nodeObj.segment_distance = (ones(num_cities,num_cities, 3)*-1);
            nodeObj.segment = 0;
            nodeObj.in_perimeter = false;
        end
        function insertNode(nodeObj, node)
            if(node.in_perimeter)
                node.removeNode();
            end
            node.min_distance = Inf;
            node.set('connected', nodeObj.get('connected'));
            nodeObj.set('connected', node);
            node.in_perimeter = true;
        end
        function list = nodesUntil(nodeObj, end_id, list)
            if(nodeObj.id == end_id)
                list = [list, nodeObj];
            else
                list = [list, nodeObj, nodeObj.connected.nodesUntil(end_id, list)];
            end
        end
        function [distance_to_segment, curr_equidistant_under, curr_equidistant_over] = calculateDistanceToSegment(nodeObj, perim)
            seg = [perim.connected.xcoord - perim.xcoord, perim.connected.ycoord - perim.ycoord];
            point_vector = [nodeObj.xcoord - perim.xcoord, nodeObj.ycoord - perim.ycoord];
            %projecting the vector from the test point within the circle to
            %the segment vector
            projection_multiplier = dot(seg, point_vector)/dot(seg, seg);

            %when the projection is less than zero or greater than one, then the point is not over the 
            %line segment
            if(projection_multiplier < 0)
                distance_to_segment = norm([nodeObj.xcoord - perim.xcoord ,nodeObj.ycoord - perim.ycoord]);
                curr_equidistant_over = 0;
                curr_equidistant_under = 1;
            elseif(projection_multiplier > 1)
                distance_to_segment = norm([nodeObj.xcoord - perim.connected.xcoord ,nodeObj.ycoord - perim.connected.ycoord]);
                curr_equidistant_over = 1;
                curr_equidistant_under = 0;
            else
                distance_to_segment = norm(point_vector - (seg * projection_multiplier));
                curr_equidistant_under = 0;
                curr_equidistant_over = 0;
            end

            min_dist_flag = nodeObj.setDistanceToSegment(perim, perim.connected, distance_to_segment, ...
                curr_equidistant_under, curr_equidistant_over);
            if(min_dist_flag)
                nodeObj.setClosestSegment(nodeObj.findClosestSegment(perim, curr_equidistant_under, curr_equidistant_over))
            end
        end
        function [distance_to_segment, curr_equidistant_under, curr_equidistant_over] = calculateDistanceToBaseSegment(nodeObj)
            perim1 = nodeObj.connected;
            perim2 = nodeObj.previous;
            seg = [perim2.xcoord - perim1.xcoord, perim2.ycoord - perim1.ycoord];
            point_vector = [nodeObj.xcoord - perim1.xcoord, nodeObj.ycoord - perim1.ycoord];
            %projecting the vector from the test point within the circle to
            %the segment vector
            projection_multiplier = dot(seg, point_vector)/dot(seg, seg);

            %when the projection is less than zero or greater than one, then the point is not over the 
            %line segment
            if(projection_multiplier < 0)
                distance_to_segment = norm([nodeObj.xcoord - perim1.xcoord ,nodeObj.ycoord - perim1.ycoord]);
                curr_equidistant_over = 0;
                curr_equidistant_under = 1;
            elseif(projection_multiplier > 1)
                distance_to_segment = norm([nodeObj.xcoord - perim2.xcoord ,nodeObj.ycoord - perim2.ycoord]);
                curr_equidistant_over = 1;
                curr_equidistant_under = 0;
            else
                distance_to_segment = norm(point_vector - (seg * projection_multiplier));
                curr_equidistant_under = 0;
                curr_equidistant_over = 0;
            end

            min_dist_flag = nodeObj.setDistanceToSegment(perim1, perim2, distance_to_segment, ...
                curr_equidistant_under, curr_equidistant_over);
        end
        function [distance, under, over] = distanceToSegment(nodeObj, node1, node2)
            max_id = max(node1.id, node2.id);
            min_id = min(node1.id, node2.id);
            distance = nodeObj.segment_distance(max_id, min_id, 1);
            under = nodeObj.segment_distance(max_id, min_id, 2);
            over = nodeObj.segment_distance(max_id, min_id, 3);
        end
        function flag = setDistanceToSegment(nodeObj, node1, node2, distance, under, over)
            max_id = max(node1.id, node2.id);
            min_id = min(node1.id, node2.id);
            if(distance < nodeObj.min_distance)
                nodeObj.min_distance = distance;
                flag = true;
            else
                flag = false;
            end
            nodeObj.segment_distance(max_id, min_id, 1) = distance;
            nodeObj.segment_distance(max_id, min_id, 2) = under;
            nodeObj.segment_distance(max_id, min_id, 3) = over;
        end
        function [segment] = findClosestSegment(node, perim, curr_equidistant_under, curr_equidistant_over)
            if(curr_equidistant_under)
                curr_to_prev = [perim.previous.xcoord - perim.xcoord, perim.previous.ycoord - perim.ycoord];
                curr_to_next = [perim.connected.xcoord - perim.xcoord, perim.connected.ycoord - perim.ycoord];
                curr_to_point = [node.xcoord - perim.xcoord, node.ycoord - perim.ycoord];
                angle_prev = acos(dot(curr_to_prev,curr_to_point)/(norm(curr_to_prev)*norm(curr_to_point)));
                angle_curr = acos(dot(curr_to_next,curr_to_point)/(norm(curr_to_next)*norm(curr_to_point)));

                if(angle_curr < angle_prev)
                    segment = perim;   
                else
                    segment = perim.previous;
                end
            elseif(curr_equidistant_over)
                curr_to_prev = [perim.xcoord - perim.connected.xcoord, perim.ycoord - perim.connected.ycoord];
                curr_to_next = [perim.connected.connected.xcoord - perim.connected.xcoord, ...
                    perim.connected.connected.ycoord - perim.connected.ycoord];
                curr_to_point = [node.xcoord - perim.connected.xcoord, node.ycoord - perim.connected.ycoord];
                angle_prev = acos(dot(curr_to_prev,curr_to_point)/(norm(curr_to_prev)*norm(curr_to_point)));
                angle_curr = acos(dot(curr_to_next,curr_to_point)/(norm(curr_to_next)*norm(curr_to_point)));

                if(angle_curr < angle_prev)
                    segment = perim.connected;   
                else
                    segment = perim;
                end
            else
                segment = perim;    
            end
        end
        function setClosestSegment(nodeObj, node)
            nodeObj.segment = [node, node.connected];
        end
        function removeNode(nodeObj)
            nodeObj.previous.connected = nodeObj.connected;
            nodeObj.connected.previous = nodeObj.previous;
        end
        function set.connected(nodeObj, node)
            nodeObj.vector = [nodeObj.xcoord - node.xcoord, nodeObj.ycoord - node.ycoord];
            node.basevector = [node.xcoord-nodeObj.xcoord,node.ycoord-nodeObj.ycoord];
            node.basemag = norm(node.basevector);
            nodeObj.connected = node;
            node.previous = nodeObj;
            nodeObj.in_perimeter = true;
        end
        function angle = angleToVector(nodeObj, node)
            dot_product = dot(nodeObj.basevector, [nodeObj.xcoord-node.xcoord,nodeObj.ycoord-node.ycoord]);
            angle = acos(dot_product/(nodeObj.basemag*norm([nodeObj.xcoord-node.xcoord,nodeObj.ycoord-node.ycoord])))*180/pi;
        end
        function flag = equals(nodeObj, node)
            if(nodeObj.xcoord == node.xcoord && nodeObj.ycoord == node.ycoord)
                flag = true;
            else
                flag = false;
            end
        end
        function r = path(nodeObj, endNode)
            if(ismember(nodeObj,endNode) || isempty(nodeObj.connected))
                r = nodeObj;
            else
                endNode = [endNode, nodeObj];
                r = [nodeObj, nodeObj.connected.path(endNode)];
            end
        end
        function plotPathStart(nodeObj, x, y, numbers)
            hold on
            if(~isempty(nodeObj.connected))
                [xcoords, ycoords] = nodeObj.connected.plotPath(nodeObj, 1, numbers);
            end
            plot([xcoords, nodeObj.xcoord], [ycoords,nodeObj.ycoord], '-s')
            plot(x,y,'o')
        end
        function [xcoords, ycoords] = plotPath(nodeObj, firstNode, index, numbers)
            
            if(numbers == 1)
                text(nodeObj.xcoord,nodeObj.ycoord, cellstr(num2str(index)),'VerticalAlignment','bottom','HorizontalAlignment', 'right')
            elseif(numbers == 2)
                text(nodeObj.xcoord,nodeObj.ycoord, cellstr(num2str(nodeObj.id)),'VerticalAlignment','bottom','HorizontalAlignment', 'right')
            end
            if(ismember(nodeObj,firstNode) || isempty(nodeObj.connected))
                xcoords = [nodeObj.xcoord];
                ycoords = [nodeObj.ycoord];
            else

                firstNode = [firstNode,nodeObj];
                [x, y] = nodeObj.connected.plotPath(firstNode, index + 1, numbers);
                xcoords = [x,nodeObj.xcoord];
                ycoords = [y,nodeObj.ycoord];
                
            end
        end
    end
    
end

