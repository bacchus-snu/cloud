FROM ubuntu:24.04

# Version of the cloudstack-common and cloudstack-management packages in the
# build context's debs/ directory (snucse/packaging/build-debs.sh output).
ARG DEB_VERSION

ENV DEBIAN_FRONTEND=noninteractive

# openssh-client provides ssh-keygen, which the server needs to create the
# system VM key pair on first start (not declared as a package dependency);
# curl serves exec health probes against the local API.
RUN --mount=type=bind,source=debs,target=/tmp/debs \
	set -eux \
	&& test -n "${DEB_VERSION}" \
	&& apt-get update \
	&& apt-get install -y --no-install-recommends \
		"/tmp/debs/cloudstack-common_${DEB_VERSION}_all.deb" \
		"/tmp/debs/cloudstack-management_${DEB_VERSION}_all.deb" \
		ca-certificates curl openssh-client \
	&& rm -rf /var/lib/apt/lists/* \
	# the packaged log4j config only writes to files; also emit to stdout for the container log,
	# and drop the syslog appender references since there is no local syslog daemon
	&& sed -i -e 's|<ThresholdFilter level="OFF" onMatch="ACCEPT" onMismatch="DENY"/>|<ThresholdFilter level="INFO" onMatch="ACCEPT" onMismatch="DENY"/>|' \
		-e '/<AppenderRef ref="SYSLOG"\/>/d' /etc/cloudstack/management/log4j-cloud.xml

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

EXPOSE 8080 8250 9090

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
