"""Prepare a separate NAS candidate; never replace the source or live daemon."""
import ast
import hashlib
import json
import sys


def prepare(source_path, candidate_path):
    with open(source_path, "rb") as handle:
        original = handle.read()
    expected = "95cdac688cd82e89190d16f810f076a2936c12f2e4c651a51944d61d86c254ee"
    assert hashlib.sha256(original).hexdigest() == expected, "Unexpected source revision"
    before = '''                if (".." in rel.split("/") or rel.startswith(("/", "\\\\")) or
                        _index_resolve_directory(path, root, storage_boundary) is None):
                    raise ValueError("Excluded directory boundary or ancestor cycle")
                before = _index_directory_stamp(path)
                if saved[0] == before:
                    continue
'''
    after = '''                if ".." in rel.split("/") or rel.startswith(("/", "\\\\")):
                    raise ValueError("Unsafe directory path")
                before = _index_directory_stamp(path)
                if saved[0] == before and not os.path.islink(path):
                    continue
                if _index_resolve_directory(path, root, storage_boundary) is None:
                    raise ValueError("Excluded directory boundary or ancestor cycle")
                if saved[0] == before:
                    continue
'''
    text = original.decode("utf-8")
    assert text.count(before) == 1, "Unexpected directory validation block"
    candidate = text.replace(before, after, 1)
    ast.parse(candidate)
    compile(candidate, candidate_path, "exec")
    with open(candidate_path, "x", encoding="utf-8") as handle:
        handle.write(candidate)
    print(json.dumps({"source_sha256": expected,
                      "candidate_sha256": hashlib.sha256(candidate.encode("utf-8")).hexdigest(),
                      "python": sys.version.split()[0], "deployed": False}))


if __name__ == "__main__":
    prepare(sys.argv[1], sys.argv[2])
