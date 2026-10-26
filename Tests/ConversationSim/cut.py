# Removes a top-level class (by brace matching) from a Swift file, for the simulation.
# Usage: python3 cut.py in.swift out.swift "final class X"
import sys
src, out, head = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(src).read()
i = s.index(head)
# include the doc comment lines right above
j = s.index('{', i)
depth = 0
k = j
while True:
    c = s[k]
    if c == '{': depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0: break
    k += 1
s = s[:i] + s[k+1:]
open(out, 'w').write(s)
