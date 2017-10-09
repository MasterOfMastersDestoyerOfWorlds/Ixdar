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
        function nodeObj = Node(xcoord, ycoord, basevector)
            nodeObj.xcoord = xcoord;
            nodeObj.ycoord = ycoord;
            nodeObj.connected = [];
            nodeObj.distance = [];
            nodeObj.angle = [];
            
        end
        function setConnected(node)
            nodeObj.distance = [nodeObj.distance, (node.xcoord^2+ node.ycoord^2)^(1/2)]
            
            dot_product = ((nodeObj.xcoord-node.xcoord)*nodeObj.basevector(1) + (nodeObj.ycoord-node.ycoord)*nodeObj.basevector(2));
            magnitude_basevector = (nodeObj.basevector(1)^2 + nodeObj.basevector(2)^2)^(1/2);
            magnitude_vector = ((nodeObj.xcoord-node.xcoord)^2 + (nodeObj.ycoord-node.ycoord)^2)^(1/2);
            nodeObj.angle = [angle, (dot_product/(magnitude_basevector*magnitude_vector))];
            
            nodeObj.connected = [nodeObj.connected, node];
        end
        function r = getConnected()
            r = nodeObj.connected;
        end
            function setBaseVector(basevector)
            end
    end
    
end

