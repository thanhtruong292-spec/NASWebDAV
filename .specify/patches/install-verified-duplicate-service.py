"""Assemble the internal reviewed candidate, without deploying it."""
import hashlib
import sys

with open(sys.argv[1], 'r', encoding='utf-8') as handle:
    source = handle.read()
assert hashlib.sha256(source.encode('utf-8')).hexdigest() == 'c555d0391221a5775a943dd9e4348d8447755b72a0d8c2f99028cac9fc3e05dd'
with open(sys.argv[2], 'r', encoding='utf-8') as handle:
    module = handle.read()
marker = "@app.route('/api/storage/summary'"
assert source.count(marker) == 1
position = source.index(marker)
candidate = source[:position] + module + '\n\n' + source[position:]
compile(candidate, sys.argv[3], 'exec')
with open(sys.argv[3], 'x', encoding='utf-8') as handle:
    handle.write(candidate)
print(hashlib.sha256(candidate.encode('utf-8')).hexdigest())
