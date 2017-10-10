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

edge_node = [];
for i = 1:list_length
    base_vector = [average_x - node_list(i).xcoord, average_y - node_list(i).ycoord];
    node_list(i).setBaseVector(base_vector);
    if(~isEmpty(edge_node) && norm(base_vector) > edge_node(2))
        edge_node(1) = node_
end