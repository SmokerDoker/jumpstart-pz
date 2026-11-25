// Jumpstart cloud: a Cloudflare Worker with two jobs.
//
// 1. Mod libraries for Jumpstart's marketplace: search the Project Zomboid Steam Workshop for Java
//    mods (items that depend on ZombieBuddy) or Lua mods (all others). The Steam Web API needs a
//    key for searching, which must never ship inside a game mod, so the Worker holds it.
// 2. Voluntary reports from BiteCheck and Jumpstart: which unverified Java mod jars players use,
//    so the BiteCheck registry can review the mods people actually play first.
//
// Everything the game gets back is plain text (tab-separated lines), so the Java side needs no JSON
// library. Nothing here decides whether a mod is safe: Jumpstart checks every jar locally with
// BiteCheck before it loads, whatever this Worker says.

const LIBRARY_FORMAT = "jumpstart-library 1";
const USAGE_FORMAT = "jumpstart-usage 1";
const TOP_FORMAT = "jumpstart-usage-top 1";
const PAGE_SIZE = 30;
const MAX_REPORT_BYTES = 64 * 1024;
const MAX_JARS_PER_REPORT = 100;
const KEEP_DAYS = 90;
const SEARCH_CACHE_SECONDS = 3600;
const TOP_CACHE_SECONDS = 600;

// Steam's EPublishedFileQueryType
const QUERY = { popular: 12, recent: 19, trend: 3, text: 11 };

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/") {
        return text(
          "Jumpstart cloud\n" +
            "GET  /v1/library?kind=java|lua&q=TEXT&sort=popular|recent|trend&cursor=CURSOR&tag=TAG\n" +
            "POST /v1/usage    (voluntary reports of unverified jars, see the Jumpstart README)\n" +
            "GET  /v1/usage/top?days=30\n",
        );
      }
      if (request.method === "GET" && url.pathname === "/v1/library") {
        return await library(url, env, ctx);
      }
      if (request.method === "POST" && url.pathname === "/v1/usage") {
        return await report(request, env);
      }
      if (request.method === "GET" && url.pathname === "/v1/usage/top") {
        return await top(url, env, ctx);
      }
      return text("not found\n", 404);
    } catch (e) {
      if (e instanceof HttpError) {
        return text(e.message + "\n", e.status);
      }
      console.error(e);
      return text("internal error\n", 500);
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(cleanUp(env));
  },
};

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function text(body, status = 200, headers = {}) {
  return new Response(body, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8", ...headers },
  });
}

/** A value for one tab-separated field: no tabs or line breaks, at most max characters. */
function field(value, max = 200) {
  const s = String(value ?? "").replace(/[\t\r\n]+/g, " ").trim();
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
}

function today(env) {
  return env.TEST_TODAY || new Date().toISOString().slice(0, 10);
}

