#!/usr/bin/env bash
# Reproduce hench/libs/hench-ffmpeg-runtime.aar: the youtubedl-android ffmpeg
# AAR rebuilt from pinned native inputs and selected security backports.
#
# What this replaces: io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1
# embeds FFmpeg 7.1.1 + GnuTLS 3.8.10, libssh 0.11.3, libsrt 1.5.4, libaom
# 3.13.1, dav1d 1.5.1, libvpx 1.15.2, libpng 1.6.50, harfbuzz 11.5.1, ... —
# the embedded graph is old even though the wrapper is current.
#
# New inputs: Termux packages.termux.dev apt stable repo, ffmpeg 9.0.2
# (latest upstream release; recipe: termux/termux-packages
# packages/ffmpeg/build.sh @ 6ca3c4716a8640622de2ae42d035e5c97a519739,
# source tarball ffmpeg-9.0.2.tar.xz sha256
# 8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e,
# license GPL-3.0). Every deb is sha256-pinned in debs-<abi>.txt — the full
# apt dependency closure of the ffmpeg package, so a broken/partial update
# can't silently drift. Runtime content is the ELF DT_NEEDED closure of the
# ffmpeg+ffprobe binaries plus their dependency .so trees: all formats,
# filters and transports stay enabled; only libraries nothing links to
# (python, mesa, X11, ...) are left out. etc/tls + etc/fonts and per-package
# copyright/license files ride inside the zip for parity and license
# compliance.
#
# Output layout (unchanged contract):
#   jni/<abi>/libffmpeg.so     <- the ffmpeg PIE executable
#   jni/<abi>/libffprobe.so    <- the ffprobe PIE executable
#   jni/<abi>/libffmpeg.zip.so <- usr/lib/**.so*, usr/etc/{tls,fonts},
#                               usr/share/doc/** copyright/LICENSE files,
#                               usr/share/ffmpeg-runtime/{SOURCES,NOTICES}.txt
# ABIs: arm64-v8a + x86_64 only (matches the patched python runtime AAR).
#
# Requires: bash, python3, curl, ar (binutils), tar, sha256sum, readelf,
#           zip + unzip. First run ./build-ffmpeg-deb.sh for both patched debs.
# Usage: ./build-ffmpeg-aar.sh            # writes libs/hench-ffmpeg-runtime.aar
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
OUT_AAR="${OUT_AAR:-$HERE/../libs/hench-ffmpeg-runtime.aar}"
REPO="https://packages.termux.dev/apt/termux-main"
# Stock AAR we take classes.jar + manifests from (com.yausername.ffmpeg.FFmpeg).
ORIG_URL="https://repo1.maven.org/maven2/io/github/junkfood02/youtubedl-android/ffmpeg/0.18.1/ffmpeg-0.18.1.aar"
ORIG_SHA256="0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49"
PREFIX="data/data/com.termux/files/usr"   # inside every termux deb

mkdir -p "$WORK" "$(dirname "$OUT_AAR")"

# ---- 1. stock AAR wrapper -------------------------------------------------
if [ ! -f "$WORK/orig.aar" ]; then
    curl -fL -o "$WORK/orig.aar" "$ORIG_URL"
