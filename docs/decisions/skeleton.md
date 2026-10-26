# Decision Log

Format: **Decision / Alternatives rejected / Why / Revisit when**

---

## D-001: Secrets and environment config live outside the code
- **Decision:** DB credentials and host settings come from environment variables. Locally they are loaded from `.env` (gitignored). A `.env.example` with placeholders is committed.
- **Rejected:** Hardcoding in `application.yaml` / `docker-compose.yml`.
- **Why:** A password pushed to a public repo is leaked permanently, even if deleted later. Same code must run in dev, CI, and prod with different config.
- **Revisit when:** Deploying beyond local (move to a secrets manager, e.g. cloud secret store or Vault).

## D-002: Pin the Postgres image version
- **Decision:** Use an explicit tag (e.g. `postgres:16.4`), not `latest`.
- **Rejected:** `postgres:latest`.
- **Why:** `latest` can jump a major version on the next pull, and a Postgres data directory from one major version will not start on another. Builds must be reproducible.
- **Revisit when:** Planning a deliberate major upgrade (dump/restore or pg_upgrade).

## D-003: Modular monolith with hard module boundaries
- **Decision:** One deployable, packages per module: `catalog`, `inventory`, `orders`, `payments`, `identity`, `shared`. Modules talk via interfaces, never each other's tables.
- **Rejected:** Microservices from day one.
- **Why:** Distributed-system costs with no scale problem yet; boundaries keep later extraction possible.
- **Revisit when:** A module has a different scaling profile, team ownership, or release cadence.

## D-004: Products carry a `seller_id` from day one
- **Decision:** v1 has a single seller (the platform).
- **Rejected:** Adding `seller_id` later.
- **Why:** Retrofitting a column across a large live table plus every query is far costlier than carrying it now. Keeps the marketplace extension a feature, not a rewrite.
- **Revisit when:** Starting the marketplace milestone.

---

# Parking Lot
Ideas that are interesting but outside the current milestone's theme.

- (empty)