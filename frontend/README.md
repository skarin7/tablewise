# tablewise-web

Vue 3 + Vite + TypeScript frontend for the Tablewise POS. Screens mirror `specs/tablewise-backend-handoff/prototype/tablewise-prototype.html`; data comes from the Spring Boot API and STOMP channels (README sections 6, 8 and 12). `API_CONTRACT.md` lists every endpoint the app calls and the shapes it assumes where the backend is not built yet.

```
npm install
cp .env.example .env     # point at your API and Supabase project
npm run dev              # http://localhost:5173
npm test                 # bill maths, role rules, report periods
npm run build            # type-checks, then writes dist/
```

Routes: `/login`, `/dashboard`, `/pos`, `/orders`, `/online`, `/menu`, `/inventory`, `/customers`, `/reports`, `/admin`, and `/display` (the customer tablet, which pairs with a code instead of signing in). Roles limit which staff routes open.

## Deploy to Vercel

1. Import the repo in Vercel and set the root directory to `frontend` (Framework: Vite; build `npm run build`; output `dist`).
2. Add the four variables from `.env.example` for Production and Preview: `VITE_API_BASE_URL`, `VITE_WS_URL` (`wss://…/ws`), `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`.
3. `vercel.json` already rewrites every path to `index.html`.
4. On the backend, put the Vercel production and preview domains in `ALLOWED_ORIGINS`.

Vercel Hobby is for non-commercial use only (README 12.2).
