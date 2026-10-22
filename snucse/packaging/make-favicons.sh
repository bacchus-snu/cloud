#!/bin/sh
# Renders snucse/branding/favicon.png and favicon.ico from favicon.svg inside a container
# (rsvg-convert and Python 3 are installed in the image, not on the host). Run it after
# favicon.svg is replaced; the UI tests fail while favicon.png does not match favicon.svg.
#
#   snucse/packaging/make-favicons.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd -P)
brand=$(cd "$here/../branding" && pwd -P)

image=snucse-cloud-favicons
docker build -q -t "$image" - <<'EOF'
FROM alpine:3.21
RUN apk add --no-cache rsvg-convert python3
EOF

docker run --rm --user "$(id -u):$(id -g)" \
	-v "$brand:/brand" -v "$here/make-favicons.py:/make-favicons.py:ro" \
	"$image" python3 -I /make-favicons.py /brand
ls -l "$brand"
