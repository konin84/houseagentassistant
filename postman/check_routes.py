"""Checks every URL in the collection against the @Path annotations in the code.

A Postman collection is documentation that rots silently: a renamed endpoint leaves a
request that 404s, and nobody notices until someone tries it. This compares the two and
fails loudly instead.

    python postman/check_routes.py
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).parent.parent

CLASS_PATH = re.compile(r'@Path\("([^"]+)"\)\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*public\s+class')
METHOD_PATH = re.compile(r'@Path\("([^"]+)"\)')
HTTP_METHOD = re.compile(r'@(GET|POST|PUT|PATCH|DELETE)\b')


def routes_from_source():
    """Every (method, path) a JAX-RS resource actually exposes."""
    found = set()
    for java in ROOT.glob("*/src/main/java/**/*Resource.java"):
        text = java.read_text(encoding="utf-8")

        class_match = CLASS_PATH.search(text)
        if not class_match:
            continue
        base = class_match.group(1).rstrip("/")

        # Walk the file in order, pairing each HTTP verb with the @Path that follows it
        # before the next verb. Crude, but these files are small and regular.
        body = text[class_match.end():]
        chunks = re.split(r'(?=@(?:GET|POST|PUT|PATCH|DELETE)\b)', body)
        for chunk in chunks:
            verb = HTTP_METHOD.search(chunk)
            if not verb:
                continue
            sub = METHOD_PATH.search(chunk)
            suffix = sub.group(1) if sub else ""
            path = (base + "/" + suffix.strip("/")).rstrip("/") if suffix else base
            found.add((verb.group(1), normalise(path)))
    return found


def normalise(path):
    """Reduce {id}, {houseId}, {{houseId}} and so on to a single placeholder."""
    path = re.sub(r"\{\{[^}]+\}\}", "{}", path)
    path = re.sub(r"\{[^}]+\}", "{}", path)
    return "/" + path.strip("/")


def routes_from_collection():
    collection = json.loads(
        (pathlib.Path(__file__).parent / "houseagentassistant.postman_collection.json")
        .read_text(encoding="utf-8"))
    found = set()

    def walk(items):
        for item in items:
            if "item" in item:
                walk(item["item"])
                continue
            request = item["request"]
            raw = request["url"]["raw"].split("?")[0]
            path = re.sub(r"^\{\{\w+\}\}", "", raw)
            found.add((request["method"], normalise(path), item["name"]))

    walk(collection["item"])
    return found


if __name__ == "__main__":
    source = routes_from_source()
    collection = routes_from_collection()

    # Health and OpenAPI are served by Quarkus, not by a resource class of ours.
    ignored = {"/q/health", "/q/openapi", "/q/swagger-ui"}

    missing = [(m, p, n) for m, p, n in collection
               if p not in ignored and (m, p) not in source]
    unused = sorted(source - {(m, p) for m, p, _ in collection})

    for method, path, name in sorted(missing):
        print("NOT IN CODE  {:6} {:55} ({})".format(method, path, name))
    for method, path in unused:
        print("NOT IN POSTMAN {:6} {}".format(method, path))

    print("\n{} requests, {} routes in code, {} missing, {} undocumented".format(
        len(collection), len(source), len(missing), len(unused)))
    sys.exit(1 if missing or unused else 0)
