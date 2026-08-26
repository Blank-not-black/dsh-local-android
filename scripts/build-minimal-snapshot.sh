#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <full-snapshot.tar.xz> <minimal-snapshot.tar.xz>" >&2
  exit 2
fi

input_archive=$1
output_archive=$2
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)

if [[ ! -f "$input_archive" ]]; then
  echo "input snapshot not found: $input_archive" >&2
  exit 1
fi

input_real=$(readlink -f -- "$input_archive")
output_real=$(readlink -f -- "$output_archive" 2>/dev/null || true)
if [[ -n "$output_real" && "$input_real" == "$output_real" ]]; then
  echo "input and output snapshots must be different files" >&2
  exit 1
fi

stage_dir=$(mktemp -d /tmp/dsh-minimal-snapshot.XXXXXX)
cleanup() {
  rm -rf -- "$stage_dir"
}
trap cleanup EXIT

echo "extracting full snapshot..."
tar -xJf "$input_archive" -C "$stage_dir"

bin_dir="$stage_dir/usr/bin"
remove_bins() {
  local pattern
  for pattern in "$@"; do
    find "$bin_dir" -maxdepth 1 -mindepth 1 \
      \( -type f -o -type l \) -name "$pattern" -delete
  done
}

# The DSH runtime can execute user commands through bash, but its startup does
# not require a compiler, linker, assembler, or native build toolchain.
remove_bins \
  clang clang++ 'clang++-*' 'clang-*' gcc 'gcc-*' g++ 'g++-*' cpp c++ \
  'aarch64-linux-android-*' llvm* llc lli 'lld*' ld.lld ld64.lld wasm-ld opt \
  c++filt sancov sanstats 'scan-build*' scan-view run-clang-tidy intercept-build \
  analyze-build modularize bugpoint amdgpu-arch nvptx-arch offload-arch \
  find-all-symbols ar as ld nm objcopy objdump ranlib readelf size strings strip \
  addr2line elfedit gold make

# Compiler headers, build metadata, and compiler-only libraries are not part
# of the default app runtime. Keep shared runtime libraries such as libc++.
for relative_path in \
  usr/include \
  usr/lib/cmake \
  usr/lib/libear \
  usr/lib/libscanbuild \
  usr/share/clang \
  usr/share/clang-doc \
  usr/share/cmake-4.4 \
  usr/share/opt-viewer \
  usr/share/scan-build \
  usr/share/scan-view; do
  rm -rf -- "$stage_dir/$relative_path"
done

for pattern in libbfd\* libctf\* liblld\* libomp\* libopcodes\* libsframe\* libcompiler_rt\*; do
  find "$stage_dir/usr/lib" -maxdepth 1 -mindepth 1 -name "$pattern" -delete
done

# Python, Perl, and Ruby are optional scripting packs. Node remains because it
# is the embedded DSH runtime and the gateway launcher depends on it.
remove_bins python python3 'python3.*' 'python*config' 'pip*' 'perl*' 'ruby*' \
  irb gem rake bundle bundler
for relative_path in \
  usr/lib/python3.14 \
  usr/lib/ruby \
  usr/lib/perl5 \
  usr/include/python3.14 \
  usr/include/ruby-3.4.0 \
  usr/share/python \
  usr/share/perl5 \
  usr/share/ruby; do
  rm -rf -- "$stage_dir/$relative_path"
done
for pattern in libpython\* libruby\* libperl\*; do
  find "$stage_dir/usr/lib" -maxdepth 1 -mindepth 1 -name "$pattern" -delete
done

# Package managers and the DSH marketplace installer are not needed to boot a
# fixed embedded profile. Future capability packs can restore them explicitly.
remove_bins apt 'apt-*' 'apt.*' dpkg 'dpkg-*' 'dpkg.*' pkg npm npx pnpm corepack
for relative_path in \
  usr/lib/apt \
  usr/libexec/dpkg \
  usr/lib/node_modules/npm \
  usr/lib/node_modules/pnpm \
  usr/lib/node_modules/corepack \
  usr/etc/apt \
  usr/var/lib/dpkg \
  usr/var/cache/apt \
  usr/share/pacman; do
  rm -rf -- "$stage_dir/$relative_path"
done

# ADB/device image tools belong to the later device-management pack.
remove_bins adb fastboot avbtool append2simg img2simg ext2simg simg2img \
  mkbootimg unpack_bootimg repack_bootimg e2fsdroid lpadd lpdump lpflash \
  lpmake lpunpack make_f2fs
rm -rf -- "$stage_dir/usr/share/android-tools"

# Keep the console's basic shell utilities, but omit editors, network
# diagnostics, and large documentation trees from the default image.
remove_bins vim 'vim*' nano dialog less lessecho lesskey zsh 'socat*' nc \
  netcat 'netcat*' ping ping6 ftp tftp 'telnet*' ifconfig netstat route rarp \
  arp ipmaddr iptunnel mii-tool
for relative_path in \
  usr/share/vim \
  usr/share/nano \
  usr/share/zsh \
  usr/share/man \
  usr/share/info \
  usr/share/doc \
  usr/share/emacs \
  usr/share/examples \
  usr/share/fish \
  usr/share/applications \
  usr/share/icons \
  usr/share/bash-completion; do
  rm -rf -- "$stage_dir/$relative_path"
done

# Keep only the four profile plugins required by the minimal boot path. The
# profile directory itself is retained so future packs can be layered on top.
profile_dir="$stage_dir/home/.dsh/profiles/web"
profile_modules="$profile_dir/node_modules"
if [[ -d "$profile_modules" ]]; then
  for entry in "$profile_modules"/*; do
    [[ -e "$entry" || -L "$entry" ]] || continue
    case "$(basename -- "$entry")" in
      @dsh-android) ;;
      *) rm -rf -- "$entry" ;;
    esac
  done
  for entry in "$profile_modules/@dsh-android"/*; do
    [[ -e "$entry" || -L "$entry" ]] || continue
    case "$(basename -- "$entry")" in
      dsh-shell-termux|dsh-host-web-compat|dsh-client-ui-responsive) ;;
      *) rm -rf -- "$entry" ;;
    esac
  done
  find "$profile_modules" -mindepth 1 -maxdepth 1 \
    \( -name '.bin' -o -name '.package-lock.json' -o -name '.package-map.json' -o -name '.pnpm' \) \
    -exec rm -rf -- {} +
fi

minimal_profile="$repo_root/runtime/minimal/cordis.patch.yml"
install -m 0644 "$minimal_profile" "$profile_dir/cordis.patch.yml"

mkdir -p -- "$(dirname -- "$output_archive")"
rm -f -- "$output_archive"
echo "packing minimal snapshot..."
tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
  -cJf "$output_archive" -C "$stage_dir" home usr

echo "minimal snapshot: $output_archive"
du -h "$output_archive"
sha256sum "$output_archive"
