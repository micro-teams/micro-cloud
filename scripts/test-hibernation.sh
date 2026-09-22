#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$task_root/logs" "$task_root/.maven-cache"
log="$task_root/logs/hibernation-tests-$(date -u +%Y%m%dT%H%M%SZ).log"
exec > >(tee -a "$log") 2>&1
printf '%s starting isolated hibernation tests\n' "$(date -u +%FT%TZ)"
docker network inspect microcloud-hibernation-test >/dev/null 2>&1 || docker network create microcloud-hibernation-test
if ! docker container inspect microcloud-hibernation-pg >/dev/null 2>&1; then
  docker run -d --name microcloud-hibernation-pg --network microcloud-hibernation-test \
    -e POSTGRES_PASSWORD=postgres \
    postgres:16@sha256:f1c3376c26f2609ab9f29f71f824103fe2fcd8ee0346485cb6122a4f93df6f94
fi
docker start microcloud-hibernation-pg >/dev/null
for attempt in {1..30}; do
  docker exec microcloud-hibernation-pg pg_isready -U postgres && break
  sleep 1
done
docker exec microcloud-hibernation-pg psql -U postgres -c 'CREATE SCHEMA IF NOT EXISTS microcloud'
docker exec microcloud-hibernation-pg dropdb -U postgres --if-exists hibernation_migration
docker exec microcloud-hibernation-pg createdb -U postgres hibernation_migration
docker exec -i microcloud-hibernation-pg psql -v ON_ERROR_STOP=1 -U postgres -d hibernation_migration <<'SQL'
CREATE SCHEMA microcloud;
CREATE TABLE microcloud.machine (id bigint PRIMARY KEY, status text NOT NULL CHECK
  (status IN ('PROVISIONING', 'STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'DELETING', 'DELETED', 'ERROR')));
CREATE TABLE microcloud.machine_event (action text NOT NULL CHECK
  (action IN ('PROVISION', 'START', 'SHUTDOWN', 'STOP', 'DELETE', 'AI_SWITCH', 'AI_LOGIN')));
INSERT INTO microcloud.machine VALUES (1, 'RUNNING');
INSERT INTO microcloud.machine_event VALUES ('START');
SQL
docker exec -i microcloud-hibernation-pg psql -v ON_ERROR_STOP=1 -U postgres -d hibernation_migration \
  < "$task_root/deploy/migrations/machine-suspend.sql"
docker exec -i microcloud-hibernation-pg psql -v ON_ERROR_STOP=1 -U postgres -d hibernation_migration <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM microcloud.machine WHERE id = 1 AND status = 'RUNNING') THEN
    RAISE EXCEPTION 'migration changed the existing machine';
  END IF;
END $$;
UPDATE microcloud.machine SET status = 'SUSPENDING';
UPDATE microcloud.machine SET status = 'SUSPENDED';
UPDATE microcloud.machine SET status = 'RESUMING';
INSERT INTO microcloud.machine_event VALUES ('SUSPEND'), ('RESUME');
DO $$ BEGIN
  BEGIN
    UPDATE microcloud.machine SET status = 'INVALID';
    RAISE EXCEPTION 'status constraint missing';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO microcloud.machine_event VALUES ('INVALID');
    RAISE EXCEPTION 'action constraint missing';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
SQL
printf '%s migration verification passed\n' "$(date -u +%FT%TZ)"
docker run --rm --name microcloud-hibernation-maven --network microcloud-hibernation-test \
  -v "$task_root:/workspace" -v "$task_root/.maven-cache:/root/.m2" \
  -w /workspace/backend -e MAVEN_OPTS=-Xmx1536m \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://microcloud-hibernation-pg:5432/postgres \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=update \
  maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e \
  mvn -B -ntp install "$@"
printf '%s tests complete\n' "$(date -u +%FT%TZ)"
