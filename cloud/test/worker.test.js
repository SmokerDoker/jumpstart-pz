// Tests the Worker in Miniflare (Cloudflare's local runtime) with a local D1 database and a fake
// Steam Web API. Run: npm test
import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Miniflare } from "miniflare";

const ZB = "3619862853";
let mf;
let steamRequests;
let today;

function steamItem(id, title, { java = false, subs = 10, tags = ["Build 42"], desc = "desc", needs = [] } = {}) {
  return {
    publishedfileid: id,
    title,
    short_description: desc,
    subscriptions: subs,
    time_updated: 1763700000,
    tags: tags.map((t) => ({ tag: t, display_name: t })),
    children: [...(java ? [ZB] : []), ...needs].map((id, i) => ({ publishedfileid: id, sortorder: i + 1, file_type: 0 })),
  };
}

beforeEach(async () => {
  steamRequests = [];
  today = "2026-11-22";
  mf = new Miniflare({ workers: [{
    name: "jumpstart-cloud",
    modules: true,
    scriptPath: "src/index.js",
    compatibilityDate: "2026-08-01",
    d1Databases: ["DB"],
    bindings: {
      PZ_APP_ID: "108600",
      ZOMBIEBUDDY_WORKSHOP_ID: ZB,
      DEFAULT_TAG: "Build 42",
      MIN_INSTALLS_PUBLIC: "2",
      STEAM_API_KEY: "secret-key",
      REPORT_SALT: "salt",
      TEST_TODAY: today,
    },
    outboundService: async (request) => {
      const url = new URL(request.url);
      steamRequests.push(url);
      assert.equal(url.host, "api.steampowered.com");
      const javaQuery = url.searchParams.get("child_publishedfileid") === ZB;
      const items = javaQuery
        ? [steamItem("111", "Java\tMod\nOne", { java: true, subs: 500, needs: ["555", "x"] }), steamItem("112", "Java Mod Two", { java: true })]
        : [
            steamItem("201", "Lua Mod", { desc: "a lua mod" }),
            steamItem("111", "Java\tMod\nOne", { java: true }),
            steamItem(ZB, "ZombieBuddy"),
          ];
      return Response.json({ response: { total: items.length, next_cursor: "NEXT+/=", publishedfiledetails: items } });
    },
  }] });
  const db = await mf.getD1Database("DB");
  for (const statement of readFileSync("migrations/0001_usage.sql", "utf8").split(";")) {
    const sql = statement.replace(/--.*$/gm, "").trim();
    if (sql) {
      await db.prepare(sql).run();
    }
  }
});

afterEach(async () => {
  await mf.dispose();
});

function get(path) {
  return mf.dispatchFetch("http://worker" + path);
}

function post(path, body) {
  return mf.dispatchFetch("http://worker" + path, { method: "POST", body });
}

test("the Java library asks Steam for items that depend on ZombieBuddy", async () => {
  const res = await get("/v1/library?kind=java&q=fps");
  assert.equal(res.status, 200);
  const lines = (await res.text()).trim().split("\n");
  assert.equal(lines[0], "jumpstart-library 1");
  assert.equal(lines[2], "next\tNEXT+/=");
  assert.deepEqual(lines[3].split("\t"), ["item", "111", "Java Mod One", "500", "1763700000", "1", "Build 42", "desc", "555"]);
  assert.equal(lines.length, 5);

  const q = steamRequests[0].searchParams;
  assert.equal(q.get("child_publishedfileid"), ZB);
  assert.equal(q.get("search_text"), "fps");
  assert.equal(q.get("query_type"), "11");
  assert.equal(q.get("appid"), "108600");
  assert.equal(q.get("requiredtags[0]"), "Build 42");
  assert.equal(q.get("key"), "secret-key");
});

test("the Lua library leaves out Java mods and ZombieBuddy itself", async () => {
  const body = await (await get("/v1/library?kind=lua&sort=recent")).text();
  const items = body.trim().split("\n").filter((l) => l.startsWith("item\t"));
  assert.deepEqual(items.map((l) => l.split("\t")[1]), ["201"]);
  const q = steamRequests[0].searchParams;
  assert.equal(q.get("child_publishedfileid"), null);
  assert.equal(q.get("query_type"), "19");
  assert.equal(q.get("search_text"), null);
});

