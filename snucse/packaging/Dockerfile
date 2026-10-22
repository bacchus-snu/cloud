# Build environment of the Debian packages (build-debs.sh): Ubuntu 24.04 with
# OpenJDK 17 and the build dependencies debian/control lists. Maven and Node.js
# come from their upstream archives, pinned by checksum. The UI's webpack 4
# toolchain needs Node.js 16: later releases link OpenSSL 3, whose default
# provider lacks the MD4 hash webpack 4 uses, and Ubuntu packages no Node.js 16.
FROM ubuntu:24.04

ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
ARG NODE_VERSION=16.20.2
ARG NODE_SHA256=874463523f26ed528634580247f403d200ba17a31adf2de98a7b124c6eb33d87

ENV DEBIAN_FRONTEND=noninteractive

RUN set -eux \
	&& apt-get update \
	&& apt-get install -y --no-install-recommends \
		build-essential ca-certificates curl debhelper dpkg-dev fakeroot genisoimage git \
		lsb-release openjdk-17-jdk python3 python3-mysql.connector python3-setuptools \
		wget xz-utils \
	&& rm -rf /var/lib/apt/lists/* \
	&& curl -fsSLo /tmp/maven.tar.gz "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
	&& echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c \
	&& mkdir /opt/maven \
	&& tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 \
	&& curl -fsSLo /tmp/node.tar.xz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
	&& echo "${NODE_SHA256}  /tmp/node.tar.xz" | sha256sum -c \
	&& mkdir /opt/node \
	&& tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 \
	&& rm -f /tmp/maven.tar.gz /tmp/node.tar.xz \
	# empty packages standing for the two archives, so that dpkg-buildpackage can
	# check the Build-Depends on maven and nodejs
	&& stub() { mkdir -p "/tmp/$1/DEBIAN" \
		&& printf 'Package: %s\nVersion: %s\nArchitecture: all\nMaintainer: local <root@localhost>\nDescription: %s installed from its upstream archive under /opt\n' "$1" "$2" "$1" > "/tmp/$1/DEBIAN/control" \
		&& dpkg-deb --build "/tmp/$1" "/tmp/$1.deb" \
		&& dpkg -i "/tmp/$1.deb" \
		&& rm -rf "/tmp/$1" "/tmp/$1.deb"; } \
	&& stub maven "${MAVEN_VERSION}" \
	&& stub nodejs "${NODE_VERSION}" \
	# the build runs as the caller's uid, which has no home of its own here
	&& mkdir -p /build/home /build/src \
	&& chmod 1777 /build/home /build/src

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
	PATH=/opt/maven/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
	HOME=/build/home
