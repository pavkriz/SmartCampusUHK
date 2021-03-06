/*
Copyright (c) 2014, Aalborg University
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the <organization> nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.smartcampus.android.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.smartcampus.indoormodel.AbsoluteLocation;
import com.smartcampus.indoormodel.graph.IGraph;
import com.smartcampus.indoormodel.graph.Vertex;


class DijkstraShortestPath {

	private Set<Vertex> settledNodes;
	private Set<Vertex> unSettledNodes;
	private Map<Vertex, Vertex> predecessors;
	private Map<Vertex, Integer> distance;
	
	/**
	 * This is a simplistic implementation of 
	 * Dijkstra's Algorithm - performance is not addressed! E.g. 
	 * Instead of using a Set for the unsettled nodes
	 * a PriorityQueue may be more appropriate. Should performance during
	 * determining directions be a problem, this might be the place to perform a variety
	 * of tweaks.
	 * 
	 * An instance of the class is initialised with a {@link Vertex} denoting the source from which
	 * the shortest path must be determined using information about the graph. 
	 * @param source Vertex denoting the source from which the shortest path must be found
	 * @param graph2 The graph in which to conduct Dijstra's algorithm
	 * @param floor Is used to confine the number of edges that will be taken into account when
	 * conducting the algorithm. When using Dijkstra's algorithm, we will only be searching on one
	 * floor at a time. If we want to extend our search to multiple floors, we will first find the shortest
	 * path to the nearest elevator/staircase, and then, in a subsequent process, use the elevator/staircase as
	 * source when finding the shortest path on another floor.
	 * @see @link Vertex
	 * @see Graph
	 * @see NavigationEngine
	 */
	public DijkstraShortestPath(Vertex source, IGraph graph2, int floor) {
		settledNodes = new HashSet<Vertex>();
		unSettledNodes = new HashSet<Vertex>();
		distance = new HashMap<Vertex, Integer>();
		predecessors = new HashMap<Vertex, Vertex>();
		distance.put(source, 0);
		unSettledNodes.add(source);
	}	
	
	private void findMinimalDistances(Vertex node) {
		List<Vertex> adjacentNodes = getNeighbors(node);
		for (Vertex target : adjacentNodes) {
			if (getShortestDistance(target) > getShortestDistance(node) + getDistance(node, target)) {
				distance.put(target, getShortestDistance(node) + getDistance(node, target));
				predecessors.put(target, node);
				unSettledNodes.add(target);
			}
		}
	}
		
	private int getDistance(Vertex origin, Vertex destination) {
    	AbsoluteLocation absOriginLoc = origin.getLocation().getAbsoluteLocation();
    	AbsoluteLocation absDestinationLoc = destination.getLocation().getAbsoluteLocation();
    	
    	int x1 = (int)(absOriginLoc.getLatitude() * 1E6),
    		x2 = (int)(absDestinationLoc.getLatitude() * 1E6),
    		y1 = (int)(absOriginLoc.getLongitude() * 1E6), 
			y2 = (int)(absDestinationLoc.getLongitude() * 1E6);
    	
    	
    	//Maybe casting to an integer may pose problems...
    	return (int)Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }
	
	private Vertex getMinimum(Set<Vertex> vertexes) {
		Vertex minimum = null;
		for (Vertex vertex : vertexes) {
			if (minimum == null) {
				minimum = vertex;
			} else {
				if (getShortestDistance(vertex) < getShortestDistance(minimum)) {
					minimum = vertex;
				}
			}
		}
		return minimum;
	}
	
    private List<Vertex> getNeighbors(Vertex node) {
		List<Vertex> neighbors = new ArrayList<Vertex>();
		/*for (Edge edge : edges) {
			if (edge.getOrigin().equals(node) && !isSettled(edge.getDestination()) && (int)edge.getDestination().getLocation().getAbsoluteLocation().getAltitude() == floor) {
				neighbors.add(edge.getDestination());
			}
			
		}*/
		
		for(Vertex neighbor : node.adjacentVertices()) {
			if(!isSettled(neighbor))
				neighbors.add(neighbor);
		}
		return neighbors;
	}

	
	private int getShortestDistance(Vertex destination) {
		Integer d = distance.get(destination);
		if (d == null) {
			return Integer.MAX_VALUE;
		} else {
			return d;
		}
	}
	
	/**
	 * Method used to retrieve the shortest path to a specified destination vertex.
	 * @param destinationVertex used to specify the destination for which the shortest path
	 * must be found.
	 * @return a linked list of vertices denoting the shortest path from destinationVertex provided
	 * as argument to this method.
	 * @see {@link Vertex}
	 * @see {@link LinkedList}
	 */
	public LinkedList<Vertex> getShortestPath(Vertex destinationVertex) {
		
		while (unSettledNodes.size() > 0) {
			Vertex node = getMinimum(unSettledNodes);
			settledNodes.add(node);
			unSettledNodes.remove(node);
			findMinimalDistances(node);
		}
		
		LinkedList<Vertex> path = new LinkedList<Vertex>();
		Vertex step = destinationVertex;
		
		// Check if a path exists
		if (predecessors.get(step) == null) {
			path.add(destinationVertex);
			return path;
		}
			
		path.add(step);
		while (predecessors.get(step) != null) {
			step = predecessors.get(step);
			path.add(step);
		}
		
		// Put it into the correct order
		Collections.reverse(path);
		return path;
		
	}
	
	private boolean isSettled(Vertex vertex) {
		return settledNodes.contains(vertex);
	}
	


}
