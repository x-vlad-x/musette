#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Put together the files of a release in out/release/<version>/.

    tools/prepare-release.py

Run tools/build-packages.sh from the clean release commit first. The release gets the two
packages, the source of this repository, the complete corresponding source of everything in
the packages (in 64 MiB parts), the release notes and SHA256SUMS.
"""

import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGES = ROOT / "out/packages"
PART_SIZE = 64 * 1024 * 1024
LIBPLACEBO = ("https://code.videolan.org/videolan/libplacebo.git", "v7.351.0", "3188549fba13bbdf3a5a98de2a38c2e71f04e21e")
DOWNLOADS = [
    ("libass-0.17.5.tar.xz", "https://github.com/libass/libass/releases/download/0.17.5/libass-0.17.5.tar.xz",
     "2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc"),
    ("mpv-0.41.0.tar.gz", "https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz",
     "ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209"),
]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout


def env_file(path):
    values = {}
    for line in Path(path).read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url, dest, expected):
    if dest.exists() and sha256(dest) == expected:
        return
    print(f"Downloading {url}")
    partial = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, open(partial, "wb") as f:
        shutil.copyfileobj(response, f)
    if sha256(partial) != expected:
        partial.unlink()
        raise SystemExit(f"Wrong SHA-256 for {url}")
    partial.rename(dest)


def check_packages(version):
    if run("git", "-C", str(ROOT), "status", "--porcelain").strip():
        raise SystemExit("Commit the reviewed changes before preparing the release")
    head = run("git", "-C", str(ROOT), "rev-parse", "HEAD").strip()
    info = json.loads((PACKAGES / "build-info.json").read_text())
    if info["version"] != version or info["project_commit"] != head or info["project_dirty"]:
        raise SystemExit("Rebuild the packages from the clean release commit: tools/build-packages.sh")
    deb_version = run(str(ROOT / "packaging/debian/deb-version.sh"), version).strip().replace("~", "-")
    deb = PACKAGES / f"musette_{deb_version}_arm64.deb"
    flatpak = PACKAGES / f"Musette-{version}-aarch64.flatpak"
    for path in (deb, flatpak):
        if not path.exists():
            raise SystemExit(f"Missing {path}")
    return head, info, deb, flatpak


def project_archive(version, head, dest):
    """This repository at the release commit, with a list of the files and their hashes."""
    prefix = f"musette-{version}"
    date = int(run("git", "-C", str(ROOT), "log", "-1", "--format=%ct", head).strip())
    raw = subprocess.run(["git", "-C", str(ROOT), "archive", "--format=tar", f"--prefix={prefix}/", head],
                         check=True, capture_output=True).stdout
    files = {}
    with tarfile.open(fileobj=io.BytesIO(raw)) as source:
        members = source.getmembers()
        for member in members:
            if member.isfile():
                files[member.name[len(prefix) + 1:]] = hashlib.sha256(source.extractfile(member).read()).hexdigest()
        revision = json.dumps({"version": version, "commit": head, "date": date, "files": files}, indent=2).encode() + b"\n"
        with open(dest, "wb") as raw_out:
            with tarfile.open(fileobj=raw_out, mode="w:gz", format=tarfile.PAX_FORMAT) as out:
                for member in members:
                    data = source.extractfile(member) if member.isfile() else None
                    out.addfile(member, data)
                info = tarfile.TarInfo(f"{prefix}/SOURCE-REVISION.json")
                info.size, info.mtime, info.mode = len(revision), date, 0o644
                out.addfile(info, io.BytesIO(revision))


def git_archive(repo, commit, prefix, dest):
    with open(dest, "wb") as f:
        archive = subprocess.Popen(["git", "-C", str(repo), "archive", "--format=tar", f"--prefix={prefix}/", commit],
                                   stdout=subprocess.PIPE)
        subprocess.run(["xz", "-T0", "-6"], stdin=archive.stdout, stdout=f, check=True)
        if archive.wait():
            raise SystemExit(f"git archive failed in {repo}")


def corresponding_sources(version, info, work):
    """Collect the source of everything the packages contain into work/."""
    upstream = env_file(ROOT / "upstream.env")
    runtime = env_file(ROOT / "runtime.env")
    src = Path(os.environ.get("SIMPMUSIC_SRC", ROOT / "SimpMusic"))
    cache = ROOT / "out/source-cache"
    cache.mkdir(parents=True, exist_ok=True)

    simpmusic = work / "simpmusic"
    simpmusic.mkdir()
    git_archive(src, upstream["SIMPMUSIC_COMMIT"], f"SimpMusic-{upstream['SIMPMUSIC_TAG']}",
                simpmusic / f"SimpMusic-{upstream['SIMPMUSIC_TAG']}.tar.xz")
    git_archive(src / "core", upstream["CORE_COMMIT"], f"core-{upstream['CORE_COMMIT'][:12]}",
                simpmusic / f"core-{upstream['CORE_COMMIT'][:12]}.tar.xz")

    java_runtime = work / "java-runtime"
    java_runtime.mkdir()
    name = f"OpenJDK25U-jdk-sources_{runtime['TEMURIN_VERSION']}_{runtime['TEMURIN_BUILD']}.tar.gz"
    release = f"jdk-{runtime['TEMURIN_VERSION']}%2B{runtime['TEMURIN_BUILD']}"
    download(f"https://github.com/adoptium/temurin25-binaries/releases/download/{release}/{name}",
             cache / name, runtime["TEMURIN_SOURCES_SHA256"])
    shutil.copy(cache / name, java_runtime / name)

    flatpak = work / "flatpak-modules"
    flatpak.mkdir()
    for name, url, expected in DOWNLOADS:
        download(url, cache / name, expected)
        shutil.copy(cache / name, flatpak / name)
    url, tag, commit = LIBPLACEBO
    with tempfile.TemporaryDirectory() as tmp:
        checkout = Path(tmp) / f"libplacebo-{tag}"
        run("git", "clone", "--quiet", "--depth=1", "--branch", tag, "--recurse-submodules", "--shallow-submodules",
            url, str(checkout))
        if run("git", "-C", str(checkout), "rev-parse", "HEAD").strip() != commit:
            raise SystemExit(f"libplacebo {tag} is not at {commit}")
        with tarfile.open(flatpak / f"libplacebo-{tag}.tar.xz", "w:xz") as tar:
            tar.add(checkout, arcname=checkout.name, filter=lambda m: None if "/.git" in m.name or m.name.endswith(".git") else m)

    lib_dir = ROOT / "out" / info["dist"] / "lib"
    subprocess.run([sys.executable, str(ROOT / "tools/third-party.py"), str(lib_dir), "--sources", str(work / "java-libraries")],
                   check=True)

    (work / "README.md").write_text(f"""# Corresponding source of Musette {version}

