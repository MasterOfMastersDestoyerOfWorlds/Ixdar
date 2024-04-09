# Project Gordian


##  Preface: A Short Trip Through Time and Space

Let's say that we are aliens in some far off star system on the other side of the Milky Way and we want to visit Saturn's Moon Titan. With our current rocket technology it will take us a year to travel to Titan, but, given the way our rocket works, we can't course correct along the way. We need to point to where Titan will be a year from now and be accurate enough to hit the mark when we launch from our home planet. 

So how do we figure out where Titan will be? After all, every piece of matter in the universe pulls on each other through gravity. You could imagine listing every planetary body, star, and object with mass in the galaxy, along with their current positions, masses, and current trajectories in a giant computer and calculating how each one will pull on Titan and then if you knew the proper gravitational laws, you could calculate which way Titan would travel for the next timestep and how quickly. But, there is an obvious problem with this strategy, just like all of galaxy is pulling on Titan, it also pulls on itself. Very quickly the positions and trajectories of each celestial body would diverge from reality as each piece of the galaxy is pushed and pulled by every other piece. So you'd have to run the physics of the galaxy faster than the galaxy can produce it,updating each planet and star's position in tandem.

As you have been reading this, I'm sure you've been saying to yourself: "Only an idiot would do it this way!" and you'd be correct, what grade-schooler hasn't heard of an orbit? With the combined knowledge of Newton, Kepler and Einstein you could figure out Titan's future position to any arbitrary level of precision with a series of reference frames ascending from the lunar to the galactic. First you might figure out where Titan would be in its orbit relative to Saturn one year from now, then Saturn's location relative to the Sun, and then the Sun's location relative to its local group and etcetera, etcetera, until you are figuring out where our spiral arm would be relative to the galactic center. This calculation would still be pretty hard, but nowhere near as hard as figuring out how every celestial body in the galaxy is pulling on every other one!

Notice what we did: we started with an abstraction of a small moon orbiting a large planet, and as we built up the terrain of space we quickly ascended to the galactic scale. I would argue that much like the first attempt I described failed to build abstractions to help in the calculation, our current understanding of the Traveling Salesman Problem (TSP) also fails and in a similar way. The abstraction of "the orbit" has not yet been illuminated for TSP, and some think that such an abstraction is impossible, but I believe, if you look close enough, the truth is far simpler than many would have you believe.

## Chapter 1: Traveling Whatman?

A long time ago in the pre-internet, there used to be a whole army of american men who would go door to door and try selling useless junk to unsuspecting housewives (think vacuums and knives and other infomercial garbage,... but in your home!). These salesmen had a problem, they wanted to visit every house in their area to maximize the junk they sold, but they also wanted travel the least distance possible in order to minimize the amount of money they spent on gas. (You can already tell that given the framing of the problem, this problem has been befuddling computer scientists and mathematicians for a long time). 

"So what? I hasn't my Google Maps app solved this? Simply draw a straight line from A to B and try to follow that line on the roads as close as possible!" 

You'd be right if what we were talking about was point to point travel, an algorithm like A* or Dijkstra's has pretty much solved that question as optimally as we care to solve it. However, the important piece that makes TSP different is we MUST visit every house in our list, in the most optimal order. So instead of looking at all possible paths from A to B through any of the other points C to Z, we are instead looking at all possible paths from A to B to C ... Z in the most optimal ordering of those points. This explodes the problem space to exponential proportions.

### A more precise way of stating the problem would be: 

We have a graph G of N points: 

G = [ P<sub>1</sub> , P<sub>2</sub> , ... , P<sub>N-1</sub> , P<sub>N</sub> ] 

and we have a cost function C( P<sub>a</sub> , P<sub>b</sub> )  which is defined for all points in G. 

Find the best ordering O of the points in G such that when summing the cost function over the ordering, this sum C<sub>SUM</sub> is minimal when compared to all other possible orderings of G.

For example, if the best ordering, O, was [ P<sub>5</sub> , P<sub>3</sub> , ... , P<sub>7</sub> , P<sub>N</sub> ] , then C<sub>SUM</sub>(O) would be:

C<sub>SUM</sub>(O) = C( P<sub>N</sub> , P<sub>5</sub> ) + C( P<sub>5</sub> , P<sub>3</sub> ) + ... + C( P<sub>7</sub> , P<sub>N</sub> )

### This seems like a pretty abstract problem, why is it worth my time?

