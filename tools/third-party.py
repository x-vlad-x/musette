#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""List the Java libraries of a Musette build with their licences, and collect their sources.

    tools/third-party.py LIB_DIR                    write THIRD-PARTY.md
    tools/third-party.py LIB_DIR --check            fail if THIRD-PARTY.md is out of date
    tools/third-party.py LIB_DIR --sources DIR      download the source of every library into DIR

LIB_DIR is the lib/ directory made by tools/build-dist.sh. Jar names there are
"<group>--<file>"; the version and the POM come from Gradle's cache, or from the repositories
the build uses. Jars without a group are SimpMusic's own modules and Musette's interface.
"""

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
REPOSITORIES = [
    "https://repo.maven.apache.org/maven2",
    "https://dl.google.com/dl/android/maven2",
    "https://jitpack.io",
]
OWN_MODULES = {"gtkApp-jvm.jar": "Musette"}
SIMPMUSIC = "https://github.com/maxrave-dev/SimpMusic"
# Libraries whose POM names no licence, checked by hand in their repositories.
LICENCE_OVERRIDES = {
    "com.github.TeamNewPipe:nanojson": (
        [("Apache License, Version 2.0 (source file headers)", "https://www.apache.org/licenses/LICENSE-2.0")],
        "https://github.com/TeamNewPipe/nanojson",
    ),
    **{
        f"{group}:{artifact}": (
            [("GNU General Public License v3.0", "https://github.com/maxrave-dev/PipePipeExtractor/blob/main/LICENSE")],
            "https://github.com/maxrave-dev/PipePipeExtractor",
        )
        for group, artifact in [
            ("com.github.maxrave-dev", "PipePipeExtractor"),
            ("com.github.maxrave-dev.PipePipeExtractor", "PipePipeExtractor"),
            ("com.github.maxrave-dev.PipePipeExtractor", "extractor"),
            ("com.github.maxrave-dev.PipePipeExtractor", "timeago-parser"),
        ]
    },
}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "musette-third-party"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code in (401, 403, 404):
            return None
        raise
    except urllib.error.URLError:
        return None


def repo_path(group, artifact, version, name):
    return f"{group.replace('.', '/')}/{artifact}/{version}/{name}"


def from_repositories(group, artifact, version, name):
    for base in REPOSITORIES:
        data = fetch(f"{base}/{repo_path(group, artifact, version, name)}")
        if data is not None:
            return data, base
    return None, None


def locate(group, file_name):
    """Find artifact and version of a jar in Gradle's cache."""
    matches = sorted((CACHE / group).glob(f"*/*/*/{file_name}"))
    if not matches:
        raise SystemExit(f"{group}--{file_name} is not in {CACHE}; run the build first")
    path = matches[0]
    return path.parts[-4], path.parts[-3]


def pom(group, artifact, version):
    name = f"{artifact}-{version}.pom"
    local = sorted((CACHE / group / artifact / version).glob(f"*/{name}"))
    data = local[0].read_bytes() if local else from_repositories(group, artifact, version, name)[0]
    if data is None:
        return None
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return None
    # Some POMs have no XML namespace; look them up the same way.
    for node in root.iter():
        node.tag = "{%s}%s" % (NS["m"], node.tag.split("}")[-1])
    return root


def text(node, path):
    found = node.find(path, NS)
    return found.text.strip() if found is not None and found.text else ""


def licences(group, artifact, version, depth=0):
    root = pom(group, artifact, version)
    if root is None:
        return [], ""
    url = text(root, "m:url") or text(root, "m:scm/m:url")
    found = []
    for licence in root.findall("m:licenses/m:license", NS):
        found.append((text(licence, "m:name"), text(licence, "m:url")))
    parent = root.find("m:parent", NS)
    if not found and parent is not None and depth < 6:
        parent_found, parent_url = licences(
            text(parent, "m:groupId"), text(parent, "m:artifactId"), text(parent, "m:version"), depth + 1
        )
        return parent_found, url or parent_url
    return found, url


def libraries(lib_dir):
    result = []
    for jar in sorted(Path(lib_dir).glob("*.jar")):
        if "--" not in jar.name:
            owner = OWN_MODULES.get(jar.name, "SimpMusic")
            result.append({"jar": jar.name, "own": owner})
            continue
        group, file_name = jar.name.split("--", 1)
        artifact, version = locate(group, file_name)
        result.append({"jar": jar.name, "group": group, "artifact": artifact, "version": version})
    return result


