On one hand with 3d modeling software, it is really powerful but finding what you need in a nest of menus creates a tremendous amount of friction to making the thing in your head into geometry. On the other hand using bulk generators of geometry get you really far really fast, but with no real way to refine the outputs. A middle ground like Spore’s character creator is usable by the general public to create animatible characters but is limited by the library of creature parts, searching through that library of creature parts, and has no way to customize the parts that you attach to your creature.

I think the end goal is to have a system that is not entirely llm driven but could be, for example if you think about the Spore editor where there is to the left on the screen a library of creature parts and to the right of the screen is the actual creature where the user can use their mouse to move the parts around it is something like that. The thing I am thinking of is like this we ask the LLM hey generate a hand for me, the LLM breaks down the composition of what parts are needed in a hand and encodes it in mesh nodes and then in the UI the person sees the first attempt at the hand in the viewport on the left of the screen, then on the right side ofe the screen they see the tree/ diagram  of semantic pieces that made the hand, then they look at the model some more and decide you know what the thumb isn't  right, so on the tree tthey click on the thumb and that move the camera to orbit the thumb, hiding the rest of the hand by default and the tree diagram is now showing the nodes that made the thumb, there would be sliders ,curves and numbers that he user could manipulate to get something exact or if let's say there was no fingernail on the thumb  they could say we want to add a fingernail here and click on the thumb where it should go, the llm then updates the mesh nodes to try to get what they are talking about updating the dsl  along the way and asks the user if the fingernail change should be propagated to the rest of the fingers.

The main idea behind this product is that current 3D model generators, photogrammetry, NERFs, Gaussian Splats, etc. all technically do solve the problem of how to have non-speciallized people create 3d scenes, but they all have a similar set of issues:
1. Long time periods to get any result at all
2. Slot machine nature of outputs ( sometimes amazing, most of the time crap)
3. Models don’t have a coherent structure and require heavy cleanup steps to use in production
4. Only editable by an expert, which in most cases means that it is much easier to start from scratch than from a broken output.

What we could do is create a piece of LLM guided software, create a set of primitive actions like CAD has to create creatures and objects and then every mesh that we make and texture is a recipe for how to make it procedurally. We make a large library of different creatures and objects decomposed to the math it takes to create them and then we train LoRas on the DSL that creates our models to reduce costs and token efficiency. As the library grows we get more and more examples to train on and create better results, the important piece about having each actor decomposed to base parts is that to start rigging we just need to walk the tree of semantically broken down parts.

Or could use HunYuan Parts model to do something similar, though I am unimpressed by the results:
https://github.com/Tencent-Hunyuan/Hunyuan3D-Part

There is a fundamental data bottleneck in high quality 3D mesh generation that we could solve, but it would only make sense to do if we can be 10x better than the diffusion model generators that currently exist.

I think also having the procedural breakdown of a whole character gives us the ability to edit with an LLM instead of starting from scratch or explore the parameter space for one of a character's body parts instead of needing to stitch together various quality outputs from a diffusion generator. 

A lot of the work in for example creating good outputs from something like Stable Diffusion is either in collecting a good data set of representative images to train a LoRa or combining in something like Gimp or photoshop all of the outputs that are 50% of the way their and picking and choosing the best representative sample. It is tedious and error prone since if you cannot get a specific output you are looking for from the diffusion model then you’d often be better off drawing the entire image from scratch.

See also:
https://github.com/timothybrooks/instruct-pix2pix
Minecraft mod that takes in a real image and makes it into blocks using depth estimation

Alternative model is to create something akin to AlphaGo in the domain specific language that we create using Monte Carlo Tree Search and a differentiable or inverse renderer on images scraped from the internet to train the model to copy an image in 3d from an image. Then the model can learn what “good moves” in the space of 3d modelling are.
https://senguptaumd.github.io/Neural-Inverse-Rendering/
Agent loop for 3D that works well
Create a deep research report on the anatomy of the thing you are trying to replicate
If it is an organic creature then include all of the bones in the animal and how they link to each other, proportions and measurements
Use that as the basis for a set of geometry node groups
Agent explores the codebase for the different primitives it has access to
Agent creates a plan for new node groups that it needs to make
Agent creates the node group, starts blender with the updated addon, creates an object with the node group
Agent checks for any run time errors in setting up the node group (back to step 5 till passes)
Agent validates that the geometry has been generated, it is watertight, and is not collapsed to the origin.
Agent sets up the multiple cameras to be able to see the object and creates renders in its temp directory
Agent views the screenshot and makes adjustments to the parameters of the node group to find good defaults. 
I have not figured out how to have LLM do this yet and so I have been doing it, but you could imagine having a grid of 16 renders with different variations on the parameter set numbered and telling it to pick the best one until you reach a minima, i.e. it continually chooses the unaltered render.
Agent either continues back to 5 or declares victory and presents the generated mesh to me for review.
The Post-process is around workflow improvements so we query the AI around what in the CLI or node creation code can be improved, what patterns could we refactor to shrink the codebase etc. and then either create tickets or make the changes and revalidate.

All actions within blender are accomplished with a cli that talks over localhost port to the program. (I have used this in my game development repo and it worked well there as well so doesn’t necessarily need to be blender just need a piece inside the program to manipulate it at runtime and one outside the program for the ai to talk to)
Blender Addon Marketplaces
Superhive, GumRoad, Blenderkit
Each marketplace takes ~30% cut on addon sales and some marketplaces like superhive charge flat rates of 200 a year to addon developers to even list on the platform. I was thinking that if you could clone the top addons and sell directly then you could create a one sided marketplace and get the rest of the 70%. Depends largely on how automated we can make blender addons and translate research ideas into addons.
Blender Addons and GPL
Looking into licensing for blender addons GNU license is pretty wack, you have to provide source code when asked and anyone can resell or distribute your code and any code that is in the same program. Any program that even links against a library that is GPL has to be GPL, so the only way to have proprietary software is to have two distinct programs, one that does all of the actual work that is valuable and a trade secret and a thin client on the blender side that sends requests and translates the responses into blender scenes.
Houdini
Houdini is the clear node based analog that we’d want to replace with an automated and pay per token type model.

Houdini pricing ranges from free for learning (Apprentice) to ~$269/year for independent artists (Indie) and thousands for professional commercial use. Key plans include Indie (under $100k revenue), Core ($1,995+ perpetual/$1,340+ yearly), and FX ($4,495+ perpetual/$3,195+ yearly), with specialized Engine options for game devs.

Houdini Pricing Tiers (SideFX):
Houdini Apprentice: Free for non-commercial, learning, and portfolio use (includes watermarks).
Houdini Indie: ~$269-$299/year for artists/studios with revenue under $100,000.
Houdini Core: Node-locked: ~$1,995 (perpetual) or ~$1,340/year. Features modeling, animation, and rendering.
Houdini FX: Node-locked: ~$4,495 (perpetual) or ~$3,195/year. Includes advanced fluid, smoke, and destruction tools.
Houdini Engine: ~$499/year for workstations to integrate tools into Unity/Unreal. 