Well pretty much anywhere where there are networks and costs, a solution to TSP would be very useful. If we could solve this problem, it would reduce shipping costs, make drug discovery easier, increase internet bandwidth, and reduce the price of energy pretty much overnight just to name a few of the areas TSP touches. 

TSP also represents a whole class of problems that are equivalent, and we don't know if we can solve those problems quickly. This class is called NP or Non-Polynomial time problems and represents the boundary region between problems that we know how to solve quickly (Polynomial time problems (P)) and problems we know we cannot solve quickly (Exponential time problems (EXP)). So if you hear people say P = NP, they are saying that TSP is solvable quickly (by quickly I mean we could write an algorithm that builds the optimal path rather than checking all possible paths (there is a little nuance around Big(O) missing here, but this is a good start)). On the other hand if you hear people say P != NP, they are saying the only way to solve TSP is by checking all possible paths in the network, which is 2^N (N factorial? idr) paths. 

These classifications aren't too important, but I would be remiss if I didn't at least mention them because much of the discourse surrounding TSP is about proving these classifications and how they relate to each other rather than solving TSP. This is important because college students often only get instruction on how to sort problems into these classifications and get taught to not touch any problems in NP. This has largely shaped the programming communities perception of TSP as unsolvable. Despite this, we do have examples (I am thinking of the Fast Fourier Transform but may be mistaken) of problems that were previously thought to be in NP but later moved down to P with considerable insight and skill.

<b>Jaded Programmers Note</b>: if you ever hear these arguments in reference to a tough problem:

* "It has been X number of years and no one has solved the problem; its impossible!"
  
* "If we could solve problem A then computers could do unrelated problem B that, currently, only humans can do. So, problem A must be impossible because human brains are magic!" 

* "I have given up on the problem, so you should too, in order to save my ego!" 

* "This problem is too hard to be done classically, so Neural Networks and Big Data must be the only answer!" 

likely you should ignore that person as they are toxic to actually solving the problem. There are definitely unsolvable problems, but little time is wasted thinking on an unsolved problem that would help the world.

## Chapter 2: Mountains of Madness

So far I hope I have conveyed/answered the following:

* What is The Traveling Salesman Problem(TSP)?
* Why should I care about TSP?
* The solution to TSP, if one exists, lies in building up abstractions like a topographer builds terrain on a map

So the next question would be: <B>What terrain features exist on our map?</b>

Note that in this section I will be defining some of these features in non-traditional ways if you are coming from graph theory. This is so that our data-structures can more readily fit the problem at hand. If there is overlap, I will redefine these terms so that we can distinguish them from their more basic versions you would have seen in algorithms like BFS, DFS and the like.

### Segment
  
  A Segment (also known as an Edge in graph theory), is a connection between two points and a distance provided  by the cost function.

Segment( P<sub>1</sub> , P<sub>2</sub> ) = struct{
    
endpoint1 = P<sub>1</sub> 

endpoint2 = P<sub>2</sub>

distance = C( P<sub>1</sub> , P<sub>2</sub> )

}

### Point
Our smallest feature is a Point (also know as a Vertex in graph theory), which is defined as a list of Segments all of the segments in the graph that connect to P<sub>1</sub>: 

Point(P<sub>1</sub>) = struct{
    
sortedSegments = [Segment( P<sub>1</sub> , P<sub>2</sub> ) , Segment( P<sub>1</sub> , P<sub>3</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> )]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}

Many of the proceeding algorithms will rely on the fact that sortedSegments is sorted, so  sort it at construction.

The "matches" will be our current best guess of what two points should surround P<sub>1</sub> in our final ordering. Right now they will just be pointers to other points, but as we add more terrain features we will need to add more supporting data to prevent recalculation of what the best match is.

### Wormholes

A Wormhole is a point that has zero distance to two Points of your choosing and maximal distance to all other Points in the set. 

So if we have Points 1 and 2 in the problem set and add Wormhole W "between" 1 and 2, then the new correct ordering would include:

1 <-> W <-> 2

This type of point could arise naturally in any problem set, but here we are calling it out for a specific purpose: testing. These Wormhole points will help us in three scenarios:

* We want to change a problem set in M dimensions (where M < N and N is the size of the problem set) into an N-dimensional one.
* We want to perturb a problem set in the smallest way possible, without changing the final ordering.
* We want to break a large problem down into many subsets (of arbitrary size) while still knowing the correct answer

