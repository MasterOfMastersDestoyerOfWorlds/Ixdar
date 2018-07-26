num_cities = 100;
%fileId = fopen("./data_set_tsp_2.txt", "r");
%distance_matrix = fscanf(fileId, "%d", [num_cities num_cities])
%[xcoords, ycoords] = TriangulatePoints(distance_matrix)
node_list = [];
x= [];
y = [];
% rng(10)
for i = 1:num_cities
    x = [x, rand()*10];
    y = [y, rand()*10];
    node_list = [node_list, Node(x(i), y(i), num_cities, i)];
    
end

perimeter = CirclePath(node_list,x,y);
% figure(1);
% perimeter(1).plotPathStart(x,y);
% for i = 1:numel(perimeter)
%     node_list = node_list(node_list~=perimeter(i));
% end

[added_node, flag, add_perimeter] = RecalculatePerimeter(node_list, perimeter);
if(add_perimeter)
    perimeter = [perimeter, added_node];
end
%     node_list = node_list(node_list~=added_node);

while(flag)
    [added_node, flag, add_perimeter] = RecalculatePerimeter(node_list, perimeter,added_node);
    if(add_perimeter)
        perimeter = [perimeter, added_node];
%         temp_flag = flag;
%         while(flag)
%             [added_node, flag, add_perimeter] = RecalculatePerimeter(perimeter, perimeter,added_node);
%         end
%         flag = temp_flag;
    end
%     if(added_node.id == 19)
%         break
%     end
%     node_list = node_list(node_list~=added_node);
%     if(i>=0 && i <= 160)
%        figure(i); 
%        perimeter(1).plotPathStart(x, y, 0);
%     end
%     hold off;
end
figure(1); 
%0 is no numbers
%1 is sequential ordering
%2 is memory location
perimeter(1).plotPathStart(x, y, 0);
hold off;
