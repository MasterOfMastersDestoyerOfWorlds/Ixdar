function [path] = CirclePath(node_list,x,y)
    %Finds the path in the graph that encircles all nodes
    
    list_length = numel(node_list);

    %Find the center point of the data set in order to determine the normal
    %vector to each point
    average_x = 0;
    average_y = 0;
    for i = 1:list_length
        average_x = average_x + node_list(i).xcoord;
        average_y = average_y + node_list(i).ycoord;
    end
    average_x = average_x/list_length;
    average_y = average_y/list_length;

    %Find the node most distant from the center in order to make sure that the edge node is on an 
    %edge of the dataset 
    edge_node = [0, 0];
    for i = 1:list_length
        base_vector = [node_list(i).xcoord - average_x, node_list(i).ycoord - average_y];
        node_list(i).basevector = base_vector;
        node_list(i).basemag = norm(base_vector);
        if(node_list(i).basemag > edge_node(2))
            
            edge_node(1) = i;
            edge_node(2) = node_list(i).basemag;
        end
    end

    % Build the path that contains all nodes (i.e. each connection has the
    % greatest angle possible between the connection vector and the normal
    % vector)
    first = true;
    current = edge_node(1);
    bigest_node = 0;
    bigest_angle = 0;
    last = 0;
    num_tried = 0;
    path = [node_list(edge_node(1))];
    while((first || ~(current == edge_node(1)))&& num_tried < list_length)
        for i = 1:list_length
            if(i ~= current && i ~= last && (~ismember(node_list(i),path) || node_list(i).equals(node_list(edge_node(1)))))
                angle = node_list(current).angleToVector(node_list(i));
                
                if(angle > bigest_angle)
                    bigest_angle = angle;
                    bigest_node = i;
                end
            end
        end
        num_tried = num_tried + 1;
        node_list(current).set('connected',node_list(bigest_node));
        path = [path, node_list(bigest_node)];
        last = current;
        current = bigest_node;
        bigest_angle = 0;
        first = false;
    end
    
    % Build the path from the connected nodes, draw the path, and return the path 
    %path = [node_list(edge_node(1)),node_list(edge_node(1)).connected.path(node_list(edge_node(1)).connected)];
end