function daysBefore(day, days) {
  const d = new Date(day + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

// --- libraries -------------------------------------------------------------------------------------

async function library(url, env, ctx) {
  const kind = url.searchParams.get("kind");
  if (kind !== "java" && kind !== "lua") {
    throw new HttpError(400, "kind must be java or lua");
  }
  const q = field(url.searchParams.get("q") ?? "", 100);
  const sort = url.searchParams.get("sort") ?? "popular";
  if (!(sort in QUERY) || sort === "text") {
    throw new HttpError(400, "sort must be popular, recent or trend");
  }
  const cursor = url.searchParams.get("cursor") || "*";
  if (cursor.length > 200) {
    throw new HttpError(400, "cursor too long");
  }
  const tag = url.searchParams.has("tag") ? field(url.searchParams.get("tag"), 50) : env.DEFAULT_TAG ?? "";

  // the same question gets the same cached answer for an hour
  const cacheKey = new Request(
    "https://cache.jumpstart/v1/library?" +
      new URLSearchParams({ kind, q, sort, cursor, tag }).toString(),
  );
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) {
    return cached;
  }

  if (!env.STEAM_API_KEY) {
    throw new HttpError(503, "the Worker has no STEAM_API_KEY yet");
  }
  const zombieBuddy = env.ZOMBIEBUDDY_WORKSHOP_ID;
  const params = new URLSearchParams({
    key: env.STEAM_API_KEY,
    appid: env.PZ_APP_ID,
    query_type: String(q ? QUERY.text : QUERY[sort]),
    cursor,
    numperpage: String(PAGE_SIZE),
    return_tags: "true",
    return_children: "true",
    return_short_description: "true",
    strip_description_bbcode: "true",
  });
  if (q) {
    params.set("search_text", q);
  }
  if (sort === "trend" && !q) {
    params.set("days", "7");
  }
  if (tag) {
    params.set("requiredtags[0]", tag);
  }
  if (kind === "java") {
    params.set("child_publishedfileid", zombieBuddy); // items that list ZombieBuddy as a requirement
  }
  const steam = await fetch("https://api.steampowered.com/IPublishedFileService/QueryFiles/v1/?" + params.toString());
  if (!steam.ok) {
    throw new HttpError(502, "Steam answered " + steam.status);
  }
  const data = (await steam.json()).response ?? {};

  const lines = [LIBRARY_FORMAT, "total\t" + (data.total ?? 0), "next\t" + field(data.next_cursor ?? "", 200)];
  for (const item of data.publishedfiledetails ?? []) {
    if (!item || !item.publishedfileid || item.result === 9) {
      continue; // 9: file not found / hidden
    }
    const java = (item.children ?? []).some((c) => String(c.publishedfileid) === zombieBuddy);
    if (kind === "lua" && java) {
      continue; // Java mods belong to the other library
    }
    if (String(item.publishedfileid) === zombieBuddy) {
      continue;
    }
    const tags = (item.tags ?? []).map((t) => field(t.tag, 40).replace(/,/g, " ")).join(",");
    // the items it needs (Steam's "Required items"), without ZombieBuddy: Jumpstart brings that one
    const requires = (item.children ?? [])
      .map((c) => String(c.publishedfileid))
      .filter((id) => /^[0-9]{1,20}$/.test(id) && id !== zombieBuddy)
      .slice(0, 30)
      .join(",");
    lines.push(
      [
        "item",
        field(item.publishedfileid, 20),
        field(item.title, 120),
        field(item.subscriptions ?? item.lifetime_subscriptions ?? 0, 12),
        field(item.time_updated ?? 0, 12),
        java ? "1" : "0",
        field(tags, 300),
        field(item.short_description ?? "", 300),
        requires,
      ].join("\t"),
    );
  }
  const response = text(lines.join("\n") + "\n", 200, { "cache-control": "public, max-age=" + SEARCH_CACHE_SECONDS });
  ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

// --- voluntary reports ---------------------------------------------------------------------------

async function report(request, env) {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (length > MAX_REPORT_BYTES) {
    throw new HttpError(413, "report too large");
  }
  const body = await request.text();
  if (body.length > MAX_REPORT_BYTES) {
    throw new HttpError(413, "report too large");
  }
  const lines = body.split("\n").map((l) => l.replace(/\r$/, "")).filter((l) => l.length > 0);
  if (lines[0] !== USAGE_FORMAT) {
    throw new HttpError(400, "first line must be: " + USAGE_FORMAT);
  }
  let install = null;
  let client = "unknown";
  const jars = new Map();
  for (const line of lines.slice(1)) {
    const f = line.split("\t");
    if (f[0] === "install") {
      install = f[1] ?? "";
    } else if (f[0] === "client") {
      client = field(f[1], 40);
    } else if (f[0] === "jar") {
      const [, sha256, workshopId, modId, size] = f;
      if (!/^[0-9a-f]{64}$/.test(sha256 ?? "")) {
        throw new HttpError(400, "bad sha256: " + field(sha256, 80));
      }
      if (workshopId && !/^[0-9]{1,20}$/.test(workshopId)) {
        throw new HttpError(400, "bad workshop id");
      }
      if (size && !/^[0-9]{1,12}$/.test(size)) {
        throw new HttpError(400, "bad size");
      }
      jars.set(sha256, { workshopId: workshopId || null, modId: field(modId, 100) || null, size: size ? Number(size) : null });
    } else {
      throw new HttpError(400, "unknown line: " + field(f[0], 20));
    }
  }
  if (!install || !/^[A-Za-z0-9-]{16,64}$/.test(install)) {
    throw new HttpError(400, "missing or bad install id");
  }
  if (jars.size > MAX_JARS_PER_REPORT) {
    throw new HttpError(413, "at most " + MAX_JARS_PER_REPORT + " jars per report");
  }
  if (!env.REPORT_SALT) {
    throw new HttpError(503, "the Worker has no REPORT_SALT yet");
  }

  const installHash = await sha256Hex(env.REPORT_SALT + "\n" + install);
  const day = today(env);
  const first = await env.DB.prepare("INSERT OR IGNORE INTO reports (day, install_hash, client) VALUES (?, ?, ?)")
    .bind(day, installHash, client)
    .run();
  if (first.meta.changes === 0) {
    return text("ALREADY reported today\n");
  }
  if (jars.size > 0) {
    const upsert = env.DB.prepare(
      "INSERT INTO sightings (sha256, install_hash, last_day, workshop_id, mod_id, size) VALUES (?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT (sha256, install_hash) DO UPDATE SET last_day = excluded.last_day, " +
        "workshop_id = excluded.workshop_id, mod_id = excluded.mod_id, size = excluded.size",
    );
    await env.DB.batch(
      [...jars].map(([sha, j]) => upsert.bind(sha, installHash, day, j.workshopId, j.modId, j.size)),
    );
  }
  return text("OK " + jars.size + " jars\n");
}

async function sha256Hex(s) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The public review list: unverified jars by the number of installations that use them. */
async function top(url, env, ctx) {
  const days = Math.min(90, Math.max(1, Number(url.searchParams.get("days") ?? "30") || 30));
  const cacheKey = new Request("https://cache.jumpstart/v1/usage/top?days=" + days);
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) {
    return cached;
  }
  const minInstalls = Math.max(1, Number(env.MIN_INSTALLS_PUBLIC ?? "3") || 3);
  const since = daysBefore(today(env), days);
  const rows = await env.DB.prepare(
    "SELECT sha256, COUNT(*) AS installs, MAX(workshop_id) AS workshop_id, MAX(mod_id) AS mod_id, MAX(size) AS size " +
      "FROM sightings WHERE last_day >= ? GROUP BY sha256 HAVING COUNT(*) >= ? ORDER BY installs DESC, sha256 LIMIT 200",
  )
    .bind(since, minInstalls)
    .all();
  const lines = [TOP_FORMAT, "days\t" + days, "min_installs\t" + minInstalls];
  for (const r of rows.results) {
    lines.push(["jar", r.sha256, r.installs, r.workshop_id ?? "", field(r.mod_id ?? "", 100), r.size ?? ""].join("\t"));
  }
  const response = text(lines.join("\n") + "\n", 200, { "cache-control": "public, max-age=" + TOP_CACHE_SECONDS });
  ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

async function cleanUp(env) {
  const oldest = daysBefore(today(env), KEEP_DAYS);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM sightings WHERE last_day < ?").bind(oldest),
    env.DB.prepare("DELETE FROM reports WHERE day < ?").bind(oldest),
  ]);
}

export { cleanUp as _cleanUpForTests };
