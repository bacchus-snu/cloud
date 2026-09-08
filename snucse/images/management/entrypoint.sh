#!/bin/bash
# Renders db.properties from the environment on every start, deploys the schema
# once when the databases do not exist yet, then runs the management server in
# the foreground as the unprivileged "cloud" user.
#
# Required:  DB_HOST DB_PASSWORD CLOUD_SECRET_KEY DB_SECRET_KEY
# Optional:  DB_USER (cloud) DB_PORT (3306) DB_ROOT_USER (root) DB_ROOT_PASSWORD
#            MS_IP (defaults to the first address of this host)
#            CLOUDSTACK_JAVA_OPTS (replaces the packaged JAVA_OPTS)
#
# CLOUD_SECRET_KEY encrypts the credentials inside db.properties (encryption
# type "env"); DB_SECRET_KEY encrypts sensitive values stored in the database.
# Both must stay constant for the lifetime of the database.
set -euo pipefail

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${CLOUD_SECRET_KEY:?CLOUD_SECRET_KEY is required}"
: "${DB_SECRET_KEY:?DB_SECRET_KEY is required}"
DB_USER=${DB_USER:-cloud}
DB_PORT=${DB_PORT:-3306}
DB_ROOT_USER=${DB_ROOT_USER:-root}
MS_IP=${MS_IP:-$(hostname -i | awk '{print $1}')}

log() { echo "[entrypoint] $*"; }

wait_for_db() {
	local i
	for i in $(seq 1 120); do
		if (exec 3<>"/dev/tcp/${DB_HOST}/${DB_PORT}") 2>/dev/null; then
			exec 3>&-
			return 0
		fi
		sleep 5
	done
	log "database ${DB_HOST}:${DB_PORT} not reachable"
	return 1
}

databases_exist() {
	local count
	count=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_ROOT_USER" -p"$DB_ROOT_PASSWORD" -N -s -e \
		"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('cloud', 'cloud_usage')")
	[ "$count" != "0" ]
}

wait_for_db

if [ -n "${DB_ROOT_PASSWORD:-}" ] && ! databases_exist; then
	log "cloud databases not found; deploying schema as ${DB_ROOT_USER}"
	cloudstack-setup-databases "${DB_USER}:${DB_PASSWORD}@${DB_HOST}:${DB_PORT}" \
		--deploy-as="${DB_ROOT_USER}:${DB_ROOT_PASSWORD}" \
		-e env -k "$DB_SECRET_KEY" -i "$MS_IP"
fi

# no --deploy-as: only rewrites db.properties, never touches the schema
cloudstack-setup-databases "${DB_USER}:${DB_PASSWORD}@${DB_HOST}:${DB_PORT}" \
	-e env -k "$DB_SECRET_KEY" -i "$MS_IP"

/usr/share/cloudstack-common/scripts/installer/pre-check.sh

# shellcheck disable=SC1091
. /etc/default/cloudstack-management
JAVA_OPTS=${CLOUDSTACK_JAVA_OPTS:-$JAVA_OPTS}

# /var/cloudstack/management holds the system VM SSH key that the server rewrites
# from the database on startup; /var/cloudstack/mnt is the parent of secondary
# storage mount points used while seeding templates.
for d in /var/log/cloudstack/management /var/cache/cloudstack/management /var/lib/cloudstack/management \
	/var/cloudstack/management /var/cloudstack/mnt /var/tmp; do
	mkdir -p "$d"
	chown cloud:cloud "$d"
done

cd /var/log/cloudstack/management
log "starting ${BOOTSTRAP_CLASS} as cloud (cluster.node.IP=${MS_IP})"
# shellcheck disable=SC2086
exec setpriv --reuid=cloud --regid=cloud --init-groups \
	env CLOUD_SECRET_KEY="$CLOUD_SECRET_KEY" HOME=/var/lib/cloudstack/management \
	/usr/bin/java ${JAVA_DEBUG:-} $JAVA_OPTS -cp "$CLASSPATH" "$BOOTSTRAP_CLASS"
