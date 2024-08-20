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

The problem with many similar algorithms (greedy match, any colony, minimum spanning tree transformation, etc.) is that you can know that an answer is within some bound of the correct one, but there is no framework for causal reasoning on why a given solution is wrong, we simply say that the algorithm didn't work for this set and then use some form of k-opt segment swap to minimize the answer (look ma it is getting shorter!). This is the general problem with using heuristics  rather than looking for root causes, iteration is at the heart of scientific thought and if you have no theoretical framework to wrap around a problem, then there is no place to iterate your theory when you find an exception to it. It is the difference between engineering and science. *WARNING BASELESS RANT INCOMING* I think for all of its sins, the greatest one in Computer Science is that we often rely too much on engineering efforts rather than the scientific and mathematical efforts of our fields namesake. We as a field seek short term gain in fitness functions (wether the fitness function is path length in TSP research or benchmark success in Machine Learning) instead of long term understanding of deeper truths. If we continually throw aside understanding at the feet of greater compute power then we do not deserve to be called scientists. The opposite tendency, to only rely on proofs is similarly flawed and I find many of the claims of Complexity theory to fall into this category of never really saying anything of use and building no intuition(which is inherently unprovable), except what is directly derivable from previously shown results. If an answer to P vs NP exists, I expect that it will be a leap of logic unlike what we have seen before not a neatly built bridge of small results from P vs NP to P=NP or P!=NP. Since most Complexity theorists are trying to prove P!=NP because of a series of reductio ad absurdum (which as Schrodinger found out in the quantum realm, the world is often more absurd than the limits of human imagination), there is a dirth of academic research trying to use the structure of relationships to map out what NP problems can be solved in P. As far as I can tell, the notion that Optimal tours might behave and change in the same ways that a precision watch would change to a replacement of gears, is lost on most people. We as a field either jump to statistical arguments or quasi-mathematical arguments neither of which seem to build actual understandable models of what is going on in a perfect circuit. (I say quasi here since very little of complexity theory deals with the actual nature of programs, geometrical structures and the like, instead falling back to the most broad of arguments, making it closer to the philosophy of old than the mathematics and science of the early 20th century, [GTC](http://ramakrishnadas.cs.uchicago.edu/gctcacm.pdf) seems promising on the P!=NP front and I need to read more about it).

