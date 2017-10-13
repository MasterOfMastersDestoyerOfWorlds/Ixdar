function [path] = CirclePath(node_list)
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
        base_vector = [average_x - node_list(i).xcoord, average_y - node_list(i).ycoord];
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
    while(first || ~(current == edge_node(1)))
        for i = 1:list_length
            if(i ~= current && i ~= last)
                angle = node_list(current).angleToVector(node_list(i));
                if(angle > bigest_angle)
                    bigest_angle = angle;
                    bigest_node = i;
                end
            end
        end

        node_list(current).set('connected',node_list(bigest_node));
        last = current;
        current = bigest_node;
        bigest_angle = 0;
        first = false;
    end
    
    % Build the path from the connected nodes, draw the path, and return the path 
    path = [node_list(edge_node(1)),node_list(edge_node(1)).connected.path(node_list(edge_node(1)))];
    path_length = numel(path);
    path_x = [];
    path_y = [];
    for i = 1:path_length
        path_x = [path_x , path(i).xcoord];
        path_y = [path_y, path(i).ycoord];
    end
    
    % Draw all of the segments of the path
    figure(1);
    hold on;
    plot(path_x,path_y);
    xlim([-10 10]);
    ylim([-10 10]);
    hold off;
end