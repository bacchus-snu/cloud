#!/bin/sh
# Builds the Debian packages of this tree inside the image defined by the
# Dockerfile next to this script and moves them to <output dir>.
#
#   snucse/packaging/build-debs.sh <output dir>
#
# The tree is built in place (target/, ui/node_modules, debian/tmp, ...), so
# run it on a fresh checkout or an export rather than on a working copy.
# Package versions come from debian/changelog; the tree needs no .git.
#
# Environment:
#   M2_REPO       Maven local repository to reuse across builds; without it the
#                 build downloads everything into the container and drops it
#   MAVEN_OPTS    passed to Maven
#   NODE_OPTIONS  passed to Node.js (the UI build); the default heap of 3 GiB
#                 is what npm needs to install the UI's dependencies
#   BUILD_MEMORY  memory limit of the build container
set -eu

out=${1:?usage: build-debs.sh <output dir>}
here=$(cd "$(dirname "$0")" && pwd -P)
src=$(cd "$here/../.." && pwd -P)
mkdir -p "$out"
out=$(cd "$out" && pwd -P)

image=snucse-cloud-deb-builder
docker build -t "$image" "$here"

set -- --rm --user "$(id -u):$(id -g)" \
	-v "$src:/build/src/cloud" -v "$out:/build/out" -w /build/src/cloud \
	-e MAVEN_OPTS="${MAVEN_OPTS:-}" -e NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=3072}"
if [ -n "${M2_REPO:-}" ]; then
	mkdir -p "$M2_REPO"
	set -- "$@" -v "$(cd "$M2_REPO" && pwd -P):/build/home/.m2/repository"
fi
if [ -n "${BUILD_MEMORY:-}" ]; then
	set -- "$@" --memory "$BUILD_MEMORY"
fi

# Only the KVM system VM template is bundled.
docker run "$@" "$image" sh -euc '
	ACS_BUILD_OPTS="-B -ntp -Dsystemvm-kvm -DskipTests" dpkg-buildpackage -b -uc -us
	mv ../*.deb ../*.buildinfo ../*.changes /build/out/
'
ls -l "$out"
