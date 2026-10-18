"""Fetch bounded, hash-pinned public source, never a native binary package."""
import hashlib
import html
import json
import pathlib
import re
import sys
import tarfile
import urllib.parse
import urllib.request

ROOT = pathlib.Path(sys.argv[1]).resolve(strict=True)
DOWNLOADS = ROOT / "downloads"
SOURCES = ROOT / "sources"
DOWNLOADS.mkdir()
SOURCES.mkdir()
MAX_DOWNLOAD = 128 * 1024**2
SPECS = (
    ("ffmpeg-9.0.2.tar.gz", "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/946fcce07b6dcd0331c8cc609192aeff5e1924f8",
     "0aa2b1de2a5698b20a23e93d539a9a8e82ca0117496c5bdf05d198805f42bb3b"),
    ("lame-4.0.tar.gz", "https://downloads.sourceforge.net/project/lame/lame/4.0/lame-4.0.tar.gz",
     "3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb"),
)


def fetch(name, url, expected):
    for _ in range(3):
        request = urllib.request.Request(url, headers={"User-Agent": "AF-Codec-Source-Probe/1"})
        with urllib.request.urlopen(request, timeout=60) as response:
            if urllib.parse.urlparse(response.url).scheme != "https":
                raise ValueError("insecure source redirect")
            data = response.read(MAX_DOWNLOAD + 1)
        if len(data) > MAX_DOWNLOAD:
            raise ValueError("download limit")
        if hashlib.sha256(data).hexdigest() == expected:
            target = DOWNLOADS / name
            target.write_bytes(data)
            return target
        # SourceForge sometimes serves a landing page rather than the archive.
        # Follow only its exact public project URL, never arbitrary page code.
        if name != "lame-4.0.tar.gz" or data.startswith(b"\x1f\x8b"):
            raise ValueError("source hash mismatch: " + name)
        match = re.search(r'<meta\s+http-equiv="refresh"\s+content="[0-9]+;\s*url=([^"]+)"', data.decode("utf-8", "replace"), re.I)
        if not match:
            raise ValueError("SourceForge archive download unavailable")
        url = html.unescape(match.group(1))
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https" or parsed.hostname != "downloads.sourceforge.net" or parsed.path != "/project/lame/lame/4.0/lame-4.0.tar.gz":
            raise ValueError("untrusted landing target")
    raise ValueError("source download attempts exhausted")


manifest = []
for name, url, expected in SPECS:
    archive = fetch(name, url, expected)
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 40_000 or sum(m.size for m in members) > 1024**3:
            raise ValueError("source expansion limit")
        for member in members:
            path = pathlib.PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or ":" in member.name or "\\" in member.name:
                raise ValueError("uncontained archive path")
            if not (member.isfile() or member.isdir()):
                raise ValueError("source link/device entry")
        source.extractall(SOURCES, members=members, filter="data")
    manifest.append({"name": name, "url": url, "bytes": archive.stat().st_size, "sha256": expected})
(ROOT / "source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
