#!/usr/bin/env bash
#
# Bring up the integration-test dependencies locally / in CI: a Postgres with the "microcloud"
# schema. Mirrors what the tests expect (a real DB — MicroCloud's tests are integration tests).
#
set -euo pipefail

docker rm -f microcloud-postgres >/dev/null 2>&1 || true

docker run -d --name microcloud-postgres \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=postgres \
    -p 5432:5432 \
    postgres:16.2 >/dev/null

echo "waiting for postgres..."
for _ in $(seq 1 30); do
    if docker exec microcloud-postgres pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 1
done
docker exec microcloud-postgres psql -U postgres -d postgres \
    -c 'CREATE SCHEMA IF NOT EXISTS microcloud;'
echo "postgres up with 'microcloud' schema on localhost:5432"
