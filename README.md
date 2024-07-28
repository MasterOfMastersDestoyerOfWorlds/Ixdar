<img src="decal.svg" alt="Decal" width="50%" style="max-height: 400px; display: block;margin-left: auto;margin-right: auto;"/>

## Contents

[Preface: A Short Trip Through Time and Space](#preface)

[Chapter 1: Traveling Whatman?](#chapter-1)

[Chapter 2: Mountains of Madness](#chapter-2)

[Chapter 3: Mapping the Gordian Knot](#chapter-3)

[Chapter 4: The Sword of Iskandar](#chapter-4)

[Links and References](#links)

## Preface

## A Short Trip Through Time and Space

Let's say that we are aliens in some far off star system on the other side of the Milky Way and we want to visit Saturn's Moon Titan. With our current rocket technology it will take us a year to travel to Titan, but, given the way our rocket works, we can't course correct along the way. We need to point to where Titan will be a year from now and be accurate enough to hit the mark when we launch from our home planet.

So how do we figure out where Titan will be? After all, every piece of matter in the universe pulls on each other through gravity. You could imagine listing every planetary body, star, and object with mass in the galaxy, along with their current positions, masses, and current trajectories in a giant computer and calculating how each one will pull on Titan and then if you knew the proper gravitational laws, you could calculate which way Titan would travel for the next time-step and how quickly. But, there is an obvious problem with this strategy, just like all of galaxy is pulling on Titan, it also pulls on itself. Very quickly the positions and trajectories of each celestial body would diverge from reality as each piece of the galaxy is pushed and pulled by every other piece. So you'd have to run the physics of the galaxy faster than the galaxy can produce it, updating each planet and star's position in tandem.

As you have been reading this, I'm sure you've been saying to yourself: "Only an idiot would do it this way!" and you'd be correct, what grade-schooler hasn't heard of an orbit? With the combined knowledge of Newton, Kepler and Einstein you could figure out Titan's future position to any arbitrary level of precision with a series of reference frames ascending from the lunar to the galactic. First you might figure out where Titan would be in its orbit relative to Saturn one year from now, then Saturn's location relative to the Sun, and then the Sun's location relative to its local group and etcetera, etcetera, until you are figuring out where our spiral arm would be relative to the galactic center. This calculation would still be pretty hard, but nowhere near as hard as figuring out how every celestial body in the galaxy is pulling on every other one!

Notice what we did: we started with an abstraction of a small moon orbiting a large planet, and as we built up the terrain of space we quickly ascended to the galactic scale. I would argue that much like the first attempt I described failed to build abstractions to help in the calculation, our current understanding of the Traveling Salesman Problem (TSP) also fails and in a similar way. The abstraction of "the orbit" has not yet been illuminated for TSP, and some think that such an abstraction is impossible, but I believe, if you look close enough, the truth is far simpler than many would have you believe.

### On the Nature of Abstraction

What is a natural abstraction versus an unnatural one? Are some abstractions better than others?  
Another thought experiment, lets say that we are an observer of the Earth in space, what divisions or abstractions could we make of the Earth?

Well there are a lot of atoms in the earth so we could divide the Earth into a large number of sets, at least one for each atom and all combinations of atoms, but this is not a very satisfying answer, is definitely not a useful one, and is clearly not what humans do to make sense of the world.

Another idea might be to divide the world into what we can see from our left eye and what we can see from our right eye (pretend they don't overlap), Color everything on the left-side red and on the right-side blue. This is certainly a valid abstraction into red-side Earth and blue-side Earth, but let us consider the border region between left Earth and right Earth. All of the objects on this border region are highly interacting, the ocean water would flow from side to side, Animals would move across this boundary and magma in the core would rotate though this boundary, So overtime you'd quickly get all of the colors mixed up, and the "purple" region would gradually spread to the entire globe as atoms from blue Earth are carried onto the red side and vis versa.

This is what I'd like to call an "unnatural" abstraction, i.e. an abstraction that correctly divides the world into distinct sets that are either in the abstraction or not, but where the elements of the distinct sets are highly interacting on some metric. A better description might be that the elements of the set interact more (or equally as much) with elements outside the set than the ones inside it.

a "natural" abstraction is then one in which the elements of the sets described interact more with the other elements inside the abstraction's set than with objects outside the set.

The next question is, if we have an idea of a unnatural abstraction, and its opposite is a natural abstraction, are there any natural abstractions that exist? For all time and space? probably not. There are certainly infinitely many unnatural abstractions that could be made while there are far fewer natural ones, and the natural ones that do exist are "fragile" in that you have to carefully assign what is in the set versus out of the set and it is easy to make a natural abstraction into an unnatural one by improperly assigning what is described by the abstraction.

An example of this fragility might be the living cell. The cell is a more natural abstractions than left/right earth, but it still has parts of its operations that are unnatural. Given that the cell has inputs and outputs in the form of Oxygen/ATP, Carbon Dioxide/Proteins, etc., would those inputs and outputs be rightly called part of the cell? I don't think that they could, but it would also be impossible to describe a cell without them. So what would be the fully natural abstraction of the cell? Likely it is found in following our definition to the letter, all of the parts of the cell that interact more closely with each other than things outside the cell, so this would exclude materials like inputs and outputs, but would include things like the cell wall, the nucleus and proteins that stay within the cell walls.

If we return to our observer in space, there is exactly one natural abstraction that would matter gravitationally to the observer, that being the entire Earth including the crust, mantle, core, atmosphere, etc. If we move our observer toward the Moon, the Moon gradually becomes the dominant natural abstraction and there is some tipping point where, if we allowed our observer to be effected by gravity, that he would be sucked into the Moon instead of the Earth. If we move our observer even farther out beyond the Moon, gradually instead of being pulled by the Moon specifically, the observer is now pulled by the Earth-Moon planetary system.

The question of when a natural abstraction arises is the following: At what distance is the gravity(or any metric) produced by the atom(or some individual datum), unimportant compared to the Earth's gravity(the subset that the datum is a member of). In the language of Graphs: What Cycles if any can be abstracted into a Virtual Point whose members want to match with each other more than Points not in the Cycle.

I'm sure that I've made some mistakes in this description as I am not a physicist, but for our purposes, this simplified gravitational model will suffice as an analogy for natural and unnatural abstractions as they arise in fully connected graphs. Graphs do not follow gradients like gravitational fields do, but instead simplify those gradients of understanding into discrete numbers. This is both helpful in that there is much less data to work with and unhelpful in that it is much less clear at what level we can make an natural abstraction.

For planetary bodies it is, if not clear, fuzzily clear, that the natural abstraction occurs when you can no longer tell which of the internal bodies (wether those internal bodies be atoms, asteroids, or moons, etc.) is pulling your observer, this is often described as the gravity well and depicted as a bowling ball on a piece of fabric, where the fabric is no longer deformed by the bowling ball is the limit of the natural abstraction. One should note that in the real world there is no place where the deformation stops, it is just reduced very close to zero following the inverse square law. If you have multiple bowling balls on your fabric, then the natural abstraction starts where an observer could not tell without running the simulation, into which bowling balls well it will fall into. In fully connected graphs, since all points are connected to all points, there is no place were you can not consider any of the other points in the set, in order to work around this limitation, we will have to consider what the natural abstraction for a fully connected graph is.

### Internal States of Natural Abstractions

If we think back to the Earth and Moon as our perfect natural abstraction, we see that the Moon (and even the Sun, Jupiter and most of the planets at various times) cause tides on the earths oceans. If you think of an ideal spherical liquid earth, then it is obvious that the tides would also extend to the very core of the Planet. And indeed it has been noted by petroleum engineers and miners working in the earths crust that they have to deal with larger pressures from the surrounding rock twice a day mirroring the tides. But, If instead of all of the Earth being made of Water, it was made of different liquids or solids with different densities and tensile strengths, then at each density level we'd need to calculate a different tidal force and bulge made by the tide. Another note: it is believed that the tidal force on Jupiter's volcanic moon Io is caused by the fluctuation in and addition of tidal stress from Jupiter and the nearby moons of Europa, Ganymede and Callisto causing immense amounts of friction in the interior and surface of Io.

<img src="img\tides.png" alt="MatchTwiceAndStitch" width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>

<p style="text-align:center"> Stress forces on the surface of a planetary body due to the tides</p>

Notice that the tides exist both on the side facing the satellite as well as the side facing away from the satellite. This second bulge on the opposite side of the earth is not due the rotation of the planetary body as many people believe, but is instead due to the fact that different parts of the earth are falling toward the moon at different rates according to the inverse square of the distance. Note that the tidal stress caused by this difference in falling rates goes like the inverse cube of the mass of the satellite and this is why the Moon's tide is significantly stronger than the Sun's.

If we think of the Diagram above instead of in a dual planetary system, but in a planetary system with 3 or more bodies, then for each planetary body, other than the one we are examining, we could make a similar diagram of the stress forces on our main planet. Then to find the total tidal force over the surface we could sum these diagrams. I expect that to someone who did not know how the sub diagrams were generated, the completed diagram might look very similar to random except for the standout of the nearest body's tidal being the most obvious.

It should also be noted that the tidal bulges come in pairs. So when looking for a natural abstraction in the complete graph we should seriously think if the connections between those abstractions might have a similar rule to follow around the counting of entrances and exits from the abstraction.

### In Search of the Natural Abstraction for a Graph

In non-fully connected graphs there are four abstractions with varying degrees of naturalness depending on their construction, first is the Node or Point, next is the Edge or Path (or a collection of ordered edges and points with a beginning and ending that are not cycles), third is the Cluster (or collection of points and edges that are connected but have no explicit beginning or ending), and finally is the Cycle (or any Path that returns to its starting point). In a fully connected graph, since every Point connects to every other Point, any Path extended far enough in the graph will eventually become a cycle and the number of valid cycles in the graph grows exponentially with the size of the set, meaning that even just to enumerate all of the cycles is a Herculean task. But since our goal in the Traveling Salesmen Problem is to find the shortest Cycle that visits all of the points in the graph, a cycle will likely be part of the natural abstraction we use to solve the problem. What abstraction could we make that would be a natural -- and useful -- abstraction in the fully connected graph?

Well, as mentioned above, any cycle in the graph that contains all points would be a natural abstraction, since all of the elements want to interact with elements in the set more than ones outside, since there are none outside. This is a natural abstraction but not a useful one since even checking all of the possible cycles would take a long time.

Another possible natural abstraction would be convex hulls. In the plane convex hulls are cycles that enclose the entire set, and in the plane this works rather well, you can recursively take the convex hull and exclude the hull points until you have some number m nested convex hulls (think like the rings of a tree) where m is less than n. The problem arises with convex hulls in increasing dimensions and that they can be unnatural abstractions. In higher dimensions the convex hull is no longer a cycle, but faces and facets, and as the number of dimensions approaches the size of the set, the convex hull becomes the set, so not very useful.

Even in the plane the convex hull may be an unnatural abstraction. think about a triangle with one point(labeled A) inside that is nearer to one of the triangle points (labeled B) than the other two. Our hulling algorithm would find the hulls of the containing triangle, and the point A, but the point B is more closely interacting with A than any other point so it is unnatural. This would be the equivalent to saying that the tips of spiral arms of the galaxy have more influence on each other than say the galactic core does. Obviously this does not make sense since some of the stars in the tips would have to travel through the galactic core in order to interact with stars in the tips on the other side of the galaxy (not impossible in N dim space, but not known in our universe).

In the Match Twice and Stitch paper, an algorithm for finding m distinct cycles is proposed. Two problems arise with the proposed algorithm, the first problem is that it is not guaranteed that you can form m distinct cycles by the method outlined. These points outside the cycles means that there is no guarantee that the algorithm can be run on any set. And the second problem arises at the stitching phase of the algorithm (Stitching here means greedily alternating between the points along the boundary of 2 cycles, repeat until there is only one cycle).

<img src="img\MatchTwiceAndStitch.png" alt="MatchTwiceAndStitch" width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Example of stitching two cycles together from the match and stitch paper</p>

How can you know that when you stitch two cycles together, that they will form the correct minimal cycle of the two sets? Even in the example seen above this is unlikely to be the correct cycle and to actually check the correct cycle under this scheme you'd need to check 2^k possible cycles where k is the number of elements in the union of the two combining cycles. I think the causal reason why this is hard is that this is an unnatural abstraction, if you think about the case where you have two cycles that are circles in the plane and their boundaries are close to each other, the cycles formed will be two circles, but along the boundary between the two midpoints of the circles, those points will want to match more closely with points from the opposite circle than with all of the points in their own circle.

The problem with many similar algorithms (greedy match, any colony, minimum spanning tree transformation, etc.) is that you can know that an answer is within some bound of the correct one, but there is no framework for causal reasoning on why a given solution is wrong, we simply say that the algorithm didn't work for this set and then use some form of k-opt segment swap to minimize the answer (look ma it is getting shorter!). This is the general problem with using heuristics  rather than looking for root causes, iteration is at the heart of scientific thought and if you have no theoretical framework to wrap around a problem, then there is no place to iterate you theory when you find an exception to it. It is the difference between engineering and science. *WARNING BASELESS RANT INCOMING* I think for all of its sins, the greatest one in Computer Science is that we often rely too much on engineering efforts rather than the scientific and mathematical efforts of our fields namesake. We as a field seek short term gain in fitness functions (wether is path length in TSP research or benchmark success in Machine Learning) instead of long term understanding of deeper truths. If we continually throw aside understanding at the feet of greater compute power then we do not deserve to be called scientists. The opposite tendency, to only rely on proofs is simarly flawed and I find many of the claims of Complexity theory to fall into this categorey, never really saying anything of use and building no intuition(which is inherently unprovable), except what is directly derrivable from previously shown results. If an answer to PvsNP exists, I expect that it will be a leap of logic unlike what we have seen before not a neatly built bridge of small results from PvsNp to P=NP or P!=NP. Since most Complexity theorists are trying to prove P!=NP because of a series of reductio ad absurdum (which as Schrodinger found out in the quantum realm, the world is often more absurd than the limits of imagination), There is a dirth of academic research trying to use the structure of relationships to map out what NP problems can be solved in P. As far as I can tell, the notion that Optimal tours might behave and change in the same ways that a precision watch would change to a replacement of gears, is lost on most people. We as a field either jump to statistical arguments or quasi-mathematical arguments neither of which seem to build actual understandable models of what is going on in a perfect circuit. (I say quasi here since very little of complexity theory deals with the actual nature of programs, geometrical structures and the like, instead falling back to the most broad of arguments, making it closer to the philosophy of old than the mathematics and science of the early 20th century, [GTC](http://ramakrishnadas.cs.uchicago.edu/gctcacm.pdf) seems promising on the P!=NP front and I need to read more about it).

Why not clusters? There is a lot of literature on how to find clusters in graphs and they sort of have the property we are looking for that an ideal k-clustering of a graph is a natural abstraction. Two problems arise when considering clusters, first is how do we choose k, i.e. how do we know how many clusters there are in the graph? This is not a trivial problem and in general we can show that there is no correct answer by the simple fact that if we choose k to be n then we have n natural abstractions one for each point adn if we choose k to be 1 then we have one natural abstraction in the whole set, both of these are perfectly valid and easy to compute natural abstracts, but non-useful. Ok so maybe instead of using something like k means we use a hierarchical clustering algorithm like the [nearest-neighbor chain algorithm](https://en.wikipedia.org/wiki/Nearest-neighbor_chain_algorithm). This method would work better since we don't have to divine the number of clusters in the graph, but there is still a pretty serious problem with an approach like this. Since clusters have no ordering except points are either in the cluster or out of it, how would we form a cycle out of a cluster? If our smallest cluster in the hierarchy is some size m where m < n, then we'd still have 2^m possible cycles to choose from in order to find the optimal, and if we add up all of our k smallest clusters where k clusters consume the entire set, then we'd have 2^m_1 + 2^m_2 + ... + 2^m_k cycles to choose from. one we had all of these cycles, were is still no guarantee that we could combine them in any easy way so we'd have to also do a pairwise 2^m_a + 2^m_b combination step (where a and ba are two of the cycles found in the previous step) to get the final correct cycle. So is there any clustering method that could lead use to a natural abstraction that is easy to find, and useful in solving our problem? If we want to have a good natural abstraction, it would be useful if the abstraction had the correct cycle for the subset be incidental to the formation of the abstraction, much like in the plane the correct cycle of a convex hull subset is found simply by finding the convex hull. I don't know of any clustering algorithm that has that property (doesn't mean one doesn't exist) since clusters are mainly concerned with membership rather than ordering, so let's focus our efforts elsewhere.

Another idea might be to make an ear decomposition (See Figure Below) using the matchings of our graph as the "edges" of the decomposition. Once we have some an ear decomposition we could then cut the resulting decomposition into a single path. Since our Graph is fully connected we'd also have to ensure that we only assign two edges per vertex greedily once our seed cycle is found in order to not multiple form cycles in the resulting path.

<img src="img\EarDecomposition.png" alt="EarDecomposition" width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Example of an Ear decomposition of a partially connected graph </p>

I think this is moving in the right direction, since we have a nested structure to work with, we are moving toward a natural abstraction, but for similar reasoning to why convex hulling is an unnatural abstraction, this method would also be unnatural. This can be seen by the simple fact that there is no unique ear decomposition and any cycle we choose as the seed cycle (e.g. G_1 in the above Figure) would be valid. In many graphs in the waterloo dataset there are multiple cycles of matchings that could serve as the seed cycle. This necessarily means that you'd end up splitting one of the cycles of matchings into two ears breaking the naturalness of the abstraction.If we could guarantee that our matching ear decomposition would only stack more ears on top of each other, breaking no matching cycles, then this would be a promising technique and investigating the proper cutting algorithm would be the next step, but since this property is broken even by small graphs, this technique warrants no further investigation.

### The Gordian Knot

Okay, What could the natural abstraction be? Well I think it has the following form: a Cycle of Points and Virtual Points (i.e. a subset of the graph that has already been abstracted), in which every Virtual Point in the Cycle wants most to match most with its two neighbors in the Cycle. This Cycle can have either two matches that want to go to the outside or none. Once such a cycle is found we can abstract it away as a Virtual Point and add it to our set, removing any points from the set that are contained within the cycle. We will call this natural abstraction a Knot.

A Way to think about this structure is that it is similar to the ear matching decomposition, but generalizes it to multiple seed cycles.

Even this is not quite a true description of the structure, since what we are really trying to do is: Once we form a cycle we need to also make this cycle into a point for use in finding new cycles. The recursive nature of the data structure is necessary to ensure that it is a natural abstraction and the cyclic nature of the abstraction ensures that , at least in the base case, we will be able to find the correct tsp tour with ease. Finally the constraint that there should only be one in and one out (meaning that the Virtual Point is a member of a super cycle) ensures that we will be able to merge tsp cycles with relative but unfortunately recursive ease. Why does merging cycles require recursion? Well I'm not sure that recursion  is the right tool, but it is the best one I have. This is quite similar to the situation we find ourselves in with graphs and cycles, if we have any recursive structure,  find and building up the correct path will be at least an n^2 endeavour and at most an n^2 + (n-1)^2 + ... + 1^2 ~= n^3 operation. Why n^3? because, in the worst case, we have two externals points that we need to check every point in the cycle against to find the right cut, and when we find the right cut we cannot simply match the cutpoints together. Matching the cutpoints together is useful until a point, but once the Knot has more than one cyclic layer, it is not usually the correct answer. In a pure cycle it is the correct answer.

Does a Knot necessarily exist in a fully connected graph embedded in Euclidean Space?

if we have points p_1, p_2, ..., p_6
and each set of odd even pairs WOLG match to each other but not to their next best matches, e.g.
So what possible matching could we have?

p_6 <- p_1 <-> p_2 -> p_3
p_5 <- p_4 <-> p_3 -> p_6
p_1 <- p_5 <-> p_6 -> p_4
implies that:
S[1,6] > S[6,4] > S[4,5] > S[1,5] > S[1,6]
which breaks the triangle inequality

p_3 <- p_1 <-> p_2 -> p_3
p_5 <- p_4 <-> p_3 -> p_6
p_1 <- p_5 <-> p_6 -> p_4

implies that:
S[1,3] > S[3,6] > S[6,4] > S[4,5] > S[1,5] > S[1,3]
which breaks the triangle inequality

p_4 <- p_1 <-> p_2 -> p_3
p_5 <- p_4 <-> p_3 -> p_6
p_1 <- p_5 <-> p_6 -> p_4

implies that:
S[1,4] > S[4,5] > S[5,1] > S[1,4]
which breaks the triangle inequality.

p_6 <- p_1 <-> p_2 -> p_3
p_2 <- p_4 <-> p_3 -> p_6
p_1 <- p_5 <-> p_6 -> p_4

S[2,3] > S[3,6] > S[6,4] > S[4,2] > S[2,3]

p_6 <- p_1 <-> p_2 -> p_5
p_2 <- p_4 <-> p_3 -> p_6
p_1 <- p_5 <-> p_6 -> p_4

S[2,5] > S[5,1] > S[6,1] > S[4,6] > S[2,4] > S[2,5]

p_6 <- p_1 <-> p_2 -> p_6
p_2 <- p_4 <-> p_3 -> p_6
p_1 <- p_5 <-> p_6 -> p_4

S[2,6] > S[6,4] > S[4,2] > S[2,6]

...
etc.

We can see from the above that if there is no terminating match where one of the endpoints matches to another pair successfully, then we must break the triangle inequality it's not the triangle inequality, maybe that too, but we create a contradiction of increasing numbers such that a > b > c > a .

So we've shown we need at least one extra full match to resolve this, does that mean we must have a Knot?

## Chapter 1

## Traveling Whatman?

A long time ago in the pre-internet, there used to be a whole army of american men who would go door to door and try selling useless junk to unsuspecting housewives (think vacuums and knives and other infomercial garbage,... but in your home!). These salesmen had a problem, they wanted to visit every house in their area to maximize the junk they sold, but they also wanted travel the least distance possible in order to minimize the amount of money they spent on gas. (You can already tell that given the framing of the problem, this problem has been befuddling computer scientists and mathematicians for a long time).

"So what? Hasn't my Google Maps app solved this? Simply draw a straight line from A to B and try to follow that line on the roads as close as possible!"

You'd be right if what we were talking about was point to point travel, an algorithm like A* or Dijkstra's has pretty much solved that question. However, the important piece that makes TSP different is we MUST visit every house in our list, in the most optimal order. So instead of looking at all possible paths from A to B through any of the other points C to Z, we are instead looking at all possible paths from A to B to C ... Z in the most optimal ordering of those points. This explodes the problem space to exponential proportions.

### A more precise way of stating the problem would be

We have a graph <b>G</b> with a total of N points:

<b>G = [ P<sub>1</sub> , P<sub>2</sub> , ... , P<sub>N-1</sub> , P<sub>N</sub> ]</b>

and we have a cost function <b>C( P<sub>a</sub> , P<sub>b</sub> )</b>  which is defined for all points in G.

Find the best ordering <b>O</b> of the points in <b>G</b> such that when summing the cost function over the ordering, this sum <b>C<sub>SUM</sub></b> is minimal when compared to all other possible orderings of <b>G</b>.

For example, if the best ordering, <b>O</b>, was <b>[ P<sub>5</sub> , P<sub>3</sub> , ... , P<sub>7</sub> , P<sub>N</sub> ]</b> , then <b>C<sub>SUM</sub>(O)</b> would be:

<b>C<sub>SUM</sub>(O) = C( P<sub>N</sub> , P<sub>5</sub> ) + C( P<sub>5</sub> , P<sub>3</sub> ) + ... + C( P<sub>7</sub> , P<sub>N</sub> )</b>

### This seems like a pretty abstract problem, why is it worth my time?

Well pretty much anywhere where there are networks and costs, a solution to TSP would be very useful. If we could solve this problem, it would reduce shipping costs, make drug discovery easier, increase internet bandwidth, and reduce the price of energy pretty much overnight just to name a few of the areas TSP touches.

TSP also represents a whole class of problems that are equivalent, and we don't know if we can solve those problems quickly. This class is called <b>NP</b> or Non-Polynomial time problems and represents the boundary region between problems that we know how to solve quickly <b>(Polynomial time problems (P))</b> and problems we know we cannot solve quickly <b>(Exponential time problems (EXP))</b>. So if you hear people say Z<b>P = NP</b>, they are saying that TSP is solvable quickly (by quickly I mean we could write an algorithm that builds the optimal path rather than checking all possible paths (there is a little nuance around <b>Big(O)</b> missing here, but this is a good start)). On the other hand if you hear people say <b>P != NP</b>, they are saying the only way to solve TSP is by checking all possible paths in the network, which is 2^N (N factorial? idr) paths.

These classifications aren't too important, but I would be remiss if I didn't at least mention them because much of the discourse surrounding TSP is about proving these classifications and how they relate to each other rather than solving TSP. This is important because college students often only get instruction on how to sort problems into these classifications and get taught to not touch any problems in <b>NP</b>. This has largely shaped the programming communities perception of TSP as unsolvable, or only solvable with super-computers. Despite this, we do have examples (I am thinking of the Fast Fourier Transform, but may be mistaken) of problems that were previously thought to be in <b>NP</b> but later moved down to <b>P</b> with considerable insight and skill.

    Jaded Programmers Note: if you ever hear these arguments in reference to a tough problem:

    * "It has been X number of years and no one has solved the problem; its impossible!"
      
    * "If we could solve problem A then computers could do unrelated problem B that, currently, only humans can do. So, problem A must be impossible because human brains are magic!" 

    * "I have given up on the problem, so you should too, in order to save my ego!" 

    * "This problem is too hard to be done classically, so Neural Networks and Big Data must be the only answer!" 

    likely you should ignore that person as they are toxic to actually solving the problem. There are definitely unsolvable problems, but little time is wasted thinking on an unsolved problem that would help the world.

### What is the state of the art?

Algorithms for solving TSP (using the term solving loosely here) fall into three broad categories: <b>Exact</b>, <b>Constructive</b>, and <b>Iterative</b>, or some combination of those three.

#### Exact

Exact algorithms are exponential or factorial in nature and if you believe that <b>P != NP</b> then you also believe that these are the only category of algorithms that can solve the problem outright. They usually involve some level of brute forcing, so checking every possible path and looking for the best solution, and or branch and bounding, which seeks to eliminate large portions of the search space by ignoring all paths we already have a shorter path than. In <b>2D-TSP</b> (only looking at point sets that lie entirely in the plane) it has long been known that a correct ordering will have no self-intersections (the path crossing it self), so that could also be used as a path elimination condition.

Obviously since we wish to prove <b>P = NP</b> we can safely ignore this category of algorithms, at least until quantum computers start working at scale.

#### Constructive

A construction is the idea of making a path directly and submitting it as your answer. The easiest construction to understand would be the greedy/nearest neighbor algorithm which simply starts at a random point and then goes to the next closest point and repeats this process until we have visited all of the points. Any non-brute force algorithm will start with a construction at the very least. My goal in this project is to create a construction that matches the output of the exact algorithms.

The closest construction to the "correct construction" that I have found (i.e. the one I think has the most promise) is the <b>Match Twice and Stich Algorithm</b> (MTS) ([Kahng, 2004](#links)). MTS constructs a series of disjoint cycles and stiches them together. The next question, is why does this algorithm fail? I believe that it correctly recognizes that loops within the greedy matches of the graph are an important part of structure of the problem, but since they only have explored disjoint cycles they have missed the recursive element of the problem. This will be explored more in [Chapter 2](#chapter-2) as we start to identify the structures of TSP graphs.

#### Iterative

Traditionally, constructive algorithms have been seen as worse than iterative algorithms because if you take any failed construction (i.e. a construction that does not produce the correct ordering) you can always improve the construction by using an iterative approach (<b>swapping segments of the path with each other until you reach some local minima</b>). This usually results in a sub-optimal minima except for in very small problems, but it gives the illusion of improvement and understanding.

A common problem I have encountered in writing algorithms like these (k-opt, lowest insertion from convex hull), is that they often work quite well up to a point, but when you try and make the leap to the next harder problem you fail unexpectedly. You are then left with no rationale behind why you failed, aside from that the problem got too big or your initial construction was somehow flawed.

Since your algorithm doesn't really understand the structure of the graph, you are a like a blind man who wants to visit his home at the bottom of a hill. He will get to the bottom of the hill that he is on and declare "I must be close to my house, for I am at the bottom of the hill", when in reality his hill and his house are across town. Sometimes he may even luck out and start on the right hill, but until he can reliably find the right hill, he will be lost.

You should therefore be very skeptical of people who say "We have made a great improvement in the state of the art: our algorithm is within 2% of the optimal ordering!". They very well might be within the bound they stated, but it is very hard to tell how close they actually are to the correct ordering. After all, India and America both have beaches, but to say that you are in India because you can hear the ocean is fallacious indeed.

## Chapter 2

## Mountains of Madness

So far I hope I have conveyed/answered the following:

* What is The Traveling Salesman Problem(TSP)?
* Why should I care about TSP?
* The solution to TSP, if one exists, lies in building up abstractions like a topographer builds terrain on a map, rather than iterative improvement. The terrain features should be invarriants in the graph (for a good starting place look at cycles)

So the next question would be: <B>What terrain features exist on our map?</b>

Note that in this section I will be defining some of these features in non-traditional ways if you are coming from graph theory. This is so that our data-structures can more readily fit the problem at hand. If there is overlap, I will redefine these terms so that we can distinguish them from their more basic versions you would have seen in algorithms like <b>BFS, DFS</b> and the like.

### Segment
  
  A <b>Segment</b> (also known as an <b>Edge</b> in graph theory), is a connection between two points and a distance provided by the cost function.

Segment( P<sub>1</sub> , P<sub>2</sub> ) = struct{

endpoint1 = P<sub>1</sub>

endpoint2 = P<sub>2</sub>

distance = C( P<sub>1</sub> , P<sub>2</sub> )

}

### Point

Our smallest feature is a <b>Point</b> (also know as a <b>Vertex</b> in graph theory), which is defined as a list of Segments all of the segments in the graph that connect to P<sub>1</sub>:

Point(P<sub>1</sub>) = struct{

sortedSegments = [Segment( P<sub>1</sub> , P<sub>2</sub> ) , Segment( P<sub>1</sub> , P<sub>3</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> )]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}

Many of the proceeding algorithms will rely on the fact that sortedSegments is sorted, so  sort it at construction.

The "<b>matches</b>" will be our current best guess of what two points should surround P<sub>1</sub> in our final ordering. Right now they will just be pointers to other points, but as we add more terrain features we will need to add more supporting data to prevent recalculation of what the best match is.

### Wormholes

A <b>Wormhole</b> is a point that has zero distance to two Points of your choosing and maximal distance to all other Points in the set.

So if we have Points 1 and 2 in the problem set and add Wormhole W "between" 1 and 2, then the new correct ordering would include:

1 <-> W <-> 2

This type of point could arise naturally in any problem set, but here we are calling it out for a specific purpose: testing. These Wormhole points will help us in three scenarios:

* We want to change a problem set in M dimensions (where M < N and N is the size of the problem set) into an N-dimensional one.
* We want to perturb a problem set in the smallest way possible, without changing the final ordering.
* We want to break a large problem down into many subsets (of arbitrary size) while still knowing the correct ordering of the subset

We get the first scenario for free just by adding the Wormhole. (Not sure if this is true in general, but many geometrical arguments/algorithms break down with the addition of Wormholes)

Second, if we add a Wormhole between two points that we know are in the correct ordering, then we can change the ordering of our Sorted Segments (and how points will match with each other) without changing the final correct ordering (aside from the wormhole being added). This is useful in testing as it is the smallest change we can make to a problem set and can illuminate potential problems with our algorithm.

Finally we can take any ordered subset in a correct ordering and add a Wormhole between the two endpoints of the subset. This will allow us to solve the subset in the same way we would have solved the original set. This is the  equivalent of saying we have points A and B with some set P (where P is the points in the original solution set that lie between A and B), find the most optimal path between A and B that also visits all of the points in P.

W <-> A <-> [ P<sub>1</sub> , P<sub>2</sub>, ... , P<sub>N</sub> ] <-> B <-> W

This is useful as it allows us to take a large problem and break it down into many sub-problems, greatly expanding the amount of data we can test our algorithm on. Before you get an intuition for what groupings are "correct", it is useful to be able to strip out the layers of abstraction to work on smaller ones.

    As a small aside, you can scale up many incorrect algorithms quickly and miss the fact that they are incorrect if you skip this step, especially if your data set has a bias towards points in the plane or of a certain dimensionality. Wormholes keep us honest.

Below are some links to help you understand a bit more of the math behind what I'm talking about:

General TSP to Metric TSP Reduction:

* <https://cstheory.stackexchange.com/questions/12885/guidelines-to-reduce-general-tsp-to-triangle-tsp>

TSP with two predetermined endpoints:

* <https://stackoverflow.com/questions/36086406/traveling-salesman-tsp-with-set-start-and-end-point>

### Bigger Structures?

Ok, so now we have outlined most of the basic structures that anyone who has looked problems in graph theory likely would have expected. I don't expect you to understand why we have some of those "extra" variables yet, but as we start to look at some bigger structures, it will become apparent.

So what's next? Where are the larger structures that I called out earlier that will build the terrain of the problem?

Well before we get to that , lets look at some examples to build some intuition:

    11  [Segment[11 : 12], Segment[11 : 13], Segment[11 : 10], Segment[9 : 11], Segment[11 : 14],  ...]

    12  [Segment[11 : 12], Segment[13 : 12], Segment[9 : 12], Segment[10 : 12], Segment[14 : 12], ...]

    13  [Segment[13 : 12], Segment[11 : 13], Segment[14 : 13], Segment[10 : 13], Segment[13 : 15], Segment[9 : 13], ...]

The above lists of sorted Segments are from the Djbouti_38 problem set from the University of Waterloo. In these examples I will denote each point by its final position in the correct ordering. So Point 1 would have neighbors 0 and 2 in the final correct ordering.

A [Segment](#segment) as a reminder is a relationship between two points and the distance between those points. This definition allows us to sort the segments without losing the relationship that they represent.

You can see that in the above example if each Point "got it's way" and matched with the two best other points in it's list, then we would have the following set of relationships:

11 <-> 12

12 <-> 13

13 <-> 11

Instantly we see a problem with the naive approach, we have created a loop, so if we wanted to connect to the other 35 points in  the Djibouti dataset we couldn't!

Before we declare defeat, let's try and examine what this loop is telling us:

Well, in a perfect world we would be able to make a loop of size 38 just by looking at each Point's favorite two potential matches.

    In reality this is the subset of convex hulls in the plane (think Circles or other Polygons) and this subset is much smaller than the unrestricted set of problems we'd like to solve.

From this observation we can also see that if we tried to solve the subset <b>S = [ P<sub>11</sub> , P<sub>12</sub> , P<sub>13</sub> ]</b>, that <b>11 <-> 12 <-> 13</b> would be the correct ordering of <b>S</b>.

At a more general level this loop is telling us that the subset/grouping <b>S</b> wants to connect with itself more than any other points in the graph. So to "resolve" this loop (i.e. find out which segment we should remove) we must find the two Points that want to match with the points in our loop. These outside points will be referred to as the loop's external points or just <b>externals</b> and will be used to determine the <b>cut segment</b>, or segment to remove from the loop.

    Also observe that there is no limit on the size that such a loop could be except that it must have more than 2 points and be less than or equal to the size of the entire point set.

Let's look at another example to get some more intuition and see some edge cases:

    20  [Segment[20 : 21], Segment[20 : 22], Segment[20 : 23], Segment[20 : 18], Segment[20 : 19], ...]

    21  [Segment[20 : 21], Segment[22 : 21], Segment[23 : 21], ...]

    22  [Segment[23 : 22], Segment[22 : 21], Segment[20 : 22], Segment[22 : 24], ...]

    23  [Segment[23 : 22], Segment[23 : 21], Segment[20 : 23], Segment[23 : 24], ...]

So if we match on the first two slots for each point in this set we'd get hte relationships:

20  -> 22

20 <-> 21

21 <-> 22

22 <-> 23

23 -> 21

So its one isn't a perfect loop  like  [11, 12, 13] was, but it might be prudent to mark this structure in the same way. If you look at the third Segment on 20 and 23, they'd like to match with each our before matching outside points. We could predict that our cut Segment would be Segment[23:20], but it might not be.

Ok I think we're ready for our first larger structure

### Knot

A <b>Knot</b> is defined as any subset <b>K</b> of <b>G</b> where all of the Points in <b>K</b> only want to match with each other and a maximum of two external Points.

Knot(P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>) = struct{

knotPoints = [P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>]

sortedSegments = [Segment( P<sub>1</sub> , P<sub>M+1</sub> ) , Segment( P<sub>1</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> ),

Segment( P<sub>2</sub> , P<sub>M+1</sub> ) , Segment( P<sub>2</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>2</sub> , P<sub>N</sub> ),

...

Segment( P<sub>M</sub> , P<sub>M+1</sub> ) , Segment( P<sub>M</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>M</sub> , P<sub>N</sub> ),
]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}

the sortedSegments are all of the segments from any Point in the <b>K</b> to any point not in <b>K</b>, sorted by distance

    Note for optimization: You likely don't need to sort all of the segments since we have already sorted them at each Point. Likely you could get the best 3 segments not pointing to other Points contained in the Knot since we will not go beyond this until we make a new Knot that encapsulates this one.

### Is that All?

Not quite, right now we have our base case (one small Knot), and our final desired state (one big Knot containing the entire graph), but are missing an intermediate state (i.e. what happens if we can't form a smaller Knot with some portion of the graph). Let's look at another example:

    ...
    13  [Segment[13 : 12], Segment[11 : 13], Segment[14 : 13], Segment[10 : 13], Segment[13 : 15], Segment[9 : 13], ...]

-------
    14  [Segment[14 : 15], Segment[16 : 14], Segment[14 : 13], ...]

    15  [Segment[16 : 15], Segment[14 : 15], Segment[15 : 17], ...]

    16  [Segment[16 : 15], Segment[16 : 17], Segment[16 : 14], Segment[16 : 18], ...]

    17  [Segment[18 : 17], Segment[16 : 17], Segment[15 : 17], Segment[19 : 17], ...]

    18  [Segment[18 : 17], Segment[19 : 18], Segment[20 : 18], ...]

    19  [Segment[19 : 18], Segment[20 : 19], ...]
-------
    20  [Segment[20 : 21], Segment[20 : 22], Segment[20 : 23], Segment[20 : 18], Segment[20 : 19], ...]
    ...

From our earlier example we know that <b>Point 13</b> is part of <b>Knot[11, 12, 13]</b> and take my word for it that <b>Point 20</b> is part of <b>Knot[20, 21, 22, 23]</b>, so if we naively laid out the relationships here is what we'd get:

14 -> 16

14 <-> 15

15 <-> 16

16 <-> 17

17 <-> 18

18 <-> 19

19 -> 20

So we have a core of <B>[14, 15, 16, 17, 18, 19]</b> with two failed endpoint matches <b>Segment[14:16]</b>, and <b>Segment[19:20]</b>. So it is obvious that this section is not a Knot yet, but what is it? this brings in our final data structure: a Run

    Note that it is debatable whether you need this structure, but for organizational purposes let's include it.

### Run

A <b>Run</b> is just like a <b>Knot</b>, but only its endpoints are exposed.

Run(P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>) = struct{

endpoint1 = P<Sub>1</sub>

endpoint2 = P<Sub>M</sub>

knotPoints = [P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>]

sortedSegments = [Segment( P<sub>1</sub> , P<sub>M+1</sub> ) , Segment( P<sub>1</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> ),

Segment( P<sub>M</sub> , P<sub>M+1</sub> ) , Segment( P<sub>M</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>M</sub> , P<sub>N</sub> ),
]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}

Next, so that we can look at [Points](#point) and [Knots](#knot) interchangeably, lets make some interface or abstract class above both of them (and any other structures we want to add later):

### Virtual Point

all of the stuff from [Points](#point), [Knots](#knot), and [Runs](#run) combined and generalized!

Now that we have all of our data structures, let's get cracking

## Chapter 3

## Mapping the Gordian Knot

Our knot mapping algorithm is as follows:

Main Loop:

1. Get all of the Virtual Points we haven't visited and run the continue from Knot Finding Loop #1
2. The new list of unvisited points is the returned knotList
3. If there is only one Virtual Point left, finish, otherwise continue from #1

Knot Finding Loop:

1. Get a Virtual Point(VP) that we haven't looked at yet
2. If we have looked at every VP return the knotList and continue from Main Loop #2
3. Check what the main VP's best two matches are
4. Check if the VPs that our main VP wants to match with will match back

   5. If so, update the two VPs with their matches and add them to the runList

            a. If we have found a Knot, (both ends of the runList have two matches)  create the Knot from the runList, add it to our knotList, and continue from #1

            b. If not set the main VP to the matched VP and continue from #2

   6. If not check if we have already failed once

            c. If we have, make a Run from the runList and continue from #1

            d.  If we haven't, set the main point to the other end of the runList and continue from #2

After every Main Loop Cycle every <b>Virtual Point</b> should either be part of a new <b>Knot</b>, a new <b>Run</b>, or have no matches yet. After this should roughly halve the number of VPs every cycle and leave us with one large <b>Knot</b> ready to be cut up.

For example here is Djibouti's unvisited list after one cycle (See [Appendix A](#appendix-a) for the full Sorted Segment List, I recommend doing this part by hand to start understanding how the matching works):

    unvisited:[
      Run[0 1 ], 
      Run[2 3 4 5 ], 
      Run[6 7 ], 
      8, 
      Run[9 10 ], 
      Knot[11 12 13 ], 
      Run[14 15 16 17 18 19 ], 
      Knot[20 21 22 23 ], 
      Run[24 25 ], 
      Run[26 27 ], 
      Knot[28 29 30 ], 
      31, 
      Run[32 33 ], 
      34, 
      Knot[35 36 37 ]]

Before we continue we should talk about the desired result of combining <b>Runs</b> and <b>Knots</b>

If we have any Runs that are nested within a new <b>Knot</b> or <b>Run</b>, we should dissolve the Runs for example in our next cycle we will get the Run:

<b>Run[Run[32, 33], Run[2, 3, 4, 5], Run [6, 7]]</b>

Which should dissolve to:

<b>Run[32, 33, 2, 3, 4, 5, 6, 7]</b>

With <b>2</b> and <b>33</b> matching, as well as <b>5</b> and <b>6</b> matching.

If this was a <b>Knot</b> instead of a run then we'd also match <b>7</b> and <b>32</b>

Next what should we do if we have two <b>Knots</b> in a new <b>Knot</b>? For example if we have the Knot:

    Knot[
          Knot[Knot[35 36 37 ] 34 ] 0 1 ], 

          Knot[32 33 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]
    ]

I am not sure why but this seems to be a common failure case, so we need to do something to address it (usually cause both of the Super Knot's matches point to the same knot). I think if we built the algorithm to be more flat (without Runs) it would not be the case.

Ideally we would be able to have the knot surrounded incorrect context so insert one of them into the other:

    Knot[32 33 Knot[Knot[35 36 37 ] 34 ] 0 1 ] 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]

    or

    Knot[Knot[35 36 37 ] 34 ] 0 1 Knot[32 33 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]] 

-------
    I think the recursive insertion process should be that if you have a knot that only points to another knot then insert and keep going down levels till you are at the base knot
  
-------

### Silver into Gold

When we have a Run that loops back on itself at both ends we have a Knot, but what if we have a Run that only loops back internally? Or a Virtual Point that is next to a knot, and points to every point in its neighboring knot before pointing to other things in the Run? The following section will describe the process of finding Half Knots, or Knots which have all of the characteristics of a Knot once formed except that they do not form a perfect Knot when we are doing Knot finding (i.e. both ends of their Run point to each other).

We will use the same data structures as the perfect Knots to encapsulate Half Knots, but we will only look for them once either end of our Run has been exhausted. If we find any Half Knots in our Run the remaining Virtual Points will be reset to avoid any pointer confusion.

### Next Steps

Finally once we found every Knot and made one large Knot we get:

    Knot[
      Knot[
          Knot[32 Knot[Knot[Knot[28 29 30 ] 31 ] 26 27 ] 
                Knot[11 12 13 ] 10 9 8 7 6 5 4 3 2 
                Knot[Knot[Knot[35 36 37 ] 34 ] 0 1 ] 33 
              ] 
          24 25 ] 
      14 15 16 17 18 19 20 21 22 23 
    ]

    but really it should be more like

    Knot[32 Knot[Knot[Knot[28 29 30 ] 31 ] 26 27 ] 25 24 Knot [20 21 22 23] 19 18 17 16 15 14 Knot[11 12 13 ] 10 9 8 7 6 5 4 3 2 Knot[Knot[Knot[35 36 37 ] 34 ] 0 1 ] 33 ]

The next step is to cut it up according to the external matches

## Chapter 4

## The Sword of Iskandar

Now that we have our final all-encompassing Knot (i.e. it should contain every point in the set), we need to start cutting it up based on the externals that we matched with the Knot.

Our final Knot doesn't have any externals by definition, so we can simply dissolve it.

The general idea will be this:

* Dive into the first knot in the list until we find a knot with no sub-Knots.
* Find the base Knot's external matches.
* Figure out what segments to cut based on the externals.
* Figure out which side of each segment to match to from the externals (these matched points will be labeled KnotPoints).
* Figure out how to connect the unmatched points (referred to as CutPoints) to each other.
* Add the minimal ordering into the list of the sub Knot's parent.
* Repeat until we don't have any Knots left in the list.

Our cutting algorithm will not work on a nested knot, so if the knot we are trying to cut has a height greater than 1 we will need to recursively cut it internally.

### Cutting Loop

    Key:

    a <-> b direct connection between a and b

    a <-> ... <-> b points in between a and b not explicitly represented

    a | b cut in the connection manifold between a and b

Once we have the two externals and the flat knot <b>K</b> we are trying to cut, we will enter the cutting loop. The idea is we will have a doubly nested loop that iterates over the segments of <b>K</b> making for a N<sup>3</sup> operation (once the recursive nature of the Knot is taken into account).

If the two cut segments overlap fully (i.e. Segment [a:b] and Segment [b:a] are the cuts) then we will calculate the Agree case (seen below) and if it is a smaller loop than our minimum, replace it. so if we had Cut Segment: [a:b]

... <-> a <-> b <-> ...

with two external points ex1 and ex2, then we could have the following path

... <-> a <-> ex1 <-> ... <-> ex2 <-> b <-> ...

If the two cut segments overlap partially (i.e. Segment [a:b] and Segment [b:c] are the cut segments) then ignore this pair as it would leave one point orphaned (unconnected  to the final path).

If the two cut segments are disjoint (i.e. Segment [a:b] and Segment [c:d] are the cut segments) then we need to figure out which will connect to the externals and which will we attach internally, so if we had

... <-> a <-> b <-> ... <-> c <-> d <-> ...

with two external points ex1 and ex2, then we could have the following paths:

    1.

    ... <-> ex2 <-> a <-> ... <-> d | b <-> ... c <-> ex1 <-> ...

or

    2.

    ... <-> ex2 <-> d <-> ... <-> a | c <-> ... b <-> ex1 <-> ...

or

    3.

    | a <-> ... <-> d | ... <-> ex2 <-> b <-> ... <-> c <-> ex1 <-> ...

or

    4.

    | b <-> ... <-> c | ... <-> ex2 <-> a <-> ... <-> d <-> ex1 <-> ...

The relationship and ordering in the points not connected to the externals is unknown in this preliminary state and will be explored further later.

The differences of which KnotPoint connects to which External Point are not represented here as they do not effect the internal state of the resulting Knot.

The Four States shown above are as follows:

1. CutPoint2(d) and KnotPoint1(a) are connected to each other but are not connected to CutPoint1(b) or KnotPoint2(c) with KnotPoint1 and KnotPoint2 being connected to the externals.

2. CutPoint1(a) and KnotPoint2(d) are connected to each other but are not connected to CutPoint2(c) or KnotPoint1(b) with KnotPoint1 and KnotPoint2 being connected to the externals.

3. CutPoint1(a) and CutPoint2(d) are connected to each other but are not connected to KnotPoint1(b) or KnotPoint2(c) with KnotPoint1 and KnotPoint2 being connected to each other and and the externals.

4. CutPoint1(b) and CutPoint2(c) are connected to each other but are not connected to KnotPoint1(a) or KnotPoint2(d) with KnotPoint1 and KnotPoint2 being connected to each other and and the externals.

Once we have calculated all of the possible distance changes (and how the internal structure changes the distance) we take the smallest one and apply it.

### Finding Cut Point Matches

In our example #1 above it is easy to see that a -> ex1 and c -> ex2 means that a and c have all of their Segments accounted for, we will call this <b>Balanced</b>. An entire Knot having been cut, is balanced if every one of its Points has two matches and each external has one match inside the knot.

From here we will refer to the two points that match to the externals as Knot Points (a and c) and the other Points that were cut from their Segments as Cut Points (b and d).

Our Cut Points b and d in the example above would be unbalanced since we haven't decided what their new neighbors would be. But in the simple case we know that in order to balance a Knot we can match the Cut Points to each other. If we are only dealing with perfect knots(e.g. a Circle in the plane, triangle, etc.), then simply connecting the cut points is all we need, but if we have nested knots, things get a lot more complicated. Also note that for any bottom knot in the stack, that we can simply connect the Cut Points by definition, since every other point in the knot is already matched to its favorite other two points.

### Same Knot Different Super Knot

As we flatten the stack we need a way to tell the difference between a Sub Knot and a Super Knot, this is where our next data structure the CutMatchList comes in, in the simple case our CutMatchList would consist of one internal match and two implied matches, the internal match would be between the two Cut Points and the two implied matches would be between the two Knot Points and their externals.

### Balance in All Things

For a CutMatchList to be Balanced we need for the following conditions to hold (some we will actively calculate and some are implied by others):

* After the CutMatch is applied each Point in the Knot has exactly two matches
* Each external will have exactly one match, or exactly two matches if external1 == external2
* After the CutMatch is applied, KnotPoint1 and KnotPoint2 should be connected to each other just by the segments in the Knot
Implied by above:
* We should not have matched any Segment that already exists in the Knot's Path
* We should not have cut any Segment that does not exist in the Knot's Path
* We should not have matched between KnotPoint1 and KnotPoint2
* We should not have cut between KnotPoint1 and CutPoint1 or KnotPoint2 and CutPoint2 as it is repetitious
* We should not have multiple cycles in the  Knot

### The Winds and the Tides

Now that the framework for cutting the recursive Knot into one Knot has been laid out, we need to look athte internal structure of the knots we are cutting, 

### Complexity Limit

Immediately I can see a few questions with this approach:

Q1.  Why do we only need to look at a maximum of two cut segments? For example in the Match Twice and Stitch Algorithm they have many cut segments that they apply.

A1. This really comes down to how we are defining and finding our knots. Remember that a Knot is not any cycle in the graph, even though it is a cycle. A Knot is defined as a subset of the graph that only want to match with itself and a maximum of two external VirtualPoints. Given this definition, if we find our knots correctly, then this cutting algorithm will produce the optimal result.

Q2. Do we really need to look at all of the cut segments, shouldn't we just be able to look at the ones that are closest to the external VirtualPoints?

A2. While such an approach can work for simple cases that lie in the plane, it does not work in general and the algorithm i've outlined is really the best we can do. Take as an example a circle lying in the plane with a center point at (0,0) and with VirtualPoints distributed on the path of the circle with uneven spacing, lets call this Knot C for circle. Then arrange 3 VirtualPoints far above the circle in a line where their x and y coordinates are 0,0 and they only differ by their z coordinate and let's call this Knot L for line. since the distance from Knot L to any point on Knot C is constant, we must look at every cut segment in  Knot C to find out which will be in hte correct ordering.

Ok so now we know we must at least look at all of the segments once, but why this business with two cut segments?

Well, let's now mirror Knot L across the XY Plane so that we have Knot L1 above it and Knot L2 below it. now if we want to cut Knot K we must also consider all of the disjoint segments that would have to match across the circle internally as well.

Q3. How do we get a Big O of N<sup>3</sup>?

A3. In the Worst Case there would be  N-4 Knots to cut, and on average I have seen most sets with roughly N/3 Knots. So if we have O(N) Knots, and we are looking at all cut segment pairs in each Knot with time O(N<sup>2</sup>), then O(N)*O(N<sup>2</sup>) = O(N<sup>3</sup>)
^^ I think this is wrong and only correct assumeing that we use simply connections instead of recursive ones :(

## Chapter 5

## Plan D! Its diabolical! Its lemon scented!

ok, so I have realized that there is a way to connect any two cutpoints optimally without going through exp space, the first step is to realize that at least in the plane , we must only connect to other points within the cutpoints smallest knot or route along the super knots toward the other cutpoint again only going via internal other points in the smallest knot, this prevents us from crossing our own path in the plane. However, the restrictions I have laid out would not work on N-dimensionally embeded graphs, so what shall we do?

Well I think the plan should be to run dijkstras from cutpoint to cutpoint, with the caveate that the enterance to a node is only found in its neighbors. So if we have cutpoint 1 in Knot[1 2 3 4] where 2 is a knot point, and we want to go to cutpoint 5 in Knot[Knot[1 2 3 4] 5 6] with flattened knot Knot[1 2 3 4 5 6] and knot point 2 being 4, then two could only be reached by cutting 1 and 3, 3 could only be reach by cutting 2 and 4, and 5 could only be reach by cutting 4 and 6, if we store the previous best node, which side we are cutting and the total min distance, then we can run a modified version of dijkstras that should find the correct route from 1 to 5 and all of the cuts we'd have to make along the way by backtracking from the other cut point to the starting one.

Ok the method that I have outlined in Chapter 4 for cutting and combining the knots would be correct if we had exponential time or space (I think the recurisve tree size always means that even if we do dynamic programming that we will fail), so whats next? Well lets start with some idealized examples and see if we can build to a general system for cutting.

### Example One: The Circle

<img src="img\snap103.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>

if we have some Circle with N Points describing its sruface then we are done, Our knot finding code can easily find this greedy match solution and there is no hierarchy to it.

### Example Two: The Inscribed Circles

## Links

Look at Section 4 Minimum Bounding Circle By Megiddo:

* <https://epubs.siam.org/doi/pdf/10.1137/0212052>

Match Twice and Stitch Algorithm:

* <https://vlsicad.ucsd.edu/Publications/Journals/j67.pdf>

Waterloo TSP Dataset:

* <http://www.math.uwaterloo.ca/tsp/world/countries.html#LU>