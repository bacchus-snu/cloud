# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# The UI reads its brand files from snucse/branding/ beside it, so the build context is the root of the
# source tree; ui/Dockerfile.dockerignore keeps the context to those two directories.
# Build example, from the root of the source tree: docker build -f ui/Dockerfile -t <name> .

FROM node:16-bullseye AS build

LABEL Description="Role-based progressive web UI of the management server"
LABEL License=ApacheV2

WORKDIR /build/ui

# the webpack build and the dependency install need more than the default heap
ENV NODE_OPTIONS=--max-old-space-size=3072

COPY ui/ /build/ui/
COPY snucse/branding/ /build/snucse/branding/
RUN npm install
RUN npm run build

FROM nginx:alpine AS runtime

LABEL org.opencontainers.image.title="Management server UI" \
	org.opencontainers.image.description="A modern role-based progressive web UI" \
	org.opencontainers.image.licenses="Apache-2.0" \
	org.opencontainers.image.ref.name="latest"

COPY --from=build /build/ui/dist/. /usr/share/nginx/html/
