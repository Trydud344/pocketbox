#!/usr/bin/env bash
# Pocketbox - build PRoot (with static talloc) for Android NDK.
# Outputs: build/android/<abi>/libproot.so  (PIE executable packaged as .so)
#          build/android/<abi>/libproot-loader.so (freestanding loader)
# Usage: ./build-proot-android.sh [arm64-v8a|x86_64|all]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
THIRD="$HERE/third_party"
NDK="${NDK:-$HOME/android-sdk/ndk/28.2.13676358}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=29
SRC="$THIRD/proot/src"
TALLOC="$THIRD/talloc-2.4.2"
OUT="$ROOT/build/android"

ABI="$1"
case "$ABI" in
  arm64-v8a)
    TARGET=aarch64-linux-android$API
    OBJFMT=elf64-littleaarch64
    BINARCH=aarch64
    LOADER_ADDR=0x2000000000
    ;;
  x86_64)
    TARGET=x86_64-linux-android$API
    OBJFMT=elf64-x86-64
    BINARCH=i386:x86-64
    LOADER_ADDR=0x600000000000
    ;;
  *)
    echo "usage: $0 arm64-v8a|x86_64" >&2; exit 1;;
esac

CC="$TOOLCHAIN/bin/${TARGET}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
STRIP="$TOOLCHAIN/bin/llvm-strip"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"

WORK="$OUT/$ABI"
mkdir -p "$WORK"

echo "== [$ABI] building talloc (static)"
# Build talloc against a tiny prelude instead of samba's replace.h:
#   -D_LIBREPLACE_REPLACE_H makes replace.h a no-op; -include supplies the decls.
TALLOCCFLAGS="-D_GNU_SOURCE -DNO_CONFIG_H -D_LIBREPLACE_REPLACE_H \
 -DHAVE_VA_COPY -DHAVE___VA_COPY -DHAVE_ASPRINTF_DECL -DTALLOC_BUILD"
$CC --target=$TARGET -O2 -fPIC -include "$TALLOC/../talloc-pb-prelude.h" \
    -I"$TALLOC" -I"$TALLOC/lib/replace" $TALLOCCFLAGS -c "$TALLOC/talloc.c" -o "$WORK/talloc.o"

echo "== [$ABI] building proot loader"
mkdir -p "$WORK/loader"
$CC --target=$TARGET -O2 -fPIC -ffreestanding \
    -I"$SRC" -c "$SRC/loader/loader.c" -o "$WORK/loader/loader.o"
$CC --target=$TARGET -O2 -fPIC -ffreestanding \
    -I"$SRC" -c "$SRC/loader/assembly.S" -o "$WORK/loader/assembly.o"
$CC --target=$TARGET -nostdlib -static -Wl,-Ttext=$LOADER_ADDR,-z,noexecstack,--build-id=none \
    -o "$WORK/loader/loader.elf" "$WORK/loader/loader.o" "$WORK/loader/assembly.o"
$STRIP "$WORK/loader/loader.elf"
cp "$WORK/loader/loader.elf" "$WORK/libproot-loader.so"

echo "== [$ABI] embedding loader into proot"
( cd "$WORK/loader" && $OBJCOPY --input-target binary --output-target $OBJFMT --binary-architecture $BINARCH \
    "loader.elf" "loader-wrapped.o" )

echo "== [$ABI] feature checks"
cat > "$WORK/.check_process_vm.c" <<'EOF'
#include <sys/uio.h>
int main(void) { process_vm_readv(0, NULL, 0, NULL, 0, 0); return 0; }
EOF
cat > "$WORK/.check_seccomp_filter.c" <<'EOF'
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <sys/prctl.h>
int main(void) { prctl(SECCOMP_MODE_FILTER, 0); return 0; }
EOF
BUILD_H="$WORK/build.h"
{
  echo '/* auto-generated for Pocketbox proot build */'
  echo '#ifndef BUILD_H'
  echo '#define BUILD_H'
  echo '#define VERSION "5.4.0-pocketbox"'
  if $CC --target=$TARGET -o "$WORK/.check_process_vm" "$WORK/.check_process_vm.c" 2>/dev/null; then
    echo '#define HAVE_PROCESS_VM'
  fi
  if $CC --target=$TARGET -o "$WORK/.check_seccomp_filter" "$WORK/.check_seccomp_filter.c" 2>/dev/null; then
    echo '#define HAVE_SECCOMP_FILTER'
  fi
  echo '#endif'
} > "$BUILD_H"

echo "== [$ABI] building proot"
PROOT_CFLAGS="-O2 -fPIC -std=gnu99 -Wall -Werror-implicit-function-declaration \
  -I$SRC -I$TALLOC -I$WORK -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
  -UHAS_LOADER_32BIT"

OBJECTS=""
while IFS= read -r f; do
  obj="$WORK/${f%.c}.o"
  mkdir -p "$(dirname "$obj")"
  $CC --target=$TARGET $PROOT_CFLAGS -c "$SRC/$f" -o "$obj"
  OBJECTS="$OBJECTS $obj"
done <<'SRCS'
cli/cli.c
cli/proot.c
cli/note.c
execve/enter.c
execve/exit.c
execve/shebang.c
execve/elf.c
execve/ldso.c
execve/auxv.c
execve/aoxp.c
path/binding.c
path/glue.c
path/canon.c
path/path.c
path/proc.c
path/temp.c
syscall/seccomp.c
syscall/syscall.c
syscall/chain.c
syscall/enter.c
syscall/exit.c
syscall/sysnum.c
syscall/socket.c
syscall/heap.c
syscall/rlimit.c
tracee/tracee.c
tracee/mem.c
tracee/reg.c
tracee/event.c
ptrace/ptrace.c
ptrace/user.c
ptrace/wait.c
extension/extension.c
extension/kompat/kompat.c
extension/fake_id0/fake_id0.c
extension/link2symlink/link2symlink.c
extension/portmap/portmap.c
extension/portmap/map.c
SRCS

$CC --target=$TARGET -o "$WORK/libproot.so" \
    $OBJECTS "$WORK/talloc.o" "$WORK/loader/loader-wrapped.o" \
    -Wl,-z,noexecstack -Wl,--build-id=none -ldl -lm

$STRIP --strip-unneeded "$WORK/libproot.so"

echo "== [$ABI] DONE"
ls -la "$WORK/libproot.so" "$WORK/libproot-loader.so"