test("the key never reaches the answer, and answers are cached", async () => {
  const first = await (await get("/v1/library?kind=lua")).text();
  const second = await (await get("/v1/library?kind=lua")).text();
  assert.equal(first, second);
  assert.equal(steamRequests.length, 1, "the second answer comes from the cache");
  assert.ok(!first.includes("secret-key"));
});

test("bad library requests are refused", async () => {
  assert.equal((await get("/v1/library?kind=exe")).status, 400);
  assert.equal((await get("/v1/library?kind=java&sort=text")).status, 400);
  assert.equal(steamRequests.length, 0);
});

const SHA_A = "a".repeat(64);
const SHA_B = "b".repeat(64);

function usage(install, jars) {
  return ["jumpstart-usage 1", "install\t" + install, "client\tjumpstart/test", ...jars.map((j) => "jar\t" + j.join("\t"))].join("\n");
}

test("reports are stored once per installation and day, without the installation id", async () => {
  const install = "0123456789abcdef0123456789abcdef";
  let res = await post("/v1/usage", usage(install, [[SHA_A, "111", "JavaModOne", "1234"], [SHA_B, "", "Local", "99"]]));
  assert.equal(await res.text(), "OK 2 jars\n");
  res = await post("/v1/usage", usage(install, [[SHA_A, "111", "JavaModOne", "1234"]]));
  assert.equal(await res.text(), "ALREADY reported today\n");

  const db = await mf.getD1Database("DB");
  const rows = (await db.prepare("SELECT * FROM sightings ORDER BY sha256").all()).results;
  assert.equal(rows.length, 2);
  assert.equal(rows[0].workshop_id, "111");
  assert.equal(rows[1].workshop_id, null);
  const dump = JSON.stringify((await db.prepare("SELECT * FROM reports").all()).results) + JSON.stringify(rows);
  assert.ok(!dump.includes(install), "the raw installation id is not stored");
});

test("the public list only shows jars that enough installations reported", async () => {
  await post("/v1/usage", usage("install-one-000000000", [[SHA_A, "111", "JavaModOne", "1"], [SHA_B, "", "Rare", "2"]]));
  await post("/v1/usage", usage("install-two-000000000", [[SHA_A, "111", "JavaModOne", "1"]]));
  const lines = (await (await get("/v1/usage/top?days=30")).text()).trim().split("\n");
  assert.equal(lines[0], "jumpstart-usage-top 1");
  const jars = lines.filter((l) => l.startsWith("jar\t"));
  assert.deepEqual(jars, ["jar\t" + SHA_A + "\t2\t111\tJavaModOne\t1"], "SHA_B was reported by one installation only");
});

test("bad reports are refused", async () => {
  assert.equal((await post("/v1/usage", "hello")).status, 400);
  assert.equal((await post("/v1/usage", usage("short", []))).status, 400);
  assert.equal((await post("/v1/usage", usage("install-one-000000000", [["nothex", "", "x", "1"]]))).status, 400);
  assert.equal((await post("/v1/usage", usage("install-one-000000000", [[SHA_A, "12ab", "x", "1"]]))).status, 400);
  const many = Array.from({ length: 101 }, (_, i) => [i.toString(16).padStart(64, "0"), "", "m", "1"]);
  assert.equal((await post("/v1/usage", usage("install-one-000000000", many))).status, 413);
  assert.equal((await post("/v1/usage", "x".repeat(70000))).status, 413);
});

test("the daily clean-up removes reports older than 90 days", async () => {
  const db = await mf.getD1Database("DB");
  await db.prepare("INSERT INTO sightings VALUES (?, 'h', '2026-08-01', NULL, NULL, NULL)").bind(SHA_A).run();
  await db.prepare("INSERT INTO sightings VALUES (?, 'h', '2026-11-10', NULL, NULL, NULL)").bind(SHA_B).run();
  const worker = await mf.getWorker();
  await worker.scheduled({ cron: "17 3 * * *", scheduledTime: new Date("2026-11-22T03:17:00Z") });
  const left = (await db.prepare("SELECT sha256 FROM sightings").all()).results.map((r) => r.sha256);
  assert.deepEqual(left, [SHA_B]);
});