fi
echo "$ORIG_SHA256  $WORK/orig.aar" | sha256sum -c -
rm -rf "$WORK/orig" && mkdir -p "$WORK/orig"
unzip -q "$WORK/orig.aar" -d "$WORK/orig"
build_abi() {  # $1=deb manifest suffix  $2=aar jni abi dir
    local key="$1" aarabi="$2"
    local tsv="$HERE/debs-$key.txt"
    local ddeb="$WORK/debs-$key" dext="$WORK/ext-$aarabi" dstage="$WORK/stage-$aarabi"
    mkdir -p "$ddeb" "$dext"

    # Never fall back to the unpatched upstream FFmpeg binary.
    local debabi="$key"
    [ "$key" != arm64 ] || debabi=aarch64
    local override="${FFMPEG_DEB_DIR:-$HERE/out}/ffmpeg_9.0.2-1_$debabi.deb"
    [ -s "$override" ] || {
        echo "Missing $override; run ./build-ffmpeg-deb.sh first" >&2
        exit 1
    }
    while IFS=$'\t' read -r pkg ver fn sha; do
        [[ "$pkg" =~ ^# ]] && continue
        local deb="$ddeb/$(basename "$fn")"
        if [ "$pkg" = ffmpeg ]; then
            cp "$override" "$deb"
            echo "using patched ffmpeg deb: $override ($(sha256sum "$deb" | cut -d' ' -f1))"
            continue
        fi
        [ -f "$deb" ] || curl -fsSL -o "$deb" "$REPO/$fn"
        echo "$sha  $deb" | sha256sum -c - >/dev/null || {
            echo "checksum FAILED: $pkg ($fn)"; exit 1; }
    done < "$tsv"

    # extract all debs into one prefix tree
    while IFS=$'\t' read -r pkg ver fn sha; do
        [[ "$pkg" =~ ^# ]] && continue
        local deb="$ddeb/$(basename "$fn")" dx="$ddeb/x-$(basename "$fn" .deb)"
        mkdir -p "$dx"
        ar x "$deb" --output="$dx"
        tar --force-local -xf "$dx"/data.tar.* -C "$dext"
    done < "$tsv"

    # ELF DT_NEEDED closure from the two binaries, preferring real providers
    # over libandroid-stub link stubs; keep each kept lib's full soname chain.
    python3 - "$dext/$PREFIX" "$dstage" "$ddeb" <<'PYEOF'
import os, re, subprocess, sys, glob, tarfile, shutil

ext, stage, ddeb = sys.argv[1], sys.argv[2], sys.argv[3]
LIB = './data/data/com.termux/files/usr/lib/'

# package -> member list (from the debs we just extracted)
pkg_files = {}
for dx in glob.glob(os.path.join(ddeb, 'x-*')):
    data = [f for f in os.listdir(dx) if f.startswith('data.tar')]
    with tarfile.open(os.path.join(dx, data[0])) as tf:
        pkg_files[os.path.basename(dx)[2:]] = tf.getnames()

provider, stub = {}, {}
for n, files in pkg_files.items():
    for f in files:
        if f.startswith(LIB) and '.so' in os.path.basename(f):
            b = os.path.basename(f)
            if n.startswith('libandroid-stub'):
                stub.setdefault(b, (n, f))
            else:
                provider.setdefault(b, (n, f))

def needed(path):
    try:
        out = subprocess.check_output(['readelf', '-d', path], text=True)
    except Exception:
        return []
    return re.findall(r'NEEDED.*\[(.+)\]', out)

def rel(f):  # deb member -> ext-relative path
    return f.replace('./data/data/com.termux/files/usr/', '')

def epath(f):
    return os.path.join(ext, rel(f))

seeds = [os.path.join(ext, 'bin/ffmpeg'), os.path.join(ext, 'bin/ffprobe')]
have, keep, missing = {os.path.basename(p) for p in seeds}, set(seeds), set()
queue, seen = list(seeds), set(seeds)
while queue:
    p = queue.pop()
    for need in needed(p):
        if need in have:
            continue
        prov = provider.get(need) or stub.get(need)
        if prov:
            pkg, f = prov
            have.add(need)
            for f2 in pkg_files[pkg]:  # whole soname chain incl. symlinks
                if f2.startswith(LIB) and os.path.basename(f2).split('.so')[0] == need.split('.so')[0]:
                    keep.add(epath(f2))
            real = epath(f)
            if os.path.exists(real) and real not in seen:
                seen.add(real)
                queue.append(real)
        else:
            missing.add(need)  # expected: libc/libdl/libm/liblog/libandroid (system)

# binaries ship as jni/<abi>/libffmpeg.so + libffprobe.so (no exec bit survives
# zip extraction, so usr/bin copies would only shadow the working location)
want = {os.path.relpath(f, ext) for f in keep
        if not f.endswith(('/bin/ffmpeg', '/bin/ffprobe'))}
# runtime config/data parity + license texts
for dirp, _, files in os.walk(ext):
    for fn in files:
        r = os.path.relpath(os.path.join(dirp, fn), ext)
        if r.startswith(('etc/tls/', 'etc/fonts/', 'share/fontconfig/',
                         'share/LICENSES/')):
            want.add(r)
        elif r.startswith('share/doc/') and re.search(
                r'(?i)(copyright|licen[cs]e|copying|authors|notice)', fn):
            want.add(r)

# pull in targets of every staged symlink until the set is closed
for _ in range(20):
    grew = False
    for r in list(want):
        src = os.path.join(ext, r)
        if os.path.islink(src):
            t = os.readlink(src)
            if t.startswith('/'):
                # absolute termux-prefix link: remap into extracted tree
                t = t.replace('/data/data/com.termux/files/usr/', '', 1)
            else:
                t = os.path.normpath(os.path.join(os.path.dirname(r), t))
            if t != r and t not in want and os.path.lexists(os.path.join(ext, t)):
                want.add(t); grew = True
    if not grew:
        break


shutil.rmtree(stage, ignore_errors=True)
os.makedirs(stage, exist_ok=True)
for r in sorted(want):
    src = os.path.join(ext, r)
    dst = os.path.join(stage, 'usr', r)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.islink(src):
        if os.path.lexists(dst):
            os.unlink(dst)
        os.symlink(os.readlink(src), dst)
    elif os.path.isfile(src):
        shutil.copy2(src, dst)

# no staged symlink may dangle after extraction
dangling = [os.path.relpath(os.path.join(dp, f), stage) + ' -> ' + os.readlink(os.path.join(dp, f))
            for dp, _, fs in os.walk(stage) for f in fs
            if os.path.islink(os.path.join(dp, f)) and not os.path.exists(os.path.join(dp, f))]
if dangling:
    sys.exit('dangling symlinks in staged tree: %s' % dangling[:20])

unknown = missing - {'libc.so','libdl.so','libm.so','liblog.so',
                     'libandroid.so','libmediandk.so','libjnigraphics.so',
                     'libOpenSLES.so','libOpenCL.so','libvulkan.so',
                     'libvulkan.so.1','libEGL.so','libGLESv2.so','libGLESv3.so'}
if unknown:
    sys.exit('unresolved non-system DT_NEEDED: %s' % sorted(unknown))
print('staged %d files; system-resolved: %s' % (len(want), sorted(missing)))
PYEOF

    # provenance + notices inside the runtime zip
    mkdir -p "$dstage/usr/share/ffmpeg-runtime"
    { awk -F'\t' '!/^#/ && $1!="ffmpeg" {printf "%s %s  deb sha256:%s\n", $1, $2, $4}' "$tsv"
      printf "ffmpeg 9.0.2-1  locally rebuilt (see SOURCES.txt) deb sha256:%s\n" \
          "$(sha256sum "$override" | cut -d' ' -f1)"
    } > "$dstage/usr/share/ffmpeg-runtime/PACKAGES.txt"
    cat > "$dstage/usr/share/ffmpeg-runtime/SOURCES.txt" <<EOF
ffmpeg 9.0.2 + security backports (GPL-3.0)
  recipe: https://github.com/termux/termux-packages/blob/6ca3c4716a8640622de2ae42d035e5c97a519739/packages/ffmpeg/build.sh
  source: https://www.ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz
          sha256 8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
  backports (required; built by hench/native-ffmpeg/build-ffmpeg-deb.sh):
    rtsp-redirect-limit.patch      CVE-2026-107696 (upstream f5d4fd186c68)
    rtsp-samplerate-validate.patch upstream a7b20ee10335
  both from https://github.com/FFmpeg/FFmpeg master, applied in order to n9.0.2.
Binaries: dependency libs are Termux termux-main stable repo
(packages.termux.dev), pinned per-ABI in hench/native-ffmpeg/debs-*.txt; the
ffmpeg deb itself is the required patched local rebuild.
Each package's build.sh lives under packages/<name>/ in termux-packages;
upstream source URLs are in those recipes. Wrapper classes from
io.github.junkfood02.youtubedl-android :ffmpeg:0.18.1 (GPL-3.0, com.yausername.ffmpeg).
Known limit: OpenSSL/termux-compiled default cert and fontconfig paths point at
/data/data/com.termux/... and won't resolve here; usr/etc/tls/cert.pem and
usr/etc/fonts + usr/share/fontconfig are shipped so SSL_CERT_FILE /
FONTCONFIG_FILE env vars can activate them. In this app ffmpeg is only invoked
for local muxing/audio extraction (yt-dlp downloads media itself), so this
matches prior behavior.
EOF

    (cd "$dstage" && rm -f "$WORK/libffmpeg-$aarabi.zip" && \
     zip -qrXy "$WORK/libffmpeg-$aarabi.zip" usr)
    cp "$dext/$PREFIX/bin/ffmpeg"  "$WORK/libffmpeg-$aarabi.so"
    cp "$dext/$PREFIX/bin/ffprobe" "$WORK/libffprobe-$aarabi.so"
}
build_abi arm64 arm64-v8a
build_abi x86_64 x86_64

# ---- 3. assemble AAR ------------------------------------------------------
rm -rf "$WORK/aar" && mkdir -p "$WORK/aar"
cp "$WORK/orig/R.txt" "$WORK/orig/AndroidManifest.xml" "$WORK/orig/classes.jar" "$WORK/aar/"
cp -r "$WORK/orig/res" "$WORK/orig/META-INF" "$WORK/aar/"
for abi in arm64-v8a x86_64; do
    mkdir -p "$WORK/aar/jni/$abi"
    cp "$WORK/libffmpeg-$abi.so"     "$WORK/aar/jni/$abi/libffmpeg.so"
    cp "$WORK/libffprobe-$abi.so"    "$WORK/aar/jni/$abi/libffprobe.so"
    cp "$WORK/libffmpeg-$abi.zip"    "$WORK/aar/jni/$abi/libffmpeg.zip.so"
done
(cd "$WORK/aar" && rm -f "$OUT_AAR" && zip -qrX "$OUT_AAR" .)
echo "built $OUT_AAR"