- `simpmusic/`: SimpMusic {upstream['SIMPMUSIC_TAG']} ({upstream['SIMPMUSIC_COMMIT']}) and its
  core ({upstream['CORE_COMMIT']}). `musette-{version}-sources.tar.gz` has the patches applied to
  them (`patches/`) and the build scripts (`tools/build-packages.sh`).
- `java-runtime/`: the source of Eclipse Temurin {runtime['TEMURIN_VERSION']}+{runtime['TEMURIN_BUILD']}; the packages
  contain a runtime made from it with jlink (`tools/build-runtime.sh`).
- `java-libraries/`: the published source of the Java libraries in the packages, listed with
  their hashes in `sources.json`; the licences are in `THIRD-PARTY.md`.
- `flatpak-modules/`: libass, libplacebo and mpv, built into the Flatpak by
  `packaging/flatpak/io.github.x_vlad_x.Musette.yml`. The GNOME runtime comes from Flathub.
""")


def write_parts(work, prefix, release_dir):
    """Stream one tar of work/ into numbered parts, and return the parts and the tar's hash."""
    digest = hashlib.sha256()
    parts, current, written = [], None, 0

    class Writer(io.RawIOBase):
        def writable(self):
            return True

        def write(self, data):
            nonlocal current, written
            view = memoryview(data)
            while view:
                if current is None or written == PART_SIZE:
                    if current:
                        current.close()
                    path = release_dir / f"{prefix}.tar.part{len(parts) + 1:02d}"
                    parts.append(path)
                    current, written = open(path, "wb"), 0
                chunk = view[: PART_SIZE - written]
                current.write(chunk)
                digest.update(chunk)
                written += len(chunk)
                view = view[len(chunk):]
            return len(data)

    writer = Writer()
    with tarfile.open(fileobj=writer, mode="w|", format=tarfile.PAX_FORMAT) as tar:
        for path in sorted(work.rglob("*")):
            if path.is_file():
                info = tar.gettarinfo(path, arcname=f"{prefix}/{path.relative_to(work)}")
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                with open(path, "rb") as f:
                    tar.addfile(info, f)
    if current:
        current.close()
    return parts, digest.hexdigest()


def main():
    version = (ROOT / "VERSION").read_text().strip()
    head, info, deb, flatpak = check_packages(version)
    release_dir = ROOT / "out/release" / version
    if release_dir.exists():
        shutil.rmtree(release_dir)
    release_dir.mkdir(parents=True)

    shutil.copy(deb, release_dir)
    shutil.copy(flatpak, release_dir)
    project_archive(version, head, release_dir / f"musette-{version}-sources.tar.gz")

    prefix = f"musette-{version}-corresponding-sources"
    with tempfile.TemporaryDirectory(dir=ROOT / "out") as tmp:
        work = Path(tmp)
        corresponding_sources(version, info, work)
        parts, whole = write_parts(work, prefix, release_dir)

    (release_dir / "SOURCES.md").write_text(f"""# Sources of Musette {version}

`musette-{version}-sources.tar.gz` is this project at commit {head}.

The corresponding source of everything in the packages is one tar archive in {len(parts)} parts:

```sh
cat {prefix}.tar.part* > {prefix}.tar
echo '{whole}  {prefix}.tar' | sha256sum -c
tar -xf {prefix}.tar
```

Its `README.md` describes the contents.
""")
    shutil.copy(ROOT / "packaging/RELEASE-NOTES.md", release_dir / "RELEASE-NOTES.md")
    names = sorted(p.name for p in release_dir.iterdir())
    with open(release_dir / "SHA256SUMS", "w") as sums:
        for name in names:
            sums.write(f"{sha256(release_dir / name)}  {name}\n")
    total = sum(p.stat().st_size for p in release_dir.iterdir())
    print(f"{release_dir}: {len(names) + 1} files, {total / 1e6:.0f} MB")


if __name__ == "__main__":
    main()