def render(lib_dir):
    rows, own, unknown = [], [], []
    for lib in libraries(lib_dir):
        if "own" in lib:
            own.append(lib)
            continue
        found, url = licences(lib["group"], lib["artifact"], lib["version"])
        if not found and f"{lib['group']}:{lib['artifact']}" in LICENCE_OVERRIDES:
            found, url = LICENCE_OVERRIDES[f"{lib['group']}:{lib['artifact']}"]
        if not found:
            unknown.append(lib["jar"])
            continue
        names = "; ".join(f"[{name}]({link})" if link else name for name, link in found)
        coordinate = f"{lib['group']}:{lib['artifact']}:{lib['version']}"
        project = f"[project]({url})" if url else ""
        rows.append(f"| `{coordinate}` | {names} | {project} |")
    if unknown:
        raise SystemExit("No licence in the POM; add to LICENCE_OVERRIDES:\n  " + "\n  ".join(unknown))
    lines = [
        "# Third-party software in Musette",
        "",
        "Generated by `tools/third-party.py` from the jars of the build; do not edit by hand.",
        "",
        "## SimpMusic",
        "",
        f"These modules are built from [SimpMusic]({SIMPMUSIC}) and its `core` repository at the",
        "revision in `upstream.env`, with the patches in `patches/`. Copyright maxrave-dev and",
        "SimpMusic contributors, GNU General Public License, version 3:",
        "",
        ", ".join(f"`{lib['jar']}`" for lib in own if lib["own"] == "SimpMusic"),
        "",
        "`gtkApp-jvm.jar` is Musette's own interface (GPL-3.0-or-later).",
        "",
        "## Java runtime",
        "",
        "Eclipse Temurin, reduced with jlink, at the version in `runtime.env`:",
        "GNU General Public License, version 2, with the Classpath Exception. Its notices are in",
        "the `legal/` directory of the runtime.",
        "",
        "## Flatpak only",
        "",
        "The Flatpak also contains libraries built from source by",
        "`packaging/flatpak/io.github.x_vlad_x.Musette.yml`:",
        "[mpv](https://mpv.io) (GPL-2.0-or-later as built),",
        "[libplacebo](https://code.videolan.org/videolan/libplacebo) (LGPL-2.1-or-later) and",
        "[libass](https://github.com/libass/libass) (ISC).",
        "",
        "## Java libraries",
        "",
        "| Library | Licence | Source |",
        "|---|---|---|",
        *rows,
        "",
    ]
    return "\n".join(lines)


def github_archive(group, artifact, version):
    """JitPack builds GitHub repositories; their source is the repository at that revision."""
    owner = group.split(".", 2)[2]
    return fetch(f"https://github.com/{owner}/{artifact}/archive/{version}.tar.gz"), f"{artifact}-{version}.tar.gz"


def collect_sources(lib_dir, dest):
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    manifest, missing = [], []
    for lib in libraries(lib_dir):
        if "own" in lib:
            continue
        group, artifact, version = lib["group"], lib["artifact"], lib["version"]
        name = f"{artifact}-{version}-sources.jar"
        local = sorted((CACHE / group / artifact / version).glob(f"*/{name}"))
        data, origin = (local[0].read_bytes(), "gradle cache") if local else from_repositories(group, artifact, version, name)
        if data is None and group.startswith("com.github."):
            data, name = github_archive(group, artifact, version)
            origin = "github"
        if data is None:
            missing.append(f"{group}:{artifact}:{version}")
            continue
        target = dest / f"{group}--{name}"
        target.write_bytes(data)
        manifest.append({
            "library": f"{group}:{artifact}:{version}",
            "file": target.name,
            "sha256": hashlib.sha256(data).hexdigest(),
            "origin": origin,
        })
    (dest / "sources.json").write_text(json.dumps({"sources": manifest, "missing": missing}, indent=2) + "\n")
    return manifest, missing


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lib_dir")
    parser.add_argument("--output", default=str(ROOT / "THIRD-PARTY.md"))
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--sources")
    args = parser.parse_args()
    if args.sources:
        manifest, missing = collect_sources(args.lib_dir, args.sources)
        print(f"{len(manifest)} source archives in {args.sources}")
        for item in missing:
            print(f"No published source for {item}", file=sys.stderr)
        return
    content = render(args.lib_dir)
    output = Path(args.output)
    if args.check:
        if not output.exists() or output.read_text() != content:
            raise SystemExit(f"{output} is out of date: run tools/third-party.py {args.lib_dir}")
        return
    output.write_text(content)
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
