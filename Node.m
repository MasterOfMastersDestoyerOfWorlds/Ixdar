classdef Node < matlab.mixin.SetGet
    %NODE Summary of this class goes here
    %   Detailed explanation goes here
    
    properties
        connected
        xcoord
        ycoord
        basevector
        vector
        basemag
        
    end
    
    methods

        function nodeObj = Node(xcoord, ycoord)
            nodeObj.xcoord = xcoord;
            nodeObj.ycoord = ycoord;
        end
        function insertNode(nodeObj, node)
            node.set('connected', nodeObj.get('connected'));
            nodeObj.set('connected', node);
        end
        function connected = get.connected(nodeObj)
            connected = nodeObj.connected;
        end
        function set.connected(nodeObj, node)
            nodeObj.vector = [nodeObj.xcoord - node.xcoord, nodeObj.ycoord - node.ycoord];
            node.basevector = [node.xcoord-nodeObj.xcoord,node.ycoord-nodeObj.ycoord];
            node.basemag = norm(node.basevector);
            nodeObj.connected = node;
        end
        function angle = angleToVector(nodeObj, node)
            dot_product = dot(nodeObj.basevector, [nodeObj.xcoord-node.xcoord,nodeObj.ycoord-node.ycoord]);
            angle = acos(dot_product/(nodeObj.basemag*norm([nodeObj.xcoord-node.xcoord,nodeObj.ycoord-node.ycoord])))*180/pi;
        end
        function flag = equals(nodeObj, node)
%             nodeObj.xcoord
%             nodeObj.ycoord
%             node.xcoord
%             node.ycoord
%             ''
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
        function plotPathStart(nodeObj, x, y)
            if(~isempty(nodeObj.connected))
                [xcoords, ycoords] = nodeObj.connected.plotPath(nodeObj);
            end
            plot([xcoords, nodeObj.xcoord], [ycoords,nodeObj.ycoord], '-s');
            hold on;
            plot(x,y,'o');
        end
        function [xcoords, ycoords] = plotPath(nodeObj, firstNode)
            if(ismember(nodeObj,firstNode) || isempty(nodeObj.connected))
                hold off;
                xcoords = [nodeObj.xcoord];
                ycoords = [nodeObj.ycoord];
            else
                firstNode = [firstNode,nodeObj];
                [x, y] = nodeObj.connected.plotPath(firstNode);
                xcoords = [x,nodeObj.xcoord];
                ycoords = [y,nodeObj.ycoord];
                
            end
        end
    end
    
end

