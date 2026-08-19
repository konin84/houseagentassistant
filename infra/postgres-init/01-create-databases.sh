#!/bin/bash
# Creates one database per service from POSTGRES_MULTIPLE_DATABASES.
#
# The official postgres image only creates a single database, but every service here
# owns its own. Sharing one database between services would let a schema change in
# one break another at runtime - the exact coupling microservices exist to avoid.
set -euo pipefail

if [ -z "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
  echo "POSTGRES_MULTIPLE_DATABASES not set, nothing to do"
  exit 0
fi

for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
  echo "Creating database '$db'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
      CREATE DATABASE "$db";
EOSQL
  # A matching _test database, so integration tests never run against dev data.
  echo "Creating database '${db}_test'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
      CREATE DATABASE "${db}_test";
EOSQL
done
