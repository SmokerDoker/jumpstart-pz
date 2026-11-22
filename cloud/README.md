# Jumpstart cloud

Live at <https://jumpstart-cloud.smokerdoker.workers.dev> (Cloudflare account of SmokerDoker, D1
database `jumpstart-cloud` in Western Europe).

One free Cloudflare Worker with one D1 database, separate from any of your own servers. It does
two things:

1. **Mod libraries** for Jumpstart's marketplace: search the Project Zomboid Steam Workshop for
   **Java mods** (Workshop items that list ZombieBuddy as a requirement) or **Lua mods** (all
   others). Steam only allows searching with a Web API key, and a key must never ship inside a
   game mod, so the Worker holds it and answers the game.
2. **Voluntary reports** from BiteCheck and Jumpstart: which unverified Java mod jars players use,
   so the BiteCheck registry reviews the mods people actually play first.

The Worker decides nothing about safety. Jumpstart checks every jar on the player's computer with
BiteCheck before it loads anything, whatever the Worker says.

## API

All answers are plain text, one record per line, fields separated by tabs.

| Request | Answer |
| --- | --- |
| `GET /v1/library?kind=java\|lua&q=TEXT&sort=popular\|recent\|trend&cursor=C&tag=T` | `jumpstart-library 1`, `total N`, `next CURSOR`, then `item ID TITLE SUBSCRIPTIONS UPDATED JAVA(0/1) TAGS DESCRIPTION` (30 per page, cached 1 h; `tag` defaults to `Build 42`) |
| `POST /v1/usage` (body below) | `OK n jars` or `ALREADY reported today` |
| `GET /v1/usage/top?days=30` | `jumpstart-usage-top 1`, then `jar SHA256 INSTALLATIONS WORKSHOP_ID MOD_ID SIZE`, only jars that at least 3 installations reported |

Report body (what BiteCheck's `UsageSharing` sends):

```
jumpstart-usage 1
install	<random id, new every month>
client	jumpstart/0.2
jar	<sha256>	<workshop id or empty>	<mod id>	<size in bytes>
```

## What is stored

Per installation and day: that a report came, and from which client (`jumpstart/0.2`). Per
unverified jar and installation: the jar's SHA-256, Workshop id, mod id, size and the last day it
was reported. Installations are stored only as SHA-256(secret salt + installation id); the id
itself, IP addresses and anything else about the player are not stored. Everything older than
90 days is deleted every night. The public list shows a jar only when at least 3 installations
reported it, so a single player's private mod never appears there.

## Setup (once, about 15 minutes)

You need a free Cloudflare account and a Steam Web API key.

1. Steam Web API key: <https://steamcommunity.com/dev/apikey>, any domain name (for example
   `jumpstart`). The Steam account must not be limited (it has spent at least 5 USD).
2. Node.js 20 or newer. In this folder:

   ```sh
   npm install
   npx wrangler login                        # opens the browser, log in to Cloudflare
   npx wrangler d1 create jumpstart-cloud --location weur   # prints a database_id
   ```

3. Put that `database_id` into `wrangler.toml`, then:

   ```sh
   npx wrangler d1 migrations apply jumpstart-cloud --remote
   npx wrangler secret put STEAM_API_KEY     # paste the Steam key
   node -e "console.log(crypto.randomUUID() + crypto.randomUUID())" | npx wrangler secret put REPORT_SALT
   npx wrangler deploy                       # prints https://jumpstart-cloud.<your-name>.workers.dev
   ```

4. Check it: open `https://jumpstart-cloud.<your-name>.workers.dev/v1/library?kind=java` in a
   browser. Then tell Jumpstart and BiteCheck the address (it becomes their built-in default in
   the next release; until then `-Djumpstart.cloudUrl=...` for the game, `cloud_url = "..."` in
   `~/.bitecheck/config.toml`).

The free plan allows 100,000 requests and 100,000 database writes a day; library answers are
cached for an hour, and each report writes at most 101 rows.

## Development

```sh
npm install
npm test          # the Worker in Miniflare with a local D1 database and a fake Steam API
npx wrangler dev  # local server on http://localhost:8787 (put STEAM_API_KEY and REPORT_SALT in .dev.vars)
```
