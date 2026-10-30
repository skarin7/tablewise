# Tablewise

- `backend/` Spring Boot API · `frontend/` Vue app · `specs/` handoff docs and prototype

## Run everything locally (Docker)

```
docker compose up --build
```

Open http://localhost:5173 and sign in as Owner, Cashier or Kitchen (local-only sign-in, no Supabase). Postgres 16 runs in the compose file and a one-shot `seed` service loads the demo restaurant (menu, stock, 12 tables, 2 counters) once the API has migrated the schema. API: http://localhost:8080 · Postgres: localhost:5432 (`tablewise`/`tablewise`).

- Reset the data: `docker compose down -v`.
- UPI QR needs Razorpay test keys: copy `.env.example` to `.env` and fill in the three values (`.env` is git-ignored). Cash and card work without them.
- Customer display: open http://localhost:5173/display in another window, then Admin → Customer displays → Add.
- The local sign-in uses the API's `local` profile and a frontend build with `VITE_LOCAL_DEV=true`. Never deploy either.
