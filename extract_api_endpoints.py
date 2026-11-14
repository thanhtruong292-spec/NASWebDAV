import ast
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent
KOTLIN_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "nas" / "naswebdav"
PYTHON_FILE = ROOT / "backend" / "nas_api_server.py"
ENDPOINT_RE = re.compile(r"/api/[A-Za-z0-9_/$<>.-]+")


def _strip_kotlin_comments(source):
    """Remove // and /* */ comments without damaging URLs inside strings."""
    output = []
    index = 0
    quote = None
    escaped = False
    in_block = False
    in_line = False
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if in_line:
            if char == "\n":
                output.append(char)
                in_line = False
            index += 1
            continue
        if in_block:
            if char == "*" and next_char == "/":
                in_block = False
                index += 2
            else:
                if char == "\n":
                    output.append(char)
                index += 1
            continue
        if quote:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            index += 1
            continue
        if char in ("'", '"'):
            quote = char
            output.append(char)
            index += 1
        elif char == "/" and next_char == "/":
            in_line = True
            index += 2
        elif char == "/" and next_char == "*":
            in_block = True
            index += 2
        else:
            output.append(char)
            index += 1
    return "".join(output)


def _normalize_endpoint(path):
    path = path.split("?", 1)[0].rstrip(".")
    path = re.sub(r"/\$[A-Za-z_][A-Za-z0-9_]*", "/<var>", path)
    path = re.sub(r"/\$\{[^}]+\}", "/<var>", path)
    path = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*$", "", path)
    path = re.sub(r"\$\{[^}]+\}$", "", path)
    path = re.sub(r"<[^>]+>", "<var>", path)
    return path.rstrip("/") or "/"


def extract_endpoints_from_kotlin(kotlin_dir=KOTLIN_DIR):
    endpoints = set()
    kotlin_dir = Path(kotlin_dir)
    if not kotlin_dir.is_dir():
        return endpoints
    for filepath in kotlin_dir.rglob("*.kt"):
        content = _strip_kotlin_comments(filepath.read_text(encoding="utf-8"))
        for match in ENDPOINT_RE.finditer(content):
            endpoint = _normalize_endpoint(match.group(0))
            line_start = content.rfind("\n", 0, match.start()) + 1
            line_end = content.find("\n", match.end())
            if line_end < 0:
                line_end = len(content)
            line = content[line_start:line_end]
            is_url_or_path = re.search(
                r"\.url\s*\(|\bconst\s+val\b|\bval\s+\w*(?:path|url)\b|\breturn\b",
                line,
                re.IGNORECASE,
            )
            if endpoint != "/api/<var>" and is_url_or_path:
                endpoints.add(endpoint)
    return endpoints


def extract_endpoints_from_python(python_file=PYTHON_FILE):
    endpoints = set()
    python_file = Path(python_file)
    if not python_file.is_file():
        return endpoints
    tree = ast.parse(python_file.read_text(encoding="utf-8"), filename=str(python_file))
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for decorator in node.decorator_list:
            if not (
                isinstance(decorator, ast.Call)
                and isinstance(decorator.func, ast.Attribute)
                and decorator.func.attr == "route"
                and decorator.args
            ):
                continue
            path = getattr(decorator.args[0], "s", None)
            if path is None:
                path = getattr(decorator.args[0], "value", None)
            if isinstance(path, str) and path.startswith("/api/"):
                endpoints.add(_normalize_endpoint(path))
    return endpoints


def main():
    kotlin_endpoints = extract_endpoints_from_kotlin()
    python_endpoints = extract_endpoints_from_python()

    print("=== Endpoints called from Kotlin ===")
    for endpoint in sorted(kotlin_endpoints):
        print("  " + endpoint)

    print("\n=== Endpoints defined in Python ===")
    for endpoint in sorted(python_endpoints):
        print("  " + endpoint)

    print("\n=== Missing in Python ===")
    missing = sorted(kotlin_endpoints - python_endpoints)
    if not missing:
        print("  None")
    for endpoint in missing:
        print("  " + endpoint)


if __name__ == "__main__":
    main()
