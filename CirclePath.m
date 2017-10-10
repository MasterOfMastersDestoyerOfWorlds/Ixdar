node_list = [Node(0,0), Node(0,1), Node(1,0),Node(1,1)];
list_length = numel(node_list);


average_x = 0;
average_y = 0;
for i = 1:list_length
    average_x = average_x + node_list(i).xcoord;
    average_y = average_y + node_list(i).ycoord;
end
average_x = average_x/list_length;
average_y = average_y/list_length;

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
node_list(1)
node_list(2)
node_list(3)
node_list(4)

path = node_list(edge_node(1)).path(node_list(edge_node(1)));
path_length = numel(path);
path_x = [];
path_y = [];
for i = 1:path_length
    path_x = [path_x , path(i).xcoord]
    path_y = [path_y, path(i).ycoord]
end

plot(path_x,path_y);
