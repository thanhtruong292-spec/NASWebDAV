import os
import re

def extract_endpoints_from_kotlin():
    endpoints = set()
    kotlin_dir = r"d:\Android\NASWebDAV\app\src\main\java\com\nas\naswebdav"
    for root, _, files in os.walk(kotlin_dir):
        for file in files:
            if file.endswith(".kt"):
                filepath = os.path.join(root, file)
                try:
                    with open(filepath, 'r', encoding='utf-8') as f:
                        content = f.read()
                        
                        # Find all strings that look like API endpoints
                        # Usually they look like "/api/..." or similar in string literals
                        matches = re.findall(r'"(/api/[^"]+)"', content)
                        for m in matches:
                            endpoints.add(m)
                            
                        # Handle cases with string interpolation or variables, like `"$apiBase/api/..."`
                        matches = re.findall(r'/api/[a-zA-Z0-9_/-]+', content)
                        for m in matches:
                            endpoints.add(m)
                except Exception as e:
                    print(f"Error reading {filepath}: {e}")
    return endpoints

def extract_endpoints_from_python():
    endpoints = set()
    python_file = r"d:\Android\NASWebDAV\nas_api_server.py"
    try:
        with open(python_file, 'r', encoding='utf-8') as f:
            content = f.read()
            # Match @app.route("/api/...")
            matches = re.findall(r'@app\.route\("(/api/[^"]+)"', content)
            for m in matches:
                endpoints.add(m)
    except Exception as e:
        print(f"Error reading {python_file}: {e}")
    return endpoints

def main():
    kotlin_endpoints = extract_endpoints_from_kotlin()
    python_endpoints = extract_endpoints_from_python()
    
    print("=== Endpoints called from Kotlin ===")
    for ep in sorted(kotlin_endpoints):
        print(f"  {ep}")
        
    print("\n=== Endpoints defined in Python ===")
    for ep in sorted(python_endpoints):
        print(f"  {ep}")
        
    print("\n=== Missing in Python ===")
    for ep in sorted(kotlin_endpoints):
        if ep not in python_endpoints:
            # Try to see if it's a partial match (e.g. Kotlin has /api/tiktok/live_watch?param, Python has /api/tiktok/live_watch)
            matched = False
            for p_ep in python_endpoints:
                if ep.startswith(p_ep) or p_ep.startswith(ep):
                    matched = True
                    break
            
            # Special case for endpoints with path variables (e.g., /api/some/<id>)
            if not matched:
                print(f"  {ep}")

if __name__ == "__main__":
    main()
