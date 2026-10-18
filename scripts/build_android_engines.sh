#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
NDK_VERSION="$(python3 -c 'import json; print(json.load(open("scripts/engine_sources.json"))["ndk"])')"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:?ANDROID_HOME is required}/ndk/$NDK_VERSION}"
python3 - "$NDK/source.properties" "$NDK_VERSION" <<'PY'
from pathlib import Path
import re, sys
revision = re.search(r'^Pkg.Revision\s*=\s*(\S+)', Path(sys.argv[1]).read_text(), re.M)
assert revision and revision[1] == sys.argv[2], 'ANDROID_NDK_HOME does not match the pinned NDK'
PY
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CXX="$TOOLCHAIN/bin/aarch64-linux-android24-clang++"
CMAKE="${ANDROID_HOME:?ANDROID_HOME is required}/cmake/3.22.1/bin/cmake"
test -x "$CXX"
test -x "$CMAKE"
# KataGo's legacy SHA code expects BSD endian names not exposed by bionic's sys/types.h.
"$CXX" -x c++ -include endian.h -fsyntax-only - <<'CPP'
#if !defined(BYTE_ORDER) || !defined(LITTLE_ENDIAN) || !defined(BIG_ENDIAN)
#error Android endian definitions are missing
#endif
static_assert(BYTE_ORDER == __BYTE_ORDER__, "System and compiler byte order disagree");
CPP
command -v 7z
python3 scripts/prepare_engine_sources.py
OUT="$ROOT/app/build/generated/engineLibs/arm64-v8a"
SOURCES="$ROOT/app/build/engines/sources"
mkdir -p "$OUT"
export PATH="$TOOLCHAIN/bin:$PATH"
LINK_FLAGS="-static-libstdc++ -pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
make -C "$SOURCES/stockfish/src" -j2 all ARCH=armv8 COMP=ndk COMPCXX="$CXX" \
    EXTRACXXFLAGS="-DNNUE_EMBEDDING_OFF -fPIE" EXTRALDFLAGS="$LINK_FLAGS"
make -C "$SOURCES/pikafish/src" -j2 all ARCH=armv8 COMP=ndk COMPCXX="$CXX" \
    EXTRACXXFLAGS="-fPIE" EXTRALDFLAGS="$LINK_FLAGS"
cp "$SOURCES/stockfish/src/stockfish" "$OUT/libstockfish.so"
cp "$SOURCES/pikafish/src/pikafish" "$OUT/libpikafish.so"
"$CMAKE" -S "$SOURCES/katago/cpp" -B app/build/engines/katago-build \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DUSE_BACKEND=EIGEN -DUSE_AVX2=OFF \
    -DCMAKE_CXX_FLAGS="-include endian.h" \
    -DBUILD_DISTRIBUTED=OFF -DUSE_TCMALLOC=OFF -DNO_GIT_REVISION=ON \
    -DEIGEN3_INCLUDE_DIRS="$SOURCES/eigen" \
    -DZLIB_INCLUDE_DIR="$TOOLCHAIN/sysroot/usr/include" \
    -DZLIB_LIBRARY="$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/24/libz.so" \
    -DCMAKE_DISABLE_FIND_PACKAGE_OpenCL=ON \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
"$CMAKE" --build app/build/engines/katago-build --target katago -j2
cp app/build/engines/katago-build/katago "$OUT/libkatago.so"
for engine in "$OUT"/*.so; do
    "$TOOLCHAIN/bin/llvm-strip" "$engine"
    chmod 755 "$engine"
    "$TOOLCHAIN/bin/llvm-readelf" --file-header --program-headers --wide "$engine"
done
python3 - "$OUT" "$TOOLCHAIN/bin/llvm-readelf" <<'PY'
from pathlib import Path
import subprocess,sys
for file in Path(sys.argv[1]).glob('*.so'):
    data=subprocess.check_output([sys.argv[2], '-lW', str(file)], text=True)
    loads=[line.split() for line in data.splitlines() if line.strip().startswith('LOAD ')]
    assert loads and all(int(line[-1],16)>=16384 for line in loads), file
print('All native engines have 16 KB aligned load segments')
PY