We get the first scenario for free just by adding the Wormhole. (Not sure if this is true in general, but many geometrical arguments break down with the addition of Wormholes)

Second, if we add a Wormhole between two points that we know are in the correct ordering, then we can change the ordering of our Sorted Segments (and how points will match with each other) without changing the final correct ordering (aside from the wormhole being added). This is useful in testing as it is the smallest change we can make to a problem set and can illuminate potential problems with our algorithm. 

Finally we can take any ordered subset in a correct ordering and add a Wormhole between the two endpoints of the subset. This will allow us to solve the subset in the same way we would have solved the original set. This is the  equivalent of saying we have points A and B with some set P (where P is the points in the original solution set that lie between A and B), find the most optimal path between A and B that also visits all of the points in P. 

W <-> A <-> [ P<sub>1</sub> , P<sub>2</sub>, ... , P<sub>N</sub> ] <-> B <-> W

This is useful as it allows us to take a large problem and break it down into many sub-problems, greatly expanding the amount of data we can test our algorithm on. Before you get an intuition for what groupings are "correct", it is useful to be able to strip out the layers of abstraction to work on smaller ones.


    As a small aside, you can scale up many incorrect algorithms quickly and miss the fact that they are incorrect if you skip this step, especially if your data set has a bias towards points in the plane or of a certain dimensionality. Wormholes keep us honest.

Below are some links to help you understand a bit more of the math behind what I'm talking about:

General TSP to Metric TSP Reduction:

- https://cstheory.stackexchange.com/questions/12885/guidelines-to-reduce-general-tsp-to-triangle-tsp


TSP with two predetermined endpoints:

- https://stackoverflow.com/questions/36086406/traveling-salesman-tsp-with-set-start-and-end-point

### Bigger Structures?

Ok, so now we have outlined most of the basic structures that anyone who has looked problems in graph theory likely would have expected. I don't expect you to understand why we have some of those "extra" variables yet, but as we start to look at some bigger structures, it will become apparent.

So what's next? Where are the larger structures that I called out earlier that will build the terrain of the problem?

Well before we get to that , lets look at some examples to build some intuition:

    11  [Segment[11 : 12], Segment[11 : 13], Segment[11 : 10], Segment[9 : 11], Segment[11 : 14],  ...]

    12  [Segment[11 : 12], Segment[13 : 12], Segment[9 : 12], Segment[10 : 12], Segment[14 : 12], ...]

    13  [Segment[13 : 12], Segment[11 : 13], Segment[14 : 13], Segment[10 : 13], Segment[13 : 15], Segment[9 : 13], ...]

The above lists of sorted Segments are from the Djbouti_38 problem set from the University of Waterloo. In these examples I will denote each point by its final position in the correct ordering. So Point 1 would have neighbors 0 and 2 in the final correct ordering. 

A Segment as a reminder is a relationship between two points and the distance between those points. This definition allows us to sort the segments without losing the relationship that they represent. 

You can see that in the above example if each Point "got it's way" and matched with the two best other points in it's list, then we would have the following set of relationships:

11 <-> 12

12 <-> 13

13 <-> 11

Instantly we see a problem with the naive approach, we have created a loop, so if we wanted to connect to the other 35 points in  the Djibouti dataset we couldn't! 

Before we declare defeat, let's try and examine what this loop is telling us:

Well, in a perfect world we would be able to make a loop of size 38 just by looking at each Point's favorite two potential matches. 

From this observation we can also observe that if we tried to solve the subset S = [ P<sub>11</sub> , P<sub>12</sub> , P<sub>13</sub> ], that 11 <-> 12 <-> 13 would be the correct ordering of S.

At a more general level this loop is telling us that the subset/grouping S wants to connect with itself more than any other point in the graph. So to "resolve" this loop (i.e. find out which segment we should cut) we must find the two Points that want to match with the points in our loop.

    One should also observe that there is no limit on the size that such a loop could be except that it must have > 2 points

Ok I think we're ready for our first larger structure

### Knot

Knot(P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>) = struct{
    
sortedSegments = [Segment( P<sub>1</sub> , P<sub>2</sub> ) , Segment( P<sub>1</sub> , P<sub>3</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> )]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}




## Chapter 3: Mapping the Knot

## Chapter 4: The Sword of Iskandar

## Links:

Look at Section 4 Minimum Bounding Circle By Megiddo: 

- https://epubs.siam.org/doi/pdf/10.1137/0212052



http://www.math.uwaterloo.ca/tsp/world/countries.html#LU
