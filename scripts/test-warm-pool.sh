#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$task_root/logs" "$task_root/.maven-cache"
log="$task_root/logs/warm-tests-$(date -u +%Y%m%dT%H%M%SZ).log"
exec > >(tee -a "$log") 2>&1
printf '%s starting isolated warm-pool tests\n' "$(date -u +%FT%TZ)"
docker network inspect microcloud-warm-test >/dev/null 2>&1 || docker network create microcloud-warm-test
if ! docker container inspect microcloud-warm-test-pg >/dev/null 2>&1; then
  docker run -d --name microcloud-warm-test-pg --network microcloud-warm-test \
    --memory=384m --cpus=0.5 -e POSTGRES_PASSWORD=postgres \
    postgres:16@sha256:f1c3376c26f2609ab9f29f71f824103fe2fcd8ee0346485cb6122a4f93df6f94
fi
docker start microcloud-warm-test-pg >/dev/null
for attempt in {1..30}; do
  docker exec microcloud-warm-test-pg pg_isready -U postgres && break
  sleep 1
done
docker exec microcloud-warm-test-pg psql -U postgres -c 'CREATE SCHEMA IF NOT EXISTS microcloud'
docker run --rm --name microcloud-warm-test-maven --network microcloud-warm-test \
  --cpus=2 --memory=3g -v "$task_root:/workspace" -v "$task_root/.maven-cache:/root/.m2" \
  -w /workspace/backend -e MAVEN_OPTS=-Xmx1536m \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://microcloud-warm-test-pg:5432/postgres \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=update \
  maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e \
  mvn -B -ntp install
printf '%s tests complete\n' "$(date -u +%FT%TZ)"
