# deploy/

Production deployment files. **Filled in during phase 8** (`docs/plan.md` section 14).

This directory will hold:

| File | Purpose |
|---|---|
| `docker-compose.prod.yml` | Caddy + the backend image on EC2 |
| `Caddyfile` | TLS termination via Let's Encrypt, reverse proxy to the backend |

Nothing here yet, deliberately: the backend image has to exist and be exercised
locally before there is anything to deploy.

Target, from `docs/plan.md` section 11:

- **Backend:** AWS EC2 `t4g.small` (ARM, 2 GB) running Docker Compose.
- **Database:** Neon free tier with pgvector — deliberately off the EC2 box to
  save memory and credits.
- **Frontend:** Vercel, which needs nothing from this directory.
- **Fallback host:** Oracle Cloud Always Free (Ampere ARM), reusing the same
  `linux/arm64` image. That is why `backend/Dockerfile` must keep building for
  arm64 — see the `docker buildx` command in the root `README.md`.