Why not clusters? There is a lot of literature on how to find clusters in graphs and they sort of have the property we are looking for that an ideal k-clustering of a graph is a natural abstraction. Two problems arise when considering clusters, first is how do we choose k, i.e. how do we know how many clusters there are in the graph? This is not a trivial problem and in general we can show that there is no correct answer by the simple fact that if we choose k to be n then we have n natural abstractions one for each point and if we choose k to be 1 then we have one natural abstraction in the whole set, both of these are perfectly valid and easy to compute natural abstracts, but non-useful. Ok so maybe instead of using something like k means we use a hierarchical clustering algorithm like the [nearest-neighbor chain algorithm](https://en.wikipedia.org/wiki/Nearest-neighbor_chain_algorithm). This method would work better since we don't have to divine the number of clusters in the graph, but there is still a pretty serious problem with an approach like this. Since clusters have no ordering except points are either in the cluster or out of it, how would we form a cycle out of a cluster? If our smallest cluster in the hierarchy is some size m where m < n, then we'd still have 2^m possible cycles to choose from in order to find the optimal, and if we add up all of our k smallest clusters where k clusters consume the entire set, then we'd have 2^m_1 + 2^m_2 + ... + 2^m_k cycles to choose from. one we had all of these cycles, were is still no guarantee that we could combine them in any easy way so we'd have to also do a pairwise 2^m_a + 2^m_b combination step (where a and ba are two of the cycles found in the previous step) to get the final correct cycle. So is there any clustering method that could lead use to a natural abstraction that is easy to find, and useful in solving our problem? If we want to have a good natural abstraction, it would be useful if the abstraction had the correct cycle for the subset be incidental to the formation of the abstraction, much like in the plane the correct cycle of a convex hull subset is found simply by finding the convex hull. I don't know of any clustering algorithm that has that property (doesn't mean one doesn't exist) since clusters are mainly concerned with membership rather than ordering, so let's focus our efforts elsewhere.

Another idea might be to make an ear decomposition (See Figure Below) using the matchings of our graph as the "edges" of the decomposition. Once we have some an ear decomposition we could then cut the resulting decomposition into a single path. Since our Graph is fully connected we'd also have to ensure that we only assign two edges per vertex greedily once our seed cycle is found in order to not multiple form cycles in the resulting path.

<img src="img\EarDecomposition.png" alt="EarDecomposition" width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Example of an Ear decomposition of a partially connected graph </p>

I think this is moving in the right direction, since we have a nested structure to work with, we are moving toward a natural abstraction, but for similar reasoning to why convex hulling is an unnatural abstraction, this method would also be unnatural. This can be seen by the simple fact that there is no unique ear decomposition and any cycle we choose as the seed cycle (e.g. G_1 in the above Figure) would be valid. In many graphs in the waterloo dataset there are multiple cycles of matchings that could serve as the seed cycle. This necessarily means that you'd end up splitting one of the cycles of matchings into two ears breaking the naturalness of the abstraction. If we could guarantee that our matching ear decomposition would only stack more ears on top of each other, breaking no matching cycles, then this would be a promising technique and investigating the proper cutting algorithm would be the next step, but since this property is broken even by small graphs, this technique warrants no further investigation.

### The Gordian Knot

Okay, What could the natural abstraction be? Well I think it has the following form: a Cycle of Points and Virtual Points (i.e. a subset of the graph that has already been abstracted), in which every Virtual Point in the Cycle wants most to match most with its two neighbors in the Cycle. This Cycle can have either two matches that want to go to the outside or none. Once such a cycle is found we can abstract it away as a Virtual Point and add it to our set, removing any points from the set that are contained within the cycle. We will call this natural abstraction a Knot.

A Way to think about this structure is that it is similar to the ear matching decomposition, but generalizes it to multiple seed cycles.

Even this is not quite a true description of the structure, since what we are really trying to do is: Once we form a cycle we need to also make this cycle into a point for use in finding new cycles. The recursive nature of the data structure is necessary to ensure that it is a natural abstraction and the cyclic nature of the abstraction ensures that , at least in the base case, we will be able to find the correct tsp tour with ease. Finally the constraint that there should only be one in and one out (meaning that the Virtual Point is a member of a super cycle) ensures that we will be able to merge tsp cycles in polynomial time. [See Chapter 4 for details on merging](#chapter-4).

<img src="img\layers.gif" alt="layers" width="70%" style="max-width: 1000px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Example of a nested Knot structure, moving up through the layers</p>

<img src="img\wi29_numbers.png" alt="layers" width="70%" style="max-width: 1000px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> wi29 dataset numbers</p>

    Knot(flattens to: {6 5 4 3 2 1 0 28 27 26 25 24 23 22 21 20 19 18 17 16 15 14 13 12 11 10 9 8 7})[
        Knot(flattens to: {21 22 23 24 25 26 27 28 2 0 1 5 6 4 3 20})[
            Knot(flattens to: {22 23 24 21})[
                Knot[22 23 24 ] 
                21 
            ],
            Knot(flattens to: {3 4 6 5 1 0 2 28 27 26 25})[
                Knot(flattens to: {3 2 28 0 1 5 6 4})[
                    3,
                    Knot(flattens to: {6 4 2 28 0 1 5})[
                        6,
                        Knot(flattens to: {5 4 2 28 0 1})[
                            5,
                            4,
                            Knot(flattens to: {2 1 0 28})[
                                Knot[0 2 1 ],
                                28 
                            ] 
                        ] 
                    ] 
                ],
                Knot[27 26 25 ] 
            ],
            20 
        ],
        Knot(flattens to: {9 8 7 19 18 17 16 15 14 13 12 11 10})[
            Knot(flattens to: {7 19 18 17 16 15 13 14 9 8})[
                Knot(flattens to: {19 7 14 13 15 16 17 18})[
                    19, 
                    7, 
                    Knot(flattens to: {14 13 15 16 17 18})[
                        Knot(flattens to: {14 13 15 16 17})[
                            Knot[13 15 14 ], 
                            16, 
                            17
                        ], 
                        18 
                    ] 
                ],
                9, 
                8 
            ],
            Knot[10 11 12 ] 
        ] 
    ]

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
* The solution to TSP, if one exists, lies in building up abstractions like a topographer builds terrain on a map, rather than iterative improvement. The terrain features should be invariants in the graph (for a good starting place look at cycles)

So the next question would be: <B>What terrain features exist on our map?</b>

Note that in this section I will be defining some of these features in non-traditional ways if you are coming from graph theory. This is so that our data-structures can more readily fit the problem at hand. If there is overlap, I will redefine these terms so that we can distinguish them from their more basic versions you would have seen in algorithms like <b>BFS, DFS</b> and the like.

### [Segment](.\src\shell\knot\Segment.java)
  
  A <b>Segment</b> (also known as an <b>Edge</b> in graph theory), is a connection between two points and a distance provided by the cost function.

Segment( P<sub>1</sub> , P<sub>2</sub> ) = struct{

endpoint1 = P<sub>1</sub>

endpoint2 = P<sub>2</sub>

distance = C( P<sub>1</sub> , P<sub>2</sub> )

}

### [Point](.\src\shell\knot\Point.java)

Our smallest feature is a <b>Point</b> (also know as a <b>Vertex</b> in graph theory), which is defined as a list of Segments all of the segments in the graph that connect to P<sub>1</sub>:

Point(P<sub>1</sub>) = struct{

sortedSegments = [Segment( P<sub>1</sub> , P<sub>2</sub> ) , Segment( P<sub>1</sub> , P<sub>3</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> )]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}

Many of the proceeding algorithms will rely on the fact that sortedSegments is sorted, so  sort it at construction.

The "<b>matches</b>" will be our current best guess of what two points should surround P<sub>1</sub> in our final ordering. Right now they will just be pointers to other points, but as we add more terrain features we will need to add more supporting data to prevent recalculation of what the best match is.

### Wormholes

[DistanceMatrix:addDummyPoint()](.\src\shell\DistanceMatrix.java#L177)

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

### [Knot](.\src\shell\knot\Knot.java)



A <b>Knot</b> is defined as any subset <b>K</b> of <b>G</b> where all of the Points in <b>K</b> only want to match with each other and a maximum of two external Points.

Knot(P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>) = struct{

KnotPoints = [P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>]

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

### [Run](.\src\shell\knot\Run.java)



A <b>Run</b> is just like a <b>Knot</b>, but only its endpoints are exposed.

Run(P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>) = struct{

endpoint1 = P<Sub>1</sub>

endpoint2 = P<Sub>M</sub>

KnotPoints = [P<sub>1</sub>, P<sub>2</sub>, ... , P<sub>M</sub>]

sortedSegments = [Segment( P<sub>1</sub> , P<sub>M+1</sub> ) , Segment( P<sub>1</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>1</sub> , P<sub>N</sub> ),

Segment( P<sub>M</sub> , P<sub>M+1</sub> ) , Segment( P<sub>M</sub> , P<sub>M+2</sub> ) , ... , Segment( P<sub>M</sub> , P<sub>N</sub> ),
]

match1 = P<sub>*</sub>

match2 = P<sub>*</sub>

}

Next, so that we can look at [Points](#point) and [Knots](#knot) interchangeably, lets make some interface or abstract class above both of them (and any other structures we want to add later):

### [Virtual Point](.\src\shell\knot\VirtualPoint.java)

all of the stuff from [Points](#point), [Knots](#knot), and [Runs](#run) combined and generalized!

Now that we have all of our data structures, let's get cracking

## Chapter 3

## Mapping the Gordian Knot

Our Knot mapping algorithm is as follows:

Main Loop:
[Shell:slowSolve()](.\src\shell\Shell.java#L552)


1. Get all of the Virtual Points we haven't visited and run the continue from Knot Finding Loop #1
2. The new list of unvisited points is the returned KnotList
3. If there is only one Virtual Point left, finish, otherwise continue from #1

Knot Finding Loop:
[Shell:createKnots()](.\src\shell\Shell.java#L61)

1. Get a Virtual Point(VP) that we haven't looked at yet
2. If we have looked at every VP return the KnotList and continue from Main Loop #2
3. Check what the main VP's best two matches are
4. Check if the VPs that our main VP wants to match with will match back

   5. If so, update the two VPs with their matches and add them to the runList

            a. If we have found a Knot, (both ends of the runList have two matches)  create the Knot from the runList, add it to our KnotList, and continue from #1

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

I am not sure why but this seems to be a common failure case, so we need to do something to address it (usually cause both of the Super Knot's matches point to the same Knot). I think if we built the algorithm to be more flat (without Runs) it would not be the case.

Ideally we would be able to have the Knot surrounded incorrect context so insert one of them into the other:

    Knot[32 33 Knot[Knot[35 36 37 ] 34 ] 0 1 ] 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]

    or

    Knot[Knot[35 36 37 ] 34 ] 0 1 Knot[32 33 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]] 

-------
    I think the recursive insertion process should be that if you have a Knot that only points to another Knot then insert and keep going down levels till you are at the base Knot
  
-------

### Silver into Gold

When we have a Run that loops back on itself at both ends we have a Knot, but what if we have a Run that only loops back internally? Or a Virtual Point that is next to a Knot, and points to every point in its neighboring Knot before pointing to other things in the Run? The following section will describe the process of finding Half Knots, or Knots which have all of the characteristics of a Knot once formed except that they do not form a perfect Knot when we are doing Knot finding (i.e. both ends of their Run point to each other).

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

Now that we have our final all-encompassing Knot (i.e. it should contain every point in the set), we need to start cutting it up based on the externals that we matched with each sub-Knot.

Our final Knot doesn't have any externals by definition, so we can simply dissolve it.

The general idea will be this:

* Dive into the first Knot in the list until we find a Knot with no sub-Knots.
* Find the base Knot's external matches.
* Loop through all segment pairs to cut and figure out the distance changed by picking a particular pair.
* Figure out which side of each segment to match to based on the distance to the externals (these matched points will be labeled KnotPoints).
* Figure out how to connect the unmatched points (referred to as CutPoints) to each other.
* Add the minimal ordering into the list of the sub-Knot's parent.
* Repeat until we don't have any Knots left in the list.

Our cutting algorithm will not work on a nested Knot, so if the Knot we are trying to cut has a height greater than 1 we will need to recursively cut it internally.

### Cutting Loop

    Key:

    a <-> b direct connection between a and b

    a <-> ... <-> b points in between a and b not explicitly represented

    a | b cut in the connection manifold between a and b

Once we have the two externals and the flat Knot <b>K</b> we are trying to cut, we will enter the cutting loop. The idea is we will have a doubly nested loop that iterates over the segments of <b>K</b> making for a N<sup>3</sup> operation (once the recursive nature of the Knot is taken into account).

If the two cut segments overlap fully (i.e. Segment [a:b] and Segment [b:a] are the cuts) and it is a smaller loop than our minimum, replace it. So, if we had Cut Segment: [a:b]

... <-> a <-> b <-> ...

with two external points ex1 and ex2, then we could have the following path

... <-> a <-> ex1 <-> ... <-> ex2 <-> b <-> ...

If the two cut segments overlap partially (i.e. Segment [a:b] and Segment [b:c] are the cut segments) then ignore this pair as it would leave one point orphaned (unconnected  to the final path)(Note: that I think it is wrong to ignore this case, but I have not seen a data-set where is matters, likely because of my bias toward the plane).

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

The differences of which KnotPoint connects to which External Point (via parity of a and c, or b and d, etc.) are not represented here as they do not effect the internal state of the resulting Knot.

The Four States shown above are as follows:

1. CutPoint2(d) and KnotPoint1(a) are connected to each other but are not connected to CutPoint1(b) or KnotPoint2(c) with KnotPoint1(a) and KnotPoint2(c) being connected to the externals.

2. CutPoint1(a) and KnotPoint2(d) are connected to each other but are not connected to CutPoint2(c) or KnotPoint1(b) with KnotPoint1(b) and KnotPoint2(d) being connected to the externals.

3. CutPoint1(a) and CutPoint2(d) are connected to each other but are not connected to KnotPoint1(b) or KnotPoint2(c) with KnotPoint1(b) and KnotPoint2(c) being connected to each other and and the externals.

4. CutPoint1(b) and CutPoint2(c) are connected to each other but are not connected to KnotPoint1(a) or KnotPoint2(d) with KnotPoint1(a) and KnotPoint2(d) being connected to each other and and the externals.

Once we have calculated all of the possible distance changes (and how the internal structure changes the distance) we take the smallest one and apply it.

### Finding Cut Point Matches

In example #1 above it is easy to see that a -> ex1 and c -> ex2 means that a and c have all of their Segments accounted for, we will call this <b>Balanced</b>. An entire Knot having been cut, is balanced if every one of its Points has two matches and each external has one match inside the Knot.

From here we will refer to the two points that match to the externals as KnotPoints (e.g.1 a and c) and the other Points that were cut from their Segments and remain unmatched as CutPoints (e.g.1 b and d).

Our CutPoints b and d in the example #1 above would be unbalanced since we haven't decided what their new neighbors would be. But in the simple case we know that in order to balance a Knot we can always match the CutPoints to each other (this is likely not optimal). If we are only dealing with perfect Knots(e.g. a circle in the plane, triangle, etc.), then simply connecting the CutPoints is all we need, but if we have nested Knots and therefore a more complex Knot manifold, things get a lot more complicated.

A knot manifold is the tsp optimal tour formed by any Knot and its sub-Knots. We are cutting the Knot's manifold to connect to the external points and must repair the manifold to calculate the manifold for Knot's parent. Also note that for any bottom Knot in the stack, that we can simply connect the CutPoints by definition, since every other point in the Knot is already matched to its favorite other two points.

### Same Knot Different Super Knot

As we flatten the stack we need a way to tell the difference between a Sub Knot and a Super Knot, this is where our next data structure the CutMatchList comes in, in the simple case our CutMatchList would consist of one internal match and two implied matches, the internal match would be between the two CutPoints and the two implied matches would be between the two Knot Points and their externals.

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
* We should not have multiple cycles in the Knot

### The Winds and the Tides

Now that the framework for cutting the recursive Knot into one Knot has been laid out, we need to look at the internal structure of the Knots we are cutting. To find the all of the possible internal routes from KnotPoint1 to KnotPoint2 is a NP Hard problem and to fully explore this space would take N Factorial time. But, we are in luck! since the manifold of the Knot is already a nearly optimal route (aside form the cuts we made)! We can abuse this fact to get the optimal route in N^3 time (the running time of Dijkstra's in worse case).

    Keep in mind that we will have to repeat this for N^2/2 combinations of cut segments and so this is more like N^5 time. Also remember that we need to do this for ~N/3 Knots so this is more like N^6 time. Also keep in mind that we will have to calculate the path dependence on every cut match we make, so this is more like N^7 time. Finally note that if you could figure out a way to separate any of these caveats from their context (Similar to how Floyd-Warshall calculates all possible shortest paths at once) then you could significantly reduce the runtime of the algorithm described below.

<img src="img\HoleGame_Setup.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Initial State of the Hole-Filling Game</p>

To solve the problem stated above let's imagine a Hole-Filling Game, where the objective is to move the hole at CP1, KP1 to KP2, CP2 in the least possible distance and making both holes disappear. The following describes the valid moves in the game: Imagine that the hole in the Knot between CutPoint1 and KnotPoint1 is a movable hole centered at CutPoint1. Moving the hole to <b>Point P</b> (marked with a blue arrow below) with Neighboring Points P_prev and P_next consists of cutting the Segment between P and P_prev (S_a) or P and P_next (S_b) and matching between CutPoint1 and P-prev (S_x) or CutPoint1 and P_next (S_y) respectively, the cost of moving the hole would then be S_x - S_a or S_y - S_b respectively.

<img src="img\HoleGame_OneMove.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Hole-Filling Game after 1 move, cutting S_b (P to P_next) and matching S_y (CP1 to P_next)</p>

The object of the game is then to find the the shortest set of hole moves such that the hole ends up filling the other hole formed by KnotPoint2 and CutPoint2 centered on KnotPoint2 without forming multiple cycles in the graph.

<img src="img\HoleGame_Finished.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> A possible shortest path from the Hole-Filling Game</p>

The beauty of such an approach is that standard shortest path algorithms like Dijkstra's or Floyd-Warshall will give us the minimal set of cuts and matches to form the optimal ordering of the Knot in K^3 Time, where K is the number of Points in current Knot's the manifold! This is only possible because of the stipulation on the recursive Knot structure that every Virtual Point must only have two neighbors and that those neighbors must not "want" to be inside the Virtual Point, i.e. that we have a natural abstraction in our concept of a Virtual Point and a Knot.

I should also Note that there are 4 possible assignments, excluding parity duplicates, of KP1, CP1, CP2, and KP2 to the points in the cut segments. So, there are 4 x K^2 assignments for a single cut segment pair in the Knot manifold with meaningfully distinct internal re-orderings. This means that our Big O complexity for cutting a Knot is O(4 x K^5 x M) where M is the number of Knots in the recursive structure (at most the sum of K/3 + K/9 + K/27 + ... = ~K/2). We shall see in the next section that this is an underestimate given the path dependent structure of the problem.

### The Fly in the Ointment

The problem with naively applying shortest path algorithms to this Hole-Filling Game is two-fold: First, avoiding forming multiple cycles in our answer tour is not a trivial problem to solve (i.e. it will increase the exponent of our runtime and prevent us from using more performant algorithms). Second, the possibility of negative weights (we must subtract the cut segment distance from the match segment distance in each move) means we will have to modify our search algorithm to accommodate for backtracking. Let's start with the problem of multiple cycles, as it dominates the structure of our answer. The first multiple cycle failure case is illustrated in the following:

<img src="img\HoleGame_SelfLoop.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> A loop is formed in the Hole-Filling Game</p>

You will notice if we go directly to CP2 from the current CutPoint, Point P, then we will have multiple loops formed in the graph such that CP1 and CP2 are not connected (more generally we'd want to check using Union-Find that all endpoints are connected). We have split the graph into three separate parts instead of the two we desire, any further match that does not match with a point between P and CP1 or to CP2 would keep the graph in three pieces (matching to CP2 would make it into two pieces again). However, if we go from Point P to any point in that loop, we will fix the situation and then can exit to CP2 and complete the path without multiple loops.

<img src="img\HoleGame_SelfLoop_Fixed.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Loop is broken and we can again exit</p>

Another failure case is as follows:

<img src="img\HoleGame_Disconnected.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> KnotPoints are connected to each other instead of a CutPoint</p>

In the example above, if we exit now we will again form two cycles in the graph instead of one but for a different reason, that both Knot Points are connected to each other and both CutPoints are connected to each other but neither CutPoint is connected to a KnotPoint. And similarly to the first "self-loop" failure case, if we match to any of the points between KP1 and KP2, we would fix the situation and could again exit to CP2. I would like to call this state in the Hole-Filling Game, where we cannot exit to CP2 without forming multiple cycles in the graph, <b>Disconnected</b> and the initial state of the Hole-Filling Game, where we can connect to CP2 and exit without issue, <b>Connected</b>. It should also be easy to see that we can start the Hole-Filling Game from a disconnected state!

<img src="img\HoleGame_DisconnectedStart.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Hole-Filling Game starting out disconnected</p>

It is also easy to see that we can end up in a disconnected and self-loop state rather easily by forming a loop with any subset of the points between CP1 and CP2:

<img src="img\HoleGame_DisconnectedLoop.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Hole-Filling Game disconnected with a loop</p>

To be able to exit again to CP2 from state above, we'd need to match to any point in the self-loop (i.e. any of the points between the end of hte blue arc) and match to one point between KP1 and KP2 to transition from Disconnected to Connected.

It is also important to note that we can always plan a route that does not form any self-loops but has the same matches and cuts represented by a self-loop that was formed and then broken. For example, if we take the figure below as State #1.

<img src="img\HoleGame_SelfLoop.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> A loop is formed in State #1 (Blue)</p>

And the next figure as State #2 (with the second move marked in Yellow)

<img src="img\HoleGame_SelfLoop_Fixed_State2.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Loop is broken and we can again exit in State #2 (Yellow)</p>

And the next figure as State #3 (with the third move marked in Purple)

<img src="img\HoleGame_SelfLoop_Fixed_State3.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Hole-Filling Game is completed in  State #3 (Purple)</p>

If instead we had flipped the order of the blue and yellow state (notice below that the blue and yellow arrows have swapped spots) we would have never formed a self-loop and we would have been able to represent the same match cuts in a different order, but without the possibility of exiting before fixing the erroneous self-loop.

<img src="img\HoleGame_NoLoop.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Hole-Filling Game is completed without forming a loop</p>

So in order to prevent self-loops we need to keep a ordered list of the current state of the two groups, with the current cutPoint at the start of the list, so for example, before we make any cuts we'd have the following two groups:

    OurGroup: [CP1, 1, 2, 3, KP2]
    OtherGroup: [KP1, 4, 5, 6, CP2]

And after making the blue cut we'd have:

    blue cut's OurGroup: [1, CP1, 2, 3, KP2]
    blue cut's OtherGroup: [KP1, 4, 5, 6, CP2]

And after making the yellow cut we'd have:

    yellow cut's OurGroup: [2, CP1, 1, 3, KP2]
    yellow cut's OtherGroup: [KP1, 4, 5, 6, CP2]

We can easily see that as long as our cut is oriented such that the new CutPoint (in yellow that would be Point 2 at index 2 of the blue cut's grouping) is earlier in the list than it's matched neighbor (in yellow that would be Point 3 at index 3 of the blue cut's grouping), then we cannot create a self-loop.

We now know how to prevent getting into the self-looped state, the question then is do the same rules apply for preventing the disconnected state?

Let's consider the following example:

<img src="img\HoleGame_Dis_Fixed.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Hole-Filling Game is completed by becoming disconnected and then connected again</p>

Here we see a perfectly valid solution to the hole cutting game that very well may be the shortest path from CP1 to CP2, but we had to disconnect the path in order to make it! After making the move to H_3 the graph would be disconnected, and after making the move to H_4, the graph would be connected again. There is no way to rearrange the ordering of H_3 and H_4 so that the path does become disconnected at some point. So unlike the self-loops where the ordering of the cuts means that we don't have to worry about exploring them, we do have to explore the space of disconnected cut matches.

This means that to arrive any any point there are four possible states:

1. the previous neighbor was matched to and the path is connected
2. the previous neighbor was matched to and the path is disconnected
3. the next neighbor was matched to and the path is connected
4. the next neighbor was matched to and the path is disconnected

The next question is how do we know when we've moved from connected to disconnected and visa versa.

Well as a primer let's see what all of the hole moves we can make from CP1 would transfer our state to. We will mark holes that are connected in blue and all of the ones that are disconnected as yellow.

<img src="img\HoleGame_ConnectionMap.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>

If we consider the holes around Point 5, let's look at how OurGroup and the OtherGroup would be effected by the disconnected hole versus the connected hole:

Start:

    OurGroup: [CP1, 1, 2, 3, KP2]
    OtherGroup: [KP1, 4, 5, 6, CP2]

e.g. 1: Cut Segment[4:5] and Match Segment[CP1:5], new CutPoint is Point 4 and is Connected:

    OurGroup: [4, KP1]
    OtherGroup: [CP2, 6, 5, CP1, 1, 2, 3, KP2]

e.g. 2: Cut Segment[6:5] and Match Segment[CP1:5], new CutPoint is Point 6 and is Disconnected:

    OurGroup: [6, CP2]
    OtherGroup: [KP1, 4, 5, CP1, 1, 2, 3, KP2]

A preliminary rule might be the following: If we are matching to the other group, check whether the matched neighbor point (5 in the above examples), is between the KnotPoint (KP2) and the new CutPoint (4 or 6), if it is between them (like in e.g. 2), and we are coming from a connected state (Start), we must go to a disconnected state.

Now lets go to one of the disconnected holes (shown in pink) and see where we can reconnect it (shown in blue).

<img src="img\HoleGame_ConnectionMap2.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>

So it seems that if we move to any other hole in the OtherGroup from our disconnected state, then we can only reconnect the path, regardless of the orientation of the hole. Also notice any moves to holes in OurGroup from the disconnected state will remain disconnected.

Start:

    OurGroup: [5, 6, CP2]
    OtherGroup: [KP1, 4, CP1, 1, 2, 3, KP2]

e.g. 1: Cut Segment[1:2] and Match Segment[2:5], new CutPoint is Point 1 and is Connected:

    OurGroup: [1, CP1, 4, KP1]
    OtherGroup: [CP2, 6, 5, 2, 3, KP2]

e.g. 2: Cut Segment[2:3] and Match Segment[2:5], new CutPoint is Point 3 and is Connected:

    OurGroup: [3, KP2]
    OtherGroup: [KP1, 4, CP1, 1, 2, 5, 6, CP2]

A good question would be: do we need to keep track of how the groups were changed as we move the hole through the graph or is there a simpler way to encapsulate this information?

Put more simply: do the states that we can match to from the current CutPoint depend on how we got to the CutPoint? I think the answer is yes and here is an example to show why:

<img src="img\HoleGame_ConnectionMap6.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Connection order: CP1->5 | 6->1 | 2->4 or 2->6 </p>

If we have arrived at Point 2 with it's neighbor Point 1 being matched to, and we choose to connect from 2 to 4 (green line disregard the purple one) in the figure above, we'd have the following Groups:

Start:

    OurGroup: [2, 3, KP2]
    OtherGroup: [CP2, 6, 1, CP1, 5, 4, KP1]

e.g. 1 in green: Cut Segment[4:5] and Match Segment[2:4], new CutPoint is 5 and is Disconnected:

    OurGroup: [CP2, 6, 1, CP1, 5]
    OtherGroup: [KP1, 4, 2, 3, KP2]

If instead we chose to connect from 2 to 6 (purple line disregard the green one) then we have a completely different set of groupings:

e.g. 2 in purple: Cut Segment[CP2:6] and Match Segment[2:6], new CutPoint is CP2 and is Disconnected:

    OurGroup: [CP2]
    OtherGroup: [KP1, 4, 5, CP1, 1, 6, 2, 3, KP2]

This radical swing which points would allow us to reconnect precludes any simple answers (for example, you could think of a winding number of the hole, or only connect from counter clockwise to clockwise holes, etc.) that might allow us to not have to keep track of the path we arrived on. Why is this important? Well, it means that unless there is some simple way to determine what segments we can match to, then the path's options of where to connect to are limited by the previous connections in the path. This means that something like Floyd-Warshall, where we are trying to compute all possible point to point shortest paths could not work given the current rules of the Hole-Filling Game, so we must re-calculate our modified Dijkstra's shortest path for every pair of Cut Segments in the manifold and every assignment, aside from parity, of KnotPoints and CutPoints applied to the Cut Segments.

The rules that allow us to maintain good state transitions are as follows:

1. Don't move to the Disconnected State when connecting to our goal Hole(formed by the Segment[KP2:CP2] with the new CutPoint being KP2 and the matched neighbor being CP2)

2. If the matched neighbor is in our group we cannot move from Connected to disconnected or visa versa

3. If the matched neighbor is in the other group and the source point is disconnected, we must become Connected

4. If the matched neighbor is in the other group and is between the new CutPoint and the KnotPoint and the source point is Connected, we must become Disconnected

5. If the matched neighbor is in the other group and is not between the new CutPoint and the KnotPoint and the source point is Connected, we must remain Connected

### All I Have are Negative Thoughts

Now that we know our way around the problem of multiple cycles and self-loops, we have to face a larger and harder to reason about problem, most Shortest Path Algorithms, like Dijkstra's, do not work for graphs with negative weights. Since at every move we are cutting a segment and adding a segment; some of our costs for moving the hole will have a negative value, this happens any time that the Cut Segment is longer than the Match Segment. So what can we do about this? Well we have a few options, and none of them are particularly attractive given that our ability to move to a specific point in a specific state is path dependent. Our options are as follows:

1. Implement backtracking in Dijkstra's so that if we ever find a shorter input to a settled point, remove it from the settled list (what I went with since it is easy and supports the path dependent nature of the problem), since we are in a fully connected graph this should give us the shortest path. However we will have to store at each Point, the path we used to get there. This is because if we add backtracking we can no longer reconstruct the shortest path just by going from the end Point to the start Point in our path representation. I am not sure wether this will always give the best path, but I have not seen it fail.

2. Use Johnson's Algorithm (Bellman-Ford with a zero distance Wormhole Point) to remake the graph as a positive weight graph without considering the path dependence and then run our modified Dijkstra's now taking into account the path dependence and self-loop rules to form a valid path. This could probably work, I think you'd only need to do this once per knot manifold and seems to have better time and accuracy guarantees than our Dijkstra's with backtracking. However there is a problem with this as Johnson's Algorithm does not support negative weight cycles (a cycle whose weights sum to a negative number), so we'd have to prove that we cannot make a negative weight cycle. We'd also have to figure out how we can traverse this graph, like for example one rule would be a Point in one state cannot tavel to itself in another state, but are there other rules that make path dependence?

3. Use Bellman-Ford directly for each shortest path, (based on preliminary testing, doesn't seem to finish or finished with multiple cycles, didn't figure out why, likely because of path dependence?)

4. Replicate Negative-Weight Single-Source Shortest Paths in Near-linear Time paper (see [Links #3](#links)) (seems like it would be fast, not sure if it would work with the path dependence, they also have only provided pseudo code )

### Algorithm Speedup Potential

The algorithm described in this section is roughly a 4*N^7 operation so what are some areas we can speed it up?

1. &#9745; Remove repeated segment pairs from the main loop (2x speedup)
2. &#9744; Add worker pool for every dijkstra's call we make (algorithm is somewhat embarrassingly parallel)
3. &#9744; Remove error handling
4. &#9744; Figure out how to turn into positive weight graph so we can disregard all settled Points.
5. &#9744; Find some way to calculate all shortest paths for a manifold at once instead of in series (seems unlikely given path dependence)
6. &#9744; For less accuracy dependent problems could use heuristic of distance to KnotPoints from externals plus distance between CutPoints as best measure of where to calculate internal structure changing from N^7 to N^3 operation.

Expanding on #6 we can throw out a bunch of the cutSegment pairs based on the following idea:

the maximum distance that a specific cut segment pair could have would be:

maxDist(S1[KP1:CP1], S2[KP2:CP2], EX1, EX2) = Seg[KP1:EX1] + Seg[KP2:EX2] - S1 - S2 + Seg[CP1:CP2]

assuming that the graph is metric and the minimum distance would be

minDist(S3[KP3:CP3], S4[KP4:CP4], EX1, EX2) = Seg[KP3:EX3] + Seg[KP4:EX4] - S3 - S4 - Seg[CP3:CP4]

so if minDist(S3, S4, EX1, EX2) > maxDist(S1, S2, EX1, EX2)
where S1,S2 minmizes maxDist over the manifold, then we know that we cannot choose S3, S4 as our cut Segments
and therefore do not have to calculate the internals.

This would only work for segment pairs that start in the connected state however,
for disconnected I could imagine a similar test where you pick the simplest matching scheme possible that must exist:
if KP2's neighbor is KPN2
and KP1's Neighbor is KPN1

CP1 -> KP2 | KPN2 -> CP2
or
CP1 -> KPN1 | KP1 -> CP2
and choose the min

maxDist(S1, S2, EX1, EX2) = Seg[KP1:EX1] + Seg[KP2:EX2] - S1 - S2 + Min(Seg[CP1:KP2] + Seg[CP2:KPN2] - Seg[KP2:KPN2], Seg[CP2:KP1] + Seg[CP1:KPN1] - Seg[KP1:KPN1])

and minDist(S3, S4, EX1, EX2) = Seg[KP3:EX1] + Seg[KP4:EX2] - S3 - S4 + Min(- Seg[CP3:KP4] - Seg[CP4:KPN4] - Seg[KP4:KPN4], - Seg[CP4:KP3] - Seg[CP3:KPN3] - Seg[KP3:KPN3])

is this true^^?

So the maxDist for a disconnected segment pair

and for the segment overlap's we know the distance outright and maxDist = minDist

maxDist(S1, S1, EX1, EX2) =  Seg[KP1:EX1] + Seg[KP2:EX2] - S1 = minDist (S1, S1, EX1, EX2)

given the setup of our data structure the minDist ans

The other thing that seems to really help: see [PerfTesting](.\PerfTesting-Ixdar.csv)

is the weird fact that path length (in number of CutMatches) seems to scale with the number of Knots that CutPoint1 has to travel through in order to get to CutPoint2

it is also true, at least in the data set that I have assembled, that the path length cannot exceed the number of internal knots contained in the knot manifold.

I am unsure of how you would divine this number, but 10_rings wen from : 9,772,481,590 comparisons to 28,948,904 comparisions (300x FASTER!) by limiting the path search length to 1, this means all paths longer than 1 are disregarded, when considering circles or flat knots this seems obvious, if you have any circle with two externals on either side of it then the optimal path must connect the two CutPoints directly:

<img src="img\snap158.png" alt="circle screenshot"  width="50%" style="max-width: 500px; display: block;margin-left: auto;margin-right: auto; padding: 20px"/>
<p style="text-align:center"> Circle with external Wormhole in the top Left corner connecting 5 and 0 (circle_in_5_arc)</p>

## Links

1. Look at Section 4 Minimum Bounding Circle By Megiddo:

* <https://epubs.siam.org/doi/pdf/10.1137/0212052>

2. Match Twice and Stitch Algorithm:

* <https://vlsicad.ucsd.edu/Publications/Journals/j67.pdf>

3. Negative-Weight Single-Source Shortest Paths in Near-linear Time

* <https://arxiv.org/pdf/2203.03456>

4. Waterloo TSP Dataset:

* <http://www.math.uwaterloo.ca/tsp/world/countries.html#LU>
