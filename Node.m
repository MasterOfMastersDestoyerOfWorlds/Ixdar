classdef Node
    %NODE Summary of this class goes here
    %   Detailed explanation goes here
    
    properties
        connected
        distance
        angle
        xcoord
        ycoord
        basevector
    end
    
    methods
        function nodeObj = Node(xcoord, ycoord)
            nodeObj.xcoord = xcoord;
            nodeObj.ycoord = ycoord;
        end
        function insertNode(nodeObj, node)
            if(nodeObj.connected ~= null)
                node.setConnected(nodeObj.connected);
                nodeObj.setConnected(node);
            end
        end
        function setConnected(nodeObj, node)
            nodeObj.distance = ((nodeObj.xcoord-node.xcoord)^2+ (nodeObj.ycoord-node.ycoord)^2)^(1/2);
            
            dot_product = ((nodeObj.xcoord-node.xcoord)*nodeObj.basevector(1) + (nodeObj.ycoord-node.ycoord)*nodeObj.basevector(2));
            magnitude_basevector = (nodeObj.basevector(1)^2 + nodeObj.basevector(2)^2)^(1/2);
            magnitude_vector = ((nodeObj.xcoord-node.xcoord)^2 + (nodeObj.ycoord-node.ycoord)^2)^(1/2);
            nodeObj.angle = (dot_product/(magnitude_basevector*magnitude_vector));
            
            nodeObj.connected = node;
        end
        function setBaseVector(nodeObj,basevector)
            nodeObj.basevector = basevector;
        end
    end
    
end

