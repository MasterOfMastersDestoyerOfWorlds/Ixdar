# Project Gordian
## Contents
[Preface: A Short Trip Through Time and Space](#preface)

[Chapter 1: Traveling Whatman?](#chapter-1)

[Chapter 2: Mountains of Madness](#chapter-2)

[Chapter 3: Mapping the Gordian Knot](#chapter-3)

[Chapter 4: The Sword of Iskandar](#chapter-4)

[Links and References](#links)

[Appendix A: Djibouti Sorted Segments Lists](#appendix-a)




##  Preface
## A Short Trip Through Time and Space

Let's say that we are aliens in some far off star system on the other side of the Milky Way and we want to visit Saturn's Moon Titan. With our current rocket technology it will take us a year to travel to Titan, but, given the way our rocket works, we can't course correct along the way. We need to point to where Titan will be a year from now and be accurate enough to hit the mark when we launch from our home planet. 

So how do we figure out where Titan will be? After all, every piece of matter in the universe pulls on each other through gravity. You could imagine listing every planetary body, star, and object with mass in the galaxy, along with their current positions, masses, and current trajectories in a giant computer and calculating how each one will pull on Titan and then if you knew the proper gravitational laws, you could calculate which way Titan would travel for the next timestep and how quickly. But, there is an obvious problem with this strategy, just like all of galaxy is pulling on Titan, it also pulls on itself. Very quickly the positions and trajectories of each celestial body would diverge from reality as each piece of the galaxy is pushed and pulled by every other piece. So you'd have to run the physics of the galaxy faster than the galaxy can produce it,updating each planet and star's position in tandem.

As you have been reading this, I'm sure you've been saying to yourself: "Only an idiot would do it this way!" and you'd be correct, what grade-schooler hasn't heard of an orbit? With the combined knowledge of Newton, Kepler and Einstein you could figure out Titan's future position to any arbitrary level of precision with a series of reference frames ascending from the lunar to the galactic. First you might figure out where Titan would be in its orbit relative to Saturn one year from now, then Saturn's location relative to the Sun, and then the Sun's location relative to its local group and etcetera, etcetera, until you are figuring out where our spiral arm would be relative to the galactic center. This calculation would still be pretty hard, but nowhere near as hard as figuring out how every celestial body in the galaxy is pulling on every other one!

Notice what we did: we started with an abstraction of a small moon orbiting a large planet, and as we built up the terrain of space we quickly ascended to the galactic scale. I would argue that much like the first attempt I described failed to build abstractions to help in the calculation, our current understanding of the Traveling Salesman Problem (TSP) also fails and in a similar way. The abstraction of "the orbit" has not yet been illuminated for TSP, and some think that such an abstraction is impossible, but I believe, if you look close enough, the truth is far simpler than many would have you believe.

## Chapter 1
## Traveling Whatman?

A long time ago in the pre-internet, there used to be a whole army of american men who would go door to door and try selling useless junk to unsuspecting housewives (think vacuums and knives and other infomercial garbage,... but in your home!). These salesmen had a problem, they wanted to visit every house in their area to maximize the junk they sold, but they also wanted travel the least distance possible in order to minimize the amount of money they spent on gas. (You can already tell that given the framing of the problem, this problem has been befuddling computer scientists and mathematicians for a long time). 

"So what? Hasn't my Google Maps app solved this? Simply draw a straight line from A to B and try to follow that line on the roads as close as possible!" 

You'd be right if what we were talking about was point to point travel, an algorithm like A* or Dijkstra's has pretty much solved that question. However, the important piece that makes TSP different is we MUST visit every house in our list, in the most optimal order. So instead of looking at all possible paths from A to B through any of the other points C to Z, we are instead looking at all possible paths from A to B to C ... Z in the most optimal ordering of those points. This explodes the problem space to exponential proportions.

### A more precise way of stating the problem would be: 

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
------
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

1.   Get all of the Virtual Points we haven't visited and run the continue from Knot Finding Loop #1
2.   The new list of unvisited points is the returned knotList
3.   If there is only one Virtual Point left, finish, otherwise continue from #1 

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

I am not sure why but this seems to be a common failure case, so we need to do something to address it (usually cause both of the super knot's matches point to the same knot). I think if we built the algorithm to be more flat (without Runs) it would not be the case.

Ideally we would be able to have the knot surrounded incorrect context so insert one of them into the other:

    Knot[32 33 Knot[Knot[35 36 37 ] 34 ] 0 1 ] 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]

    or

    Knot[Knot[35 36 37 ] 34 ] 0 1 Knot[32 33 2 3 4 5 6 7 8 9 10 Knot[11 12 13 ] ]] 

---
    I think the recursive insertion process should be that if you have a knot that only points to another knot then insert and keep going down levels till you are at the base knot
  
---

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

* Find the next Knot in the list
* Find its external matches
* Figure out what segment to cut based on the externals
* Add the Knot's points into the list 
* Repeat until we don't have any Knots left in the list
  
There are a few edge cases around how to figure out the cut segment, but for the most part this will work.

Our cutting algorithm will not work on a nested knot, so if the knot we are trying to cut has a height greater than 1 we will need to recursively cut it internally.

### Cutting Loop

Once we have the two externals and the flat knot <b>K</b> we are trying to cut, we will enter the cutting loop. The idea is we will have a doubly nested loop that iterates over the segments of <b>K</b> making for a N<sup>3</sup> operation. 

If the two cut segments overlap fully (i.e. Segment [a:b] and Segment [b:a] are the cuts) then we will calculate the Agree case (seen below) and if it is a smaller loop than our minimum, replace it. so if we had 

... <-> a <-> b <-> ... 

with two external points ex1 and ex2, then we could have the following path

... <-> a <-> ex1 <-> ... <-> ex2 <-> b <-> ... 

If the two cut segments overlap partially (i.e. Segment [a:b] and Segment [b:c] are the cut segments) then ignore this pair as it would leave one point orphaned (unconnected  to the final path).

If the two cut segments are disjoint (i.e. Segment [a:b] and Segment [c:d] are the cut segments) then we need to figure out which will connect to the externals and which will we attach internally, so if we had 

... <-> a <-> b <-> ... <-> c <-> d <-> ...

with two external points ex1 and ex2, then we could have the following paths

... <-> a <-> ex1 <-> ... <-> ex2 <-> c <-> ... <-> b <-> d <-> ...

or

... <-> a <-> c <-> ... <-> b <-> ex1 <-> ... <-> ex2 <-> d <-> ...

once we have calculated all of the possible distance changes we take the smallest one and apply it.

### Complexity Limit

Immediately I can see a few questions with this approach:

Q1.  Why do we only need to look at a maximum of two cut segments? For example in the Match Twice and Stitch Algorithm they have many cut segments that they apply.

A1. This really comes down to how we are defining and finding our knots. Remember that a Knot is not any cycle in the graph, even though it is a cycle. A Knot is defined as a subset of the graph that only want to match with itself and a maximum of two external VirtualPoints. Given this definition, if we find our knots correctly, then this cutting algorithm will produce the optimal result.

Q2. Do we really need to look at all of the cut segments, shouldn't we just be able to look at the ones that are closest to the external VirtualPoints?

A2. While such an approach can work for simple cases  that lie in the plane, it does not work in general and the algorithm i've outlined is really the best we can do. Take as an example a circle lying in the plane with a center point at (0,0) and with VirtualPoints distributed on the path of the circle with uneven spacing, lets call this Knot C for circle. Then arrange 3 VirtualPoints far above the circle in a line where their x and y coordinates are 0,0 and they only differ by their z coordinate and let's call this Knot L for line. since the distance from Knot L to any point on Knot C is constant, we must look at every cut segment in  Knot C to find out which will be in hte correct ordering.

Ok so now we know we must at least look at all of the segments once, but why this business with two cut segments?

Well, let's now mirror Knot L across the XY Plane so that we have Knot L1 above it and Knot L2 below it. now if we want to cut Knot K we must also consider all of the disjoint segments that would have to match across the circle internally as well.

Q3. How do we get a Big O of N<sup>3</sup>?

A3. In the Worst Case there would be  N-4 Knots to cut, and on average I have seen most sets with roughly N/3 Knots. So if we have O(N) Knots, and we are looking at all cut segment pairs in each Knot with time O(N<sup>2</sup>), then O(N)*O(N<sup>2</sup>) = O(N<sup>3</sup>)





## Links

Look at Section 4 Minimum Bounding Circle By Megiddo: 

- https://epubs.siam.org/doi/pdf/10.1137/0212052

Match Twice and Stitch Algorithm:

 - https://vlsicad.ucsd.edu/Publications/Journals/j67.pdf

Waterloo TSP Dataset:

- http://www.math.uwaterloo.ca/tsp/world/countries.html#LU

## Appendix A

## Djibouti Sorted Segment List

    0  [Segment[1 : 0], Segment[34 : 0], Segment[35 : 0], Segment[36 : 0], Segment[37 : 0], Segment[2 : 0], Segment[3 : 0], Segment[4 : 0], Segment[33 : 0], Segment[32 : 0], Segment[6 : 0], Segment[5 : 0], Segment[26 : 0], Segment[7 : 0], Segment[24 : 0], Segment[27 : 0], Segment[25 : 0], Segment[31 : 0], Segment[8 : 0], Segment[30 : 0], Segment[28 : 0], Segment[29 : 0], Segment[23 : 0], Segment[20 : 0], Segment[21 : 0], Segment[22 : 0], Segment[9 : 0], Segment[18 : 0], Segment[15 : 0], Segment[0 : 17], Segment[12 : 0], Segment[16 : 0], Segment[11 : 0], Segment[14 : 0], Segment[19 : 0], Segment[10 : 0], Segment[13 : 0]]

    1  [Segment[1 : 0], Segment[34 : 1], Segment[35 : 1], Segment[36 : 1], Segment[37 : 1], Segment[2 : 1], Segment[3 : 1], Segment[4 : 1], Segment[33 : 1], Segment[32 : 1], Segment[5 : 1], Segment[6 : 1], Segment[26 : 1], Segment[27 : 1], Segment[7 : 1], Segment[24 : 1], Segment[25 : 1], Segment[31 : 1], Segment[8 : 1], Segment[30 : 1], Segment[28 : 1], Segment[29 : 1], Segment[23 : 1], Segment[20 : 1], Segment[21 : 1], Segment[9 : 1], Segment[22 : 1], Segment[15 : 1], Segment[18 : 1], Segment[1 : 17], Segment[12 : 1], Segment[16 : 1], Segment[11 : 1], Segment[14 : 1], Segment[19 : 1], Segment[10 : 1], Segment[13 : 1]]

    2  [Segment[2 : 3], Segment[4 : 2], Segment[2 : 33], Segment[2 : 32], Segment[2 : 5], Segment[2 : 1], Segment[2 : 34], Segment[2 : 35], Segment[36 : 2], Segment[2 : 37], Segment[6 : 2], Segment[2 : 0], Segment[2 : 7], Segment[2 : 27], Segment[26 : 2], Segment[8 : 2], Segment[2 : 31], Segment[30 : 2], Segment[2 : 28], Segment[29 : 2], Segment[2 : 25], Segment[2 : 24], Segment[9 : 2], Segment[2 : 15], Segment[2 : 12], Segment[23 : 2], Segment[11 : 2], Segment[20 : 2], Segment[21 : 2], Segment[10 : 2], Segment[2 : 17], Segment[2 : 18], Segment[22 : 2], Segment[14 : 2], Segment[16 : 2], Segment[2 : 13], Segment[2 : 19]]

    3  [Segment[2 : 3], Segment[4 : 3], Segment[33 : 3], Segment[5 : 3], Segment[32 : 3], Segment[3 : 1], Segment[34 : 3], Segment[35 : 3], Segment[6 : 3], Segment[36 : 3], Segment[37 : 3], Segment[3 : 0], Segment[7 : 3], Segment[27 : 3], Segment[8 : 3], Segment[26 : 3], Segment[31 : 3], Segment[30 : 3], Segment[28 : 3], Segment[29 : 3], Segment[25 : 3], Segment[24 : 3], Segment[9 : 3], Segment[12 : 3], Segment[15 : 3], Segment[11 : 3], Segment[23 : 3], Segment[20 : 3], Segment[21 : 3], Segment[10 : 3], Segment[3 : 17], Segment[18 : 3], Segment[14 : 3], Segment[22 : 3], Segment[16 : 3], Segment[13 : 3], Segment[19 : 3], Segment[3 : 38]]

    4  s  [Segment[4 : 5], Segment[4 : 3], Segment[4 : 6], Segment[4 : 2], Segment[4 : 7], Segment[4 : 33], Segment[4 : 32], Segment[4 : 8], Segment[4 : 1], Segment[4 : 34], Segment[4 : 35], Segment[4 : 0], Segment[4 : 36], Segment[4 : 37], Segment[4 : 31], Segment[4 : 27], Segment[26 : 4], Segment[30 : 4], Segment[4 : 28], Segment[4 : 9], Segment[29 : 4], Segment[4 : 25], Segment[4 : 24], Segment[4 : 12], Segment[4 : 11], Segment[4 : 10], Segment[4 : 15], Segment[4 : 14], Segment[4 : 23], Segment[4 : 20], Segment[4 : 21], Segment[4 : 17], Segment[16 : 4], Segment[4 : 18], Segment[4 : 22], Segment[4 : 13], Segment[4 : 19]]

    5  [Segment[4 : 5], Segment[6 : 5], Segment[5 : 7], Segment[5 : 3], Segment[2 : 5], Segment[8 : 5], Segment[5 : 33], Segment[5 : 32], Segment[5 : 1], Segment[5 : 34], Segment[35 : 5], Segment[36 : 5], Segment[5 : 37], Segment[31 : 5], Segment[5 : 0], Segment[5 : 27], Segment[26 : 5], Segment[30 : 5], Segment[9 : 5], Segment[5 : 28], Segment[29 : 5], Segment[5 : 25], Segment[5 : 12], Segment[24 : 5], Segment[10 : 5], Segment[11 : 5], Segment[5 : 15], Segment[14 : 5], Segment[5 : 17], Segment[16 : 5], Segment[13 : 5], Segment[20 : 5], Segment[23 : 5], Segment[21 : 5], Segment[5 : 18], Segment[22 : 5], Segment[5 : 19]]

    6  [Segment[6 : 7], Segment[4 : 6], Segment[6 : 5], Segment[8 : 6], Segment[6 : 3], Segment[6 : 2], Segment[6 : 33], Segment[6 : 32], Segment[6 : 1], Segment[6 : 0], Segment[6 : 34], Segment[6 : 35], Segment[6 : 36], Segment[6 : 37], Segment[6 : 31], Segment[6 : 9], Segment[6 : 27], Segment[26 : 6], Segment[30 : 6], Segment[6 : 28], Segment[29 : 6], Segment[6 : 25], Segment[6 : 10], Segment[6 : 24], Segment[6 : 12], Segment[6 : 11], Segment[6 : 15], Segment[6 : 14], Segment[23 : 6], Segment[20 : 6], Segment[6 : 21], Segment[6 : 17], Segment[6 : 13], Segment[16 : 6], Segment[6 : 18], Segment[6 : 22], Segment[6 : 19]]

    7  [Segment[6 : 7], Segment[5 : 7], Segment[8 : 7], Segment[4 : 7], Segment[7 : 3], Segment[2 : 7], Segment[7 : 33], Segment[7 : 32], Segment[7 : 1], Segment[7 : 0], Segment[7 : 34], Segment[9 : 7], Segment[35 : 7], Segment[36 : 7], Segment[7 : 37], Segment[31 : 7], Segment[7 : 27], Segment[26 : 7], Segment[30 : 7], Segment[7 : 28], Segment[29 : 7], Segment[10 : 7], Segment[7 : 25], Segment[7 : 12], Segment[11 : 7], Segment[24 : 7], Segment[7 : 15], Segment[14 : 7], Segment[13 : 7], Segment[23 : 7], Segment[16 : 7], Segment[7 : 17], Segment[20 : 7], Segment[21 : 7], Segment[7 : 18], Segment[22 : 7], Segment[7 : 19]]

    8  [Segment[8 : 7], Segment[8 : 5], Segment[8 : 6], Segment[4 : 8], Segment[8 : 9], Segment[8 : 33], Segment[8 : 3], Segment[8 : 32], Segment[8 : 2], Segment[8 : 31], Segment[8 : 1], Segment[8 : 34], Segment[8 : 27], Segment[8 : 35], Segment[30 : 8], Segment[8 : 36], Segment[8 : 0], Segment[8 : 37], Segment[8 : 10], Segment[26 : 8], Segment[8 : 28], Segment[29 : 8], Segment[8 : 12], Segment[8 : 11], Segment[8 : 25], Segment[8 : 24], Segment[8 : 15], Segment[8 : 13], Segment[8 : 14], Segment[16 : 8], Segment[8 : 17], Segment[8 : 18], Segment[20 : 8], Segment[8 : 21], Segment[23 : 8], Segment[8 : 22], Segment[8 : 19]]

    9  [Segment[9 : 10], Segment[8 : 9], Segment[9 : 11], Segment[9 : 12], Segment[9 : 7], Segment[9 : 5], Segment[6 : 9], Segment[4 : 9], Segment[9 : 33], Segment[9 : 32], Segment[9 : 31], Segment[9 : 3], Segment[9 : 2], Segment[30 : 9], Segment[9 : 28], Segment[9 : 27], Segment[29 : 9], Segment[9 : 13], Segment[26 : 9], Segment[9 : 34], Segment[9 : 1], Segment[9 : 35], Segment[9 : 36], Segment[9 : 37], Segment[9 : 0], Segment[9 : 14], Segment[9 : 15], Segment[9 : 25], Segment[16 : 9], Segment[9 : 24], Segment[9 : 17], Segment[9 : 18], Segment[20 : 9], Segment[9 : 21], Segment[23 : 9], Segment[22 : 9], Segment[9 : 19]]

    10  [Segment[9 : 10], Segment[11 : 10], Segment[10 : 12], Segment[8 : 10], Segment[10 : 13], Segment[10 : 7], Segment[10 : 5], Segment[6 : 10], Segment[4 : 10], Segment[10 : 31], Segment[10 : 32], Segment[10 : 33], Segment[30 : 10], Segment[10 : 3], Segment[10 : 2], Segment[10 : 28], Segment[29 : 10], Segment[10 : 27], Segment[14 : 10], Segment[26 : 10], Segment[10 : 34], Segment[10 : 1], Segment[10 : 35], Segment[10 : 36], Segment[10 : 37], Segment[10 : 15], Segment[10 : 0], Segment[16 : 10], Segment[10 : 25], Segment[10 : 17], Segment[10 : 24], Segment[10 : 18], Segment[20 : 10], Segment[10 : 21], Segment[23 : 10], Segment[22 : 10], Segment[10 : 19]]

    11  [Segment[11 : 12], Segment[11 : 13], Segment[11 : 10], Segment[9 : 11], Segment[11 : 14], Segment[11 : 31], Segment[30 : 11], Segment[8 : 11], Segment[11 : 28], Segment[29 : 11], Segment[11 : 32], Segment[11 : 33], Segment[11 : 5], Segment[11 : 27], Segment[11 : 15], Segment[4 : 11], Segment[11 : 7], Segment[26 : 11], Segment[6 : 11], Segment[11 : 3], Segment[16 : 11], Segment[11 : 2], Segment[11 : 34], Segment[11 : 36], Segment[11 : 35], Segment[11 : 37], Segment[11 : 25], Segment[11 : 1], Segment[11 : 17], Segment[11 : 0], Segment[11 : 18], Segment[11 : 24], Segment[20 : 11], Segment[11 : 21], Segment[11 : 19], Segment[23 : 11], Segment[22 : 11]]

    12  [Segment[11 : 12], Segment[13 : 12], Segment[9 : 12], Segment[10 : 12], Segment[14 : 12], Segment[30 : 12], Segment[31 : 12], Segment[12 : 28], Segment[29 : 12], Segment[8 : 12], Segment[12 : 32], Segment[12 : 15], Segment[12 : 27], Segment[12 : 33], Segment[5 : 12], Segment[26 : 12], Segment[4 : 12], Segment[16 : 12], Segment[7 : 12], Segment[12 : 3], Segment[6 : 12], Segment[2 : 12], Segment[12 : 25], Segment[34 : 12], Segment[36 : 12], Segment[35 : 12], Segment[12 : 37], Segment[12 : 17], Segment[12 : 1], Segment[12 : 0], Segment[12 : 18], Segment[24 : 12], Segment[20 : 12], Segment[21 : 12], Segment[19 : 12], Segment[23 : 12], Segment[22 : 12]]

    13  [Segment[13 : 12], Segment[11 : 13], Segment[14 : 13], Segment[10 : 13], Segment[13 : 15], Segment[9 : 13], Segment[30 : 13], Segment[29 : 13], Segment[16 : 13], Segment[13 : 28], Segment[13 : 31], Segment[13 : 27], Segment[13 : 32], Segment[8 : 13], Segment[13 : 33], Segment[26 : 13], Segment[13 : 17], Segment[13 : 5], Segment[4 : 13], Segment[13 : 3], Segment[2 : 13], Segment[13 : 7], Segment[13 : 25], Segment[6 : 13], Segment[13 : 18], Segment[13 : 34], Segment[36 : 13], Segment[13 : 37], Segment[13 : 35], Segment[13 : 1], Segment[13 : 0], Segment[20 : 13], Segment[13 : 19], Segment[13 : 24], Segment[21 : 13], Segment[23 : 13], Segment[22 : 13]]
    
    14  [Segment[14 : 15], Segment[16 : 14], Segment[14 : 13], Segment[29 : 14], Segment[14 : 28], Segment[30 : 14], Segment[14 : 12], Segment[14 : 17], Segment[14 : 31], Segment[11 : 14], Segment[14 : 27], Segment[26 : 14], Segment[14 : 18], Segment[14 : 25], Segment[14 : 32], Segment[14 : 33], Segment[9 : 14], Segment[14 : 19], Segment[14 : 5], Segment[20 : 14], Segment[14 : 3], Segment[14 : 2], Segment[14 : 21], Segment[8 : 14], Segment[4 : 14], Segment[14 : 37], Segment[14 : 10], Segment[14 : 36], Segment[14 : 34], Segment[14 : 35], Segment[14 : 24], Segment[6 : 14], Segment[14 : 1], Segment[14 : 7], Segment[23 : 14], Segment[22 : 14], Segment[14 : 0]]

    15  [Segment[16 : 15], Segment[14 : 15], Segment[15 : 17], Segment[29 : 15], Segment[28 : 15], Segment[30 : 15], Segment[18 : 15], Segment[31 : 15], Segment[27 : 15], Segment[26 : 15], Segment[25 : 15], Segment[19 : 15], Segment[20 : 15], Segment[32 : 15], Segment[12 : 15], Segment[21 : 15], Segment[33 : 15], Segment[13 : 15], Segment[11 : 15], Segment[24 : 15], Segment[37 : 15], Segment[36 : 15], Segment[34 : 15], Segment[35 : 15], Segment[23 : 15], Segment[22 : 15], Segment[15 : 3], Segment[2 : 15], Segment[5 : 15], Segment[4 : 15], Segment[15 : 1], Segment[8 : 15], Segment[15 : 0], Segment[9 : 15], Segment[6 : 15], Segment[7 : 15], Segment[10 : 15]]

    16  [Segment[16 : 15], Segment[16 : 17], Segment[16 : 14], Segment[16 : 18], Segment[29 : 16], Segment[16 : 28], Segment[30 : 16], Segment[16 : 19], Segment[16 : 31], Segment[16 : 27], Segment[16 : 25], Segment[16 : 26], Segment[16 : 20], Segment[16 : 21], Segment[16 : 13], Segment[16 : 12], Segment[16 : 32], Segment[16 : 33], Segment[16 : 22], Segment[16 : 24], Segment[16 : 23], Segment[16 : 11], Segment[16 : 37], Segment[16 : 36], Segment[16 : 35], Segment[16 : 34], Segment[16 : 3], Segment[16 : 2], Segment[16 : 5], Segment[16 : 4], Segment[16 : 1], Segment[16 : 0], Segment[16 : 8], Segment[16 : 9], Segment[16 : 6], Segment[16 : 7], Segment[16 : 10]]

    17  [Segment[18 : 17], Segment[16 : 17], Segment[15 : 17], Segment[19 : 17], Segment[20 : 17], Segment[21 : 17], Segment[25 : 17], Segment[29 : 17], Segment[28 : 17], Segment[14 : 17], Segment[30 : 17], Segment[26 : 17], Segment[22 : 17], Segment[27 : 17], Segment[23 : 17], Segment[31 : 17], Segment[24 : 17], Segment[32 : 17], Segment[33 : 17], Segment[37 : 17], Segment[36 : 17], Segment[35 : 17], Segment[34 : 17], Segment[2 : 17], Segment[3 : 17], Segment[1 : 17], Segment[12 : 17], Segment[0 : 17], Segment[5 : 17], Segment[4 : 17], Segment[13 : 17], Segment[11 : 17], Segment[6 : 17], Segment[8 : 17], Segment[7 : 17], Segment[9 : 17], Segment[10 : 17]]

    18  [Segment[18 : 17], Segment[19 : 18], Segment[20 : 18], Segment[21 : 18], Segment[22 : 18], Segment[25 : 18], Segment[16 : 18], Segment[23 : 18], Segment[18 : 15], Segment[24 : 18], Segment[29 : 18], Segment[26 : 18], Segment[18 : 28], Segment[27 : 18], Segment[30 : 18], Segment[31 : 18], Segment[14 : 18], Segment[37 : 18], Segment[36 : 18], Segment[35 : 18], Segment[34 : 18], Segment[32 : 18], Segment[18 : 33], Segment[18 : 1], Segment[2 : 18], Segment[18 : 3], Segment[18 : 0], Segment[4 : 18], Segment[5 : 18], Segment[12 : 18], Segment[6 : 18], Segment[13 : 18], Segment[8 : 18], Segment[7 : 18], Segment[11 : 18], Segment[9 : 18], Segment[10 : 18]]

    19  [Segment[19 : 18], Segment[20 : 19], Segment[21 : 19], Segment[19 : 17], Segment[22 : 19], Segment[23 : 19], Segment[16 : 19], Segment[19 : 25], Segment[19 : 15], Segment[24 : 19], Segment[29 : 19], Segment[26 : 19], Segment[19 : 28], Segment[19 : 27], Segment[30 : 19], Segment[14 : 19], Segment[31 : 19], Segment[19 : 37], Segment[36 : 19], Segment[35 : 19], Segment[19 : 34], Segment[19 : 32], Segment[19 : 33], Segment[19 : 1], Segment[19 : 0], Segment[2 : 19], Segment[19 : 3], Segment[4 : 19], Segment[5 : 19], Segment[19 : 12], Segment[6 : 19], Segment[13 : 19], Segment[8 : 19], Segment[7 : 19], Segment[11 : 19], Segment[9 : 19], Segment[10 : 19]]

    20  [Segment[20 : 21], Segment[20 : 22], Segment[20 : 23], Segment[20 : 18], Segment[20 : 19], Segment[20 : 25], Segment[20 : 24], Segment[20 : 17], Segment[26 : 20], Segment[20 : 27], Segment[20 : 15], Segment[29 : 20], Segment[20 : 28], Segment[16 : 20], Segment[20 : 37], Segment[20 : 36], Segment[20 : 35], Segment[30 : 20], Segment[20 : 34], Segment[20 : 31], Segment[20 : 1], Segment[20 : 32], Segment[20 : 0], Segment[20 : 33], Segment[20 : 2], Segment[20 : 3], Segment[20 : 14], Segment[4 : 20], Segment[20 : 5], Segment[20 : 6], Segment[20 : 7], Segment[20 : 8], Segment[20 : 12], Segment[20 : 11], Segment[20 : 13], Segment[20 : 9], Segment[20 : 10]]

    21  [Segment[20 : 21], Segment[22 : 21], Segment[23 : 21], Segment[21 : 18], Segment[21 : 19], Segment[21 : 24], Segment[21 : 25], Segment[21 : 17], Segment[26 : 21], Segment[21 : 27], Segment[29 : 21], Segment[21 : 15], Segment[21 : 28], Segment[16 : 21], Segment[21 : 37], Segment[36 : 21], Segment[21 : 35], Segment[21 : 34], Segment[30 : 21], Segment[21 : 31], Segment[21 : 1], Segment[21 : 0], Segment[21 : 32], Segment[21 : 33], Segment[21 : 2], Segment[21 : 3], Segment[14 : 21], Segment[4 : 21], Segment[21 : 5], Segment[6 : 21], Segment[21 : 7], Segment[8 : 21], Segment[21 : 12], Segment[11 : 21], Segment[9 : 21], Segment[21 : 13], Segment[10 : 21]]

    22  [Segment[23 : 22], Segment[22 : 21], Segment[20 : 22], Segment[22 : 24], Segment[22 : 18], Segment[22 : 19], Segment[22 : 25], Segment[22 : 17], Segment[26 : 22], Segment[22 : 37], Segment[22 : 36], Segment[22 : 27], Segment[22 : 35], Segment[22 : 34], Segment[29 : 22], Segment[22 : 28], Segment[22 : 15], Segment[16 : 22], Segment[22 : 1], Segment[30 : 22], Segment[22 : 0], Segment[22 : 31], Segment[22 : 32], Segment[22 : 33], Segment[22 : 2], Segment[22 : 3], Segment[4 : 22], Segment[22 : 14], Segment[22 : 5], Segment[6 : 22], Segment[22 : 7], Segment[8 : 22], Segment[22 : 12], Segment[22 : 11], Segment[22 : 9], Segment[22 : 13], Segment[22 : 10]]

    23  [Segment[23 : 22], Segment[23 : 21], Segment[20 : 23], Segment[23 : 24], Segment[23 : 25], Segment[23 : 18], Segment[23 : 19], Segment[23 : 17], Segment[26 : 23], Segment[23 : 37], Segment[23 : 36], Segment[23 : 35], Segment[23 : 34], Segment[23 : 27], Segment[23 : 1], Segment[29 : 23], Segment[23 : 28], Segment[23 : 0], Segment[30 : 23], Segment[23 : 15], Segment[23 : 31], Segment[23 : 32], Segment[16 : 23], Segment[23 : 33], Segment[23 : 2], Segment[23 : 3], Segment[4 : 23], Segment[23 : 5], Segment[23 : 6], Segment[23 : 14], Segment[23 : 7], Segment[23 : 8], Segment[23 : 12], Segment[23 : 9], Segment[23 : 11], Segment[23 : 13], Segment[23 : 10]]

    24  [Segment[24 : 25], Segment[23 : 24], Segment[24 : 37], Segment[36 : 24], Segment[35 : 24], Segment[21 : 24], Segment[20 : 24], Segment[24 : 34], Segment[22 : 24], Segment[26 : 24], Segment[24 : 1], Segment[24 : 0], Segment[24 : 27], Segment[24 : 18], Segment[2 : 24], Segment[24 : 3], Segment[24 : 32], Segment[24 : 28], Segment[24 : 33], Segment[29 : 24], Segment[31 : 24], Segment[30 : 24], Segment[24 : 17], Segment[4 : 24], Segment[24 : 19], Segment[24 : 5], Segment[24 : 15], Segment[6 : 24], Segment[16 : 24], Segment[24 : 7], Segment[8 : 24], Segment[14 : 24], Segment[9 : 24], Segment[24 : 12], Segment[11 : 24], Segment[13 : 24], Segment[10 : 24]]

    25  [Segment[26 : 25], Segment[24 : 25], Segment[27 : 25], Segment[25 : 28], Segment[29 : 25], Segment[37 : 25], Segment[36 : 25], Segment[20 : 25], Segment[35 : 25], Segment[34 : 25], Segment[21 : 25], Segment[30 : 25], Segment[31 : 25], Segment[23 : 25], Segment[25 : 18], Segment[32 : 25], Segment[25 : 33], Segment[25 : 17], Segment[25 : 1], Segment[22 : 25], Segment[2 : 25], Segment[25 : 3], Segment[25 : 0], Segment[25 : 15], Segment[4 : 25], Segment[19 : 25], Segment[16 : 25], Segment[5 : 25], Segment[6 : 25], Segment[14 : 25], Segment[7 : 25], Segment[8 : 25], Segment[12 : 25], Segment[9 : 25], Segment[11 : 25], Segment[13 : 25], Segment[10 : 25]]

    27  [Segment[26 : 27], Segment[31 : 27], Segment[27 : 28], Segment[30 : 27], Segment[29 : 27], Segment[27 : 32], Segment[27 : 33], Segment[27 : 25], Segment[27 : 37], Segment[36 : 27], Segment[34 : 27], Segment[27 : 3], Segment[35 : 27], Segment[2 : 27], Segment[5 : 27], Segment[4 : 27], Segment[27 : 1], Segment[24 : 27], Segment[27 : 15], Segment[27 : 0], Segment[6 : 27], Segment[8 : 27], Segment[7 : 27], Segment[27 : 17], Segment[16 : 27], Segment[27 : 18], Segment[14 : 27], Segment[20 : 27], Segment[21 : 27], Segment[12 : 27], Segment[23 : 27], Segment[9 : 27], Segment[11 : 27], Segment[22 : 27], Segment[19 : 27], Segment[13 : 27], Segment[10 : 27]]

    26  [Segment[26 : 27], Segment[26 : 28], Segment[26 : 31], Segment[29 : 26], Segment[30 : 26], Segment[26 : 25], Segment[26 : 32], Segment[26 : 33], Segment[26 : 37], Segment[26 : 36], Segment[26 : 34], Segment[26 : 35], Segment[26 : 2], Segment[26 : 3], Segment[26 : 1], Segment[26 : 24], Segment[26 : 4], Segment[26 : 5], Segment[26 : 0], Segment[26 : 15], Segment[26 : 6], Segment[26 : 17], Segment[26 : 20], Segment[26 : 18], Segment[26 : 7], Segment[26 : 8], Segment[26 : 21], Segment[16 : 26], Segment[26 : 23], Segment[26 : 14], Segment[26 : 22], Segment[26 : 12], Segment[26 : 9], Segment[26 : 19], Segment[26 : 11], Segment[26 : 13], Segment[26 : 10]]

    28  [Segment[29 : 28], Segment[30 : 28], Segment[31 : 28], Segment[27 : 28], Segment[26 : 28], Segment[32 : 28], Segment[33 : 28], Segment[28 : 15], Segment[25 : 28], Segment[28 : 3], Segment[2 : 28], Segment[37 : 28], Segment[36 : 28], Segment[34 : 28], Segment[14 : 28], Segment[35 : 28], Segment[16 : 28], Segment[28 : 17], Segment[5 : 28], Segment[4 : 28], Segment[28 : 1], Segment[24 : 28], Segment[18 : 28], Segment[12 : 28], Segment[8 : 28], Segment[28 : 0], Segment[6 : 28], Segment[20 : 28], Segment[7 : 28], Segment[11 : 28], Segment[21 : 28], Segment[9 : 28], Segment[23 : 28], Segment[13 : 28], Segment[22 : 28], Segment[19 : 28], Segment[10 : 28]]

    29  [Segment[29 : 28], Segment[30 : 29], Segment[29 : 31], Segment[29 : 27], Segment[29 : 26], Segment[29 : 15], Segment[29 : 32], Segment[29 : 25], Segment[29 : 33], Segment[29 : 16], Segment[29 : 14], Segment[29 : 17], Segment[29 : 3], Segment[29 : 2], Segment[29 : 37], Segment[29 : 36], Segment[29 : 34], Segment[29 : 35], Segment[29 : 5], Segment[29 : 4], Segment[29 : 18], Segment[29 : 1], Segment[29 : 24], Segment[29 : 12], Segment[29 : 8], Segment[29 : 0], Segment[29 : 20], Segment[29 : 6], Segment[29 : 11], Segment[29 : 21], Segment[29 : 7], Segment[29 : 9], Segment[29 : 23], Segment[29 : 13], Segment[29 : 19], Segment[29 : 22], Segment[29 : 10]]
    
    30  [Segment[30 : 28], Segment[30 : 29], Segment[30 : 31], Segment[30 : 27], Segment[30 : 26], Segment[30 : 32], Segment[30 : 33], Segment[30 : 15], Segment[30 : 25], Segment[30 : 3], Segment[30 : 2], Segment[30 : 5], Segment[30 : 34], Segment[30 : 36], Segment[30 : 37], Segment[30 : 35], Segment[30 : 4], Segment[30 : 14], Segment[30 : 16], Segment[30 : 12], Segment[30 : 17], Segment[30 : 1], Segment[30 : 8], Segment[30 : 6], Segment[30 : 24], Segment[30 : 0], Segment[30 : 7], Segment[30 : 11], Segment[30 : 18], Segment[30 : 9], Segment[30 : 20], Segment[30 : 21], Segment[30 : 13], Segment[30 : 23], Segment[30 : 22], Segment[30 : 19], Segment[30 : 10]]

    31  [Segment[30 : 31], Segment[31 : 28], Segment[31 : 27], Segment[29 : 31], Segment[26 : 31], Segment[31 : 32], Segment[31 : 33], Segment[31 : 3], Segment[2 : 31], Segment[31 : 5], Segment[31 : 25], Segment[4 : 31], Segment[31 : 34], Segment[36 : 31], Segment[31 : 37], Segment[35 : 31], Segment[31 : 15], Segment[8 : 31], Segment[31 : 1], Segment[6 : 31], Segment[31 : 7], Segment[31 : 12], Segment[31 : 0], Segment[14 : 31], Segment[16 : 31], Segment[9 : 31], Segment[31 : 24], Segment[11 : 31], Segment[31 : 17], Segment[31 : 18], Segment[20 : 31], Segment[21 : 31], Segment[13 : 31], Segment[23 : 31], Segment[10 : 31], Segment[22 : 31], Segment[31 : 19]]

    32  [Segment[32 : 33], Segment[32 : 3], Segment[2 : 32], Segment[5 : 32], Segment[31 : 32], Segment[4 : 32], Segment[27 : 32], Segment[26 : 32], Segment[34 : 32], Segment[30 : 32], Segment[35 : 32], Segment[36 : 32], Segment[32 : 37], Segment[32 : 28], Segment[32 : 1], Segment[6 : 32], Segment[29 : 32], Segment[8 : 32], Segment[7 : 32], Segment[32 : 0], Segment[32 : 25], Segment[9 : 32], Segment[24 : 32], Segment[32 : 15], Segment[12 : 32], Segment[11 : 32], Segment[14 : 32], Segment[32 : 17], Segment[16 : 32], Segment[20 : 32], Segment[32 : 18], Segment[10 : 32], Segment[21 : 32], Segment[23 : 32], Segment[22 : 32], Segment[13 : 32], Segment[19 : 32]]

    33  [Segment[38 : 33], Segment[32 : 33], Segment[33 : 3], Segment[2 : 33], Segment[5 : 33], Segment[4 : 33], Segment[27 : 33], Segment[31 : 33], Segment[26 : 33], Segment[34 : 33], Segment[35 : 33], Segment[36 : 33], Segment[37 : 33], Segment[6 : 33], Segment[33 : 1], Segment[30 : 33], Segment[33 : 28], Segment[7 : 33], Segment[8 : 33], Segment[29 : 33], Segment[33 : 0], Segment[25 : 33], Segment[9 : 33], Segment[24 : 33], Segment[33 : 15], Segment[12 : 33], Segment[11 : 33], Segment[14 : 33], Segment[33 : 17], Segment[16 : 33], Segment[10 : 33], Segment[20 : 33], Segment[18 : 33], Segment[21 : 33], Segment[23 : 33], Segment[22 : 33], Segment[13 : 33], Segment[19 : 33]]

    0  [Segment[1 : 0], Segment[34 : 0], Segment[35 : 0], Segment[36 : 0], Segment[37 : 0], Segment[2 : 0], Segment[3 : 0], Segment[4 : 0], Segment[33 : 0], Segment[32 : 0], Segment[6 : 0], Segment[5 : 0], Segment[26 : 0], Segment[7 : 0], Segment[24 : 0], Segment[27 : 0], Segment[25 : 0], Segment[31 : 0], Segment[8 : 0], Segment[30 : 0], Segment[28 : 0], Segment[29 : 0], Segment[23 : 0], Segment[20 : 0], Segment[21 : 0], Segment[22 : 0], Segment[9 : 0], Segment[18 : 0], Segment[15 : 0], Segment[0 : 17], Segment[12 : 0], Segment[16 : 0], Segment[11 : 0], Segment[14 : 0], Segment[19 : 0], Segment[10 : 0], Segment[13 : 0]]

    1  [Segment[1 : 0], Segment[34 : 1], Segment[35 : 1], Segment[36 : 1], Segment[37 : 1], Segment[2 : 1], Segment[3 : 1], Segment[4 : 1], Segment[33 : 1], Segment[32 : 1], Segment[5 : 1], Segment[6 : 1], Segment[26 : 1], Segment[27 : 1], Segment[7 : 1], Segment[24 : 1], Segment[25 : 1], Segment[31 : 1], Segment[8 : 1], Segment[30 : 1], Segment[28 : 1], Segment[29 : 1], Segment[23 : 1], Segment[20 : 1], Segment[21 : 1], Segment[9 : 1], Segment[22 : 1], Segment[15 : 1], Segment[18 : 1], Segment[1 : 17], Segment[12 : 1], Segment[16 : 1], Segment[11 : 1], Segment[14 : 1], Segment[19 : 1], Segment[10 : 1], Segment[13 : 1]]

    35  [Segment[36 : 35], Segment[35 : 37], Segment[35 : 34], Segment[35 : 1], Segment[35 : 0], Segment[2 : 35], Segment[35 : 3], Segment[35 : 33], Segment[35 : 32], Segment[26 : 35], Segment[4 : 35], Segment[35 : 27], Segment[35 : 24], Segment[35 : 25], Segment[35 : 5], Segment[6 : 35], Segment[35 : 31], Segment[35 : 7], Segment[30 : 35], Segment[35 : 28], Segment[29 : 35], Segment[8 : 35], Segment[23 : 35], Segment[20 : 35], Segment[21 : 35], Segment[22 : 35], Segment[35 : 18], Segment[35 : 15], Segment[35 : 17], Segment[9 : 35], Segment[16 : 35], Segment[35 : 12], Segment[14 : 35], Segment[35 : 19], Segment[11 : 35], Segment[10 : 35], Segment[13 : 35]]

    36  [Segment[36 : 37], Segment[36 : 35], Segment[36 : 34], Segment[36 : 1], Segment[36 : 0], Segment[36 : 2], Segment[36 : 3], Segment[36 : 33], Segment[36 : 32], Segment[26 : 36], Segment[4 : 36], Segment[36 : 27], Segment[36 : 24], Segment[36 : 25], Segment[36 : 5], Segment[6 : 36], Segment[36 : 31], Segment[30 : 36], Segment[36 : 28], Segment[36 : 7], Segment[29 : 36], Segment[8 : 36], Segment[23 : 36], Segment[20 : 36], Segment[36 : 21], Segment[22 : 36], Segment[36 : 18], Segment[36 : 15], Segment[36 : 17], Segment[9 : 36], Segment[16 : 36], Segment[36 : 12], Segment[14 : 36], Segment[36 : 19], Segment[11 : 36], Segment[10 : 36], Segment[36 : 13]]

    37  [Segment[36 : 37], Segment[35 : 37], Segment[34 : 37], Segment[37 : 1], Segment[37 : 0], Segment[2 : 37], Segment[37 : 3], Segment[37 : 33], Segment[32 : 37], Segment[26 : 37], Segment[4 : 37], Segment[27 : 37], Segment[24 : 37], Segment[37 : 25], Segment[5 : 37], Segment[6 : 37], Segment[31 : 37], Segment[37 : 28], Segment[30 : 37], Segment[7 : 37], Segment[29 : 37], Segment[8 : 37], Segment[23 : 37], Segment[20 : 37], Segment[21 : 37], Segment[22 : 37], Segment[37 : 18], Segment[37 : 15], Segment[37 : 17], Segment[9 : 37], Segment[16 : 37], Segment[12 : 37], Segment[14 : 37], Segment[19 : 37], Segment[11 : 37], Segment[10 : 37], Segment[13 : 37]]

