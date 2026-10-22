FROM ubuntu:24.04

ARG CS_VERSION=4.23.0.0
ARG CS_POOL=https://download.cloudstack.org/debian/dists/bookworm/4.23/pool
ARG CS_COMMON_SHA256=bdad2a4905334eea711c006f1d1c24409d53a20b0e02e9f5249c2e28a50a7c54
ARG CS_MANAGEMENT_SHA256=450e1289b2729672491904f88065df63472c6578640dac908bc8f72b0a978794

ENV DEBIAN_FRONTEND=noninteractive

# The upstream .deb files are architecture-independent and identical across the
# debian/ and ubuntu/ repositories; they are pinned by SHA256 rather than fetched
# through an apt repository so that the build does not depend on any repository
# signing key or Release file. nfs-common is required for the management server
# to mount secondary storage when seeding the system VM template; openssh-client
# provides ssh-keygen, which the server needs to create the system VM key pair
# on first start (not declared as a package dependency).
RUN set -eux \
	&& apt-get update \
	&& apt-get install -y --no-install-recommends ca-certificates curl \
	&& curl -fsSLo /tmp/cloudstack-common.deb "${CS_POOL}/cloudstack-common_${CS_VERSION}_all.deb" \
	&& curl -fsSLo /tmp/cloudstack-management.deb "${CS_POOL}/cloudstack-management_${CS_VERSION}_all.deb" \
	&& echo "${CS_COMMON_SHA256}  /tmp/cloudstack-common.deb" | sha256sum -c \
	&& echo "${CS_MANAGEMENT_SHA256}  /tmp/cloudstack-management.deb" | sha256sum -c \
	&& apt-get install -y --no-install-recommends /tmp/cloudstack-common.deb /tmp/cloudstack-management.deb nfs-common openssh-client \
	&& rm -f /tmp/cloudstack-common.deb /tmp/cloudstack-management.deb \
	&& rm -rf /var/lib/apt/lists/* \
	# the packaged log4j config only writes to files; also emit to stdout for the container log,
	# and drop the syslog appender references since there is no local syslog daemon
	&& sed -i -e 's|<ThresholdFilter level="OFF" onMatch="ACCEPT" onMismatch="DENY"/>|<ThresholdFilter level="INFO" onMatch="ACCEPT" onMismatch="DENY"/>|' \
		-e '/<AppenderRef ref="SYSLOG"\/>/d' /etc/cloudstack/management/log4j-cloud.xml

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

EXPOSE 8080 8250 9090

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
