#!/usr/bin/env bash
# Rebuild the Termux ffmpeg 9.0.2 deb with the post-release security fixes
# Termux does not yet carry:
#   patches/rtsp-redirect-limit.patch    CVE-2026-107696 — unbounded 3xx
#                                      redirect loop in ff_rtsp_connect()
#                                      (upstream FFmpeg/FFmpeg@f5d4fd186c68,
#                                      lands in the next release)
#   patches/rtsp-samplerate-validate.patch — unvalidated SampleRate SDP attr
#                                      (upstream FFmpeg/FFmpeg@a7b20ee10335)
# Both apply cleanly to the n9.0.2 tree (verified).
#
# Build runs in the official Termux package-builder Docker image, checked out
# at the same commit that produced the repo deb (6ca3c47…), so output is the
# identical recipe + our two patches. The ffmpeg source tarball sha256 is
# asserted against the recipe's pinned value.
#
# Requires: docker (works with the stock termux-package-builder docs flow),
#           curl, git, sha256sum.
# Output: out/ffmpeg_9.0.2-1_<arch>.deb for aarch64 and x86_64 — pass its
# directory to build-ffmpeg-aar.sh as FFMPEG_DEB_DIR.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work-deb}"
OUT="$HERE/out"
TP_COMMIT="6ca3c4716a8640622de2ae42d035e5c97a519739"
FF_SHA256="8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"
IMAGE="ghcr.io/termux/package-builder@sha256:44999857244619e25bc8e3427904a16d36cc0c0e087704af039e5bdf94814ac9"

mkdir -p "$WORK/cache" "$OUT"

# ---- 1. pinned termux-packages checkout -----------------------------------
if [ ! -d "$WORK/termux-packages/.git" ]; then
    git clone --filter=blob:none https://github.com/termux/termux-packages "$WORK/termux-packages"
fi
git -C "$WORK/termux-packages" checkout -q "$TP_COMMIT"

# recipe sanity: version + source hash must match what we claim to build
grep -q 'TERMUX_PKG_VERSION="9.0.2"' "$WORK/termux-packages/packages/ffmpeg/build.sh"
grep -q "TERMUX_PKG_SHA256=$FF_SHA256" "$WORK/termux-packages/packages/ffmpeg/build.sh"

# ---- 2. drop in security patches (auto-applied by termux build.sh flow) ---
cp "$HERE/patches/rtsp-redirect-limit.patch" \
   "$HERE/patches/rtsp-samplerate-validate.patch" \
   "$WORK/termux-packages/packages/ffmpeg/"

# bump revision so the deb is distinguishable from repo ffmpeg_9.0.2
grep -q TERMUX_PKG_REVISION "$WORK/termux-packages/packages/ffmpeg/build.sh" || \
  sed -i 's/^TERMUX_PKG_VERSION="9.0.2"/TERMUX_PKG_VERSION="9.0.2"\nTERMUX_PKG_REVISION=1/' \
    "$WORK/termux-packages/packages/ffmpeg/build.sh"

# ---- 3. docker build, both ABIs --------------------------------------------
# Mount the pinned repo over the image's checkout; -I installs the package's
# build deps from the termux apt repo first. Output lands in output/.
for arch in aarch64 x86_64; do
    docker run --rm --user root --device /dev/fuse --cap-add SYS_ADMIN \
        --security-opt apparmor=unconfined \
        -e BUILDER_UID="$(id -u)" -e BUILDER_GID="$(id -g)" \
        -v "$WORK/termux-packages:/home/builder/termux-packages" \
        -v "$WORK/cache:/home/builder/.termux-build" \
        -w /home/builder/termux-packages \
        "$IMAGE" bash -euc '
            if [ "$(id -u builder)" != "$BUILDER_UID" ]; then
                usermod -u "$BUILDER_UID" builder
            fi
            if [ "$(id -g builder)" != "$BUILDER_GID" ]; then
                groupmod -g "$BUILDER_GID" builder
            fi
            chown -R "$BUILDER_UID:$BUILDER_GID" /home/builder /data
            exec runuser -u builder -- ./build-package.sh -a "$1" -I ffmpeg
        ' _ "$arch"
    find "$WORK/termux-packages/output" -name "ffmpeg_*_${arch}.deb" \
        -exec cp {} "$OUT/" \;
done
