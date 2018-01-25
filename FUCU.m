num_cities = 100;
fileId = fopen("./data_set_tsp_2.txt", "r");
%distance_matrix = fscanf(fileId, "%d", [num_cities num_cities])
%[xcoords, ycoords] = TriangulatePoints(distance_matrix)
node_list = [];
x= [];
y = [];
rng(10);
for i = 1:num_cities
    x = [x, rand()*10];
    y = [y, rand()*10];
    node_list = [node_list, Node(x(i), y(i))];
    
end
%plot(x,y, 'o');

perimeter = CirclePath(node_list,x,y);
% figure(1);
% perimeter(1).plotPathStart(x,y);
for i = 1:numel(perimeter)
    node_list = node_list(node_list~=perimeter(i));
end
%node_list = node_list(node_list~=added_node);
for i = 1:(num_cities - numel(perimeter))+1
    [perimeter, added_node] = RecalculatePerimeter(node_list, perimeter);

    node_list = node_list(node_list~=added_node);

end
figure(1);
perimeter(1).plotPathStart(x,y);
hold off;
