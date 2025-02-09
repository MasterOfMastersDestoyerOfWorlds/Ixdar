# Test Failure Notes

1. test_djibouti_8_20 - ixdarSkip

2. test_lines - unknown

3. test_lu634 - recursion limit

4. test_lu634_491_501p16_48 - ixdarSkip

5. test_lu634_571_581p603_625 - is both a clockwork rotation error and an ixdarSkip error in combination Knot 65 has the correct cutMatchList but does not rotate to it Knot 73 does not have the correct cutMatchList because of ixdar skip

6. test_qa194 - recursion limit

7. test_qa194_0_14WH - issue with wormholes not being infinitely far away from all other points except their endpoints, can likely fix in the Distance Matrix code

8. test_qa194_0_20 - ixdarSkip

9. test_qa194_0_47p193_173 - few things wrong, likely need to improve how we are separating 2-knots so that they do not combine with each other when they would later be part of a knot and their knot neighbors want to connect to two separate parts of the 2-knot

10. test_qa194_180-6 - ixdarSkip

11. test_qa194_23_0p193_179 - similar to 0-47p193-173 in that there are a few places where the knot construction is suboptimal, also turning off ixdar skip gets the correct answer

12. test_qa194_60_80 - 17 is closer to everything in knot [20,19,16] but matches to the father away knot instead

13. test_qa194_67_151 - did not finish knot construction

14. test_qa194_48_0p193_154 - did not finish knot construction
