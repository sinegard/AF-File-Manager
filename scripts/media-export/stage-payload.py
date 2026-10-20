"""Verify a pinned source-built payload before staging generated Android inputs."""
import argparse
import hashlib
import json
import pathlib
import shutil
import struct
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument("source", type=pathlib.Path)
parser.add_argument("--source-commit", required=True)
parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("app/build/generated/media-export"))
args=parser.parse_args()
source=args.source.resolve(strict=True)
manifest=json.loads((source/"manifest.json").read_text(encoding="utf-8-sig"))
assert manifest["schema"]==1 and manifest["sourceCommit"]==args.source_commit
assert manifest["ndk"]=="27.3.13750724" and manifest["androidApi"]==26
assert len(args.source_commit)==40 and all(c in "0123456789abcdef" for c in args.source_commit)
listed=set()
for item in manifest["files"]:
    relative=pathlib.PurePosixPath(item["path"])
    assert not relative.is_absolute() and ".." not in relative.parts and ":" not in item["path"] and "\\" not in item["path"]
    path=source.joinpath(*relative.parts)
    assert not path.is_symlink() and path.is_file() and path.stat().st_size==item["bytes"]
    assert hashlib.sha256(path.read_bytes()).hexdigest()==item["sha256"],item["path"]
    assert item["path"] not in listed
    listed.add(item["path"])
assert {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()}==listed|{"manifest.json"}
abis={"arm64-v8a":183,"armeabi-v7a":40,"x86_64":62,"x86":3}
names={"libavcodec.so","libavfilter.so","libavformat.so","libavutil.so","libswscale.so","libswresample.so","libffmpeg-cli.so","libffprobe-cli.so"}
native=[]
for abi,machine in abis.items():
    directory=source/abi
    config=(directory/"config/config.h").read_text()
    components=(directory/"config/config_components.h").read_text()
    for flag in ("GPL","NONFREE","NETWORK","SMALL"):
        assert f"#define CONFIG_{flag} 0\n" in config,(abi,flag)
    for flag in ("LIBMP3LAME_ENCODER","FLAC_ENCODER","WMAV2_ENCODER","H264_MEDIACODEC_ENCODER","H264_METADATA_BSF","MPEGTS_MUXER","MPEGTS_DEMUXER","AFFTDN_FILTER","REVERSE_FILTER"):
        assert f"#define CONFIG_{flag} 1\n" in components,(abi,flag)
    assert '#define FFMPEG_VERSION "9.0.2"\n' in (directory/"config/ffversion.h").read_text()
    assert {p.name for p in (directory/"lib").iterdir()}==names
    for file in sorted((directory/"lib").iterdir()):
        data=file.read_bytes()
        assert data[:4]==b"\x7fELF" and data[5]==1 and struct.unpack_from("<H",data,18)[0]==machine
        if data[4]==2:
            offset=struct.unpack_from("<Q",data,32)[0]
            entry_size,count=struct.unpack_from("<HH",data,54)
            assert entry_size==56
            segments=[struct.unpack_from("<IIQQQQQQ",data,offset+n*entry_size) for n in range(count)]
            loads=[(p[2],p[3],p[7]) for p in segments if p[0]==1]
        else:
            assert data[4]==1
            offset=struct.unpack_from("<I",data,28)[0]
            entry_size,count=struct.unpack_from("<HH",data,42)
            assert entry_size==32
            segments=[struct.unpack_from("<IIIIIIII",data,offset+n*entry_size) for n in range(count)]
            loads=[(p[1],p[2],p[7]) for p in segments if p[0]==1]
        assert loads and all(a>=16384 and o%16384==v%16384 for o,v,a in loads),(abi,file.name)
        native.append({"path":f"jniLibs/{abi}/{file.name}","source":file,"sha256":hashlib.sha256(data).hexdigest(),"bytes":len(data)})
output=args.output.resolve()
assert not output.exists(),"Use a fresh generated output; never replace unverified existing data"
output.mkdir(parents=True)
staged=[]
for file in native:
    target=output/file["path"];target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(file["source"],target)
    staged.append({k:v for k,v in file.items() if k!="source"})
assets=output/"assets/licenses/media-export"
assets.mkdir(parents=True)
for name in ("FFmpeg-LGPL-2.1.txt","LAME-LGPL-2.txt","source-manifest.json"):
    shutil.copyfile(source/name,assets/name)
identity={"schema":1,"builderCommit":args.source_commit,"ffmpeg":"9.0.2","ffmpegCommit":"946fcce07b6dcd0331c8cc609192aeff5e1924f8","lame":"4.0","ndk":"27.3.13750724","minApi":26,"files":staged}
(assets/"components.json").write_text(json.dumps(identity,indent=2)+"\n")
(assets/"NOTICE.txt").write_text("FFmpeg 9.0.2: LGPL 2.1 or later. LAME 4.0: LGPL.\nTheir licenses are independent of AF's own license.\nMatching sources and build recipes accompany the AF release as a separate source archive.\nhttps://github.com/sinegard/AF-File-Manager/blob/main/docs/MEDIA_EXPORT.md\n")
source_bundle=output/"AF-media-export-sources.zip"
with zipfile.ZipFile(source_bundle,"x",compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for file in sorted(source.rglob("*")):
        if file.is_file() and (file.parent.name in ("sources","build-source") or file.name in ("source-manifest.json","FFmpeg-LGPL-2.1.txt","LAME-LGPL-2.txt")):
            archive.write(file,file.relative_to(source).as_posix())
stage={"schema":1,"sourceCommit":args.source_commit,"sourceManifestSha256":hashlib.sha256((source/"manifest.json").read_bytes()).hexdigest(),"native":staged,"sourceBundleSha256":hashlib.sha256(source_bundle.read_bytes()).hexdigest()}
(output/"stage.json").write_text(json.dumps(stage,indent=2)+"\n")
print(json.dumps({"stagedBinaries":len(native),"abis":list(abis),"output":str(output),"sourceBundleSha256":stage["sourceBundleSha256"]}))
