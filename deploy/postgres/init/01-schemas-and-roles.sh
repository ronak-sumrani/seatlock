#!/usr/bin/env bash
# Schema-per-service on one Postgres (spec §3, ADR-011).
#
# Each service gets one login role that owns its schema(s) and has no rights anywhere else, so
# "each service owns its data" is enforced by Postgres, not by convention. checker_ro can read
# every schema (for the invariant checker) and write nothing.
#
# The postgres image runs this once, as POSTGRES_USER, only when the data volume is empty.
# It is a .sh file and not .sql because the passwords come from environment variables.
set -euo pipefail

: "${USER_CATALOG_DB_PASSWORD:?missing}"
: "${INVENTORY_DB_PASSWORD:?missing}"
: "${BOOKING_DB_PASSWORD:?missing}"
: "${PAYMENT_DB_PASSWORD:?missing}"
: "${CHECKER_RO_DB_PASSWORD:?missing}"

# Passwords go in as psql variables and are used as :'name', which psql quotes as a SQL string
# literal. That way a password containing a quote can't break or inject into the SQL.
psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$POSTGRES_DB" \
    -v uc_pw="$USER_CATALOG_DB_PASSWORD" \
    -v inv_pw="$INVENTORY_DB_PASSWORD" \
    -v bk_pw="$BOOKING_DB_PASSWORD" \
    -v pay_pw="$PAYMENT_DB_PASSWORD" \
    -v ro_pw="$CHECKER_RO_DB_PASSWORD" <<-'EOSQL'
	-- 1. Remove the rights every role gets by default (the PUBLIC pseudo-role). Postgres 15+
	--    already removes CREATE on schema public, but CONNECT on the database and USAGE on public
	--    are still granted to everyone. After this, a role can do only what we grant below.
	REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
	REVOKE ALL ON SCHEMA public FROM PUBLIC;

	-- 2. One login role per service. It can't create databases or roles, and it is not a superuser.
	CREATE ROLE user_catalog_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'uc_pw';
	CREATE ROLE inventory_user    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'inv_pw';
	CREATE ROLE booking_user      LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'bk_pw';
	CREATE ROLE payment_user      LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pay_pw';
	CREATE ROLE checker_ro        LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'ro_pw';

	GRANT CONNECT ON DATABASE :"db"
	    TO user_catalog_user, inventory_user, booking_user, payment_user, checker_ro;

	-- 3. Each schema is owned by its service role. The owner has every right inside the schema
	--    (create tables, Flyway history, ...). Nobody else is granted USAGE, so for other service
	--    roles the schema's tables can't even be looked up: "permission denied for schema".
	--    user-catalog-service owns both auth and catalog. They are separate modules in one
	--    deployable (spec §4) and can be split into two roles later.
	CREATE SCHEMA auth      AUTHORIZATION user_catalog_user;
	CREATE SCHEMA catalog   AUTHORIZATION user_catalog_user;
	CREATE SCHEMA inventory AUTHORIZATION inventory_user;
	CREATE SCHEMA booking   AUTHORIZATION booking_user;
	CREATE SCHEMA payment   AUTHORIZATION payment_user;

	-- 4. Unqualified table names resolve to the role's own schema.
	ALTER ROLE user_catalog_user SET search_path = catalog, auth;
	ALTER ROLE inventory_user    SET search_path = inventory;
	ALTER ROLE booking_user      SET search_path = booking;
	ALTER ROLE payment_user      SET search_path = payment;

	-- 5. checker_ro: read everything, write nothing.
	GRANT USAGE ON SCHEMA auth, catalog, inventory, booking, payment TO checker_ro;
	GRANT SELECT ON ALL TABLES IN SCHEMA auth, catalog, inventory, booking, payment TO checker_ro;
	-- GRANT ... ON ALL TABLES only covers tables that exist now, and right now there are none.
	-- Flyway creates the real tables later while logged in as the owner role. Default privileges
	-- make every table that the owner creates in its schema readable by checker_ro automatically.
	ALTER DEFAULT PRIVILEGES FOR ROLE user_catalog_user IN SCHEMA auth, catalog
	    GRANT SELECT ON TABLES TO checker_ro;
	ALTER DEFAULT PRIVILEGES FOR ROLE inventory_user IN SCHEMA inventory
	    GRANT SELECT ON TABLES TO checker_ro;
	ALTER DEFAULT PRIVILEGES FOR ROLE booking_user IN SCHEMA booking
	    GRANT SELECT ON TABLES TO checker_ro;
	ALTER DEFAULT PRIVILEGES FOR ROLE payment_user IN SCHEMA payment
	    GRANT SELECT ON TABLES TO checker_ro;
	-- A second layer on top of the grants: checker_ro's sessions start read-only, so even a wrongly
	-- granted write privilege fails with "cannot execute ... in a read-only transaction".
	ALTER ROLE checker_ro SET default_transaction_read_only = on;
EOSQL
