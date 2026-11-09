import worker from "./src/index.js";
import assert from "node:assert/strict";

const BOT = "123456:SECRET-BOT-TOKEN";
const KEY = "app-key-for-tests";
let calls = [];
let telegramImpl = null;
let ipCounter = 0;

globalThis.fetch = async (url, init) => {
  const u = String(url);
  const method = u.split("/").pop();
  const body = init?.body ? JSON.parse(init.body) : {};
  calls.push({ url: u, method, body });
  assert.ok(u.startsWith(`https://api.telegram.org/bot${BOT}/`), "uses the official Telegram API");
  return telegramImpl(method, body);
};

const tg = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

const defaultTelegram = (method, body) => {
  if (method === "getMe") return tg({ ok: true, result: { username: "my_test_bot" } });
  if (method === "sendMessage") return tg({ ok: true, result: { message_id: 1 } });
  if (method === "getChat") return tg({ ok: true, result: { id: body.chat_id, first_name: "Kavir" } });
  if (method === "getUpdates") {
    return tg({
      ok: true,
      result: [
        { update_id: 1, message: { chat: { id: 111, type: "private", first_name: "Old" } } },
        { update_id: 2, message: { chat: { id: 222, type: "private", first_name: "New", last_name: "User" } } },
        { update_id: 3, message: { chat: { id: 222, type: "private", first_name: "New" } } },
      ],
    });
  }
  return tg({ ok: false, error_code: 404, description: "unknown" }, 404);
};

function call(path, { method = "GET", key = KEY, body, env = {}, ip } = {}) {
  const headers = { "cf-connecting-ip": ip || `10.0.0.${++ipCounter}` };
  if (key !== null) headers["x-app-key"] = key;
  const init = { method, headers };
  if (body !== undefined) {
    init.body = typeof body === "string" ? body : JSON.stringify(body);
    headers["content-type"] = "application/json";
  }
  const request = new Request(`https://relay.example.workers.dev${path}`, init);
  return worker.fetch(request, { BOT_TOKEN: BOT, APP_KEY: KEY, ...env });
}

async function json(response) {
  const text = await response.text();
  assert.ok(!text.includes(BOT), "response must never contain the bot token");
  return JSON.parse(text);
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test("public root answers without auth", async () => {
  const r = await call("/", { key: null });
  assert.equal(r.status, 200);
  assert.equal((await json(r)).service, "smart-attendance-relay");
});

test("unknown paths are 404", async () => {
  assert.equal((await call("/nope")).status, 404);
});

test("health requires the app key", async () => {
  assert.equal((await call("/health", { key: null })).status, 401);
  assert.equal((await call("/health", { key: "wrong" })).status, 401);
  assert.equal(calls.length, 0, "Telegram must not be called for unauthorized requests");
});

test("health returns the bot username", async () => {
  const r = await call("/health");
  assert.equal(r.status, 200);
  const body = await json(r);
  assert.equal(body.ok, true);
  assert.equal(body.bot, "my_test_bot");
});

test("health can verify a chat", async () => {
  const body = await json(await call("/health?chat_id=555"));
  assert.equal(body.chat.ok, true);
  assert.equal(body.chat.name, "Kavir");
});

test("health reports an unreachable chat", async () => {
  telegramImpl = (method) =>
    method === "getChat"
      ? tg({ ok: false, error_code: 400, description: "Bad Request: chat not found" }, 400)
      : defaultTelegram(method, {});
  const body = await json(await call("/health?chat_id=555"));
  assert.equal(body.ok, true);
  assert.equal(body.chat.ok, false);
  assert.match(body.chat.error, /chat not found/);
});

test("detect lists unique chats newest first", async () => {
  const body = await json(await call("/detect"));
  assert.deepEqual(body.chats.map((c) => c.id), ["222", "111"]);
  assert.equal(body.chats[0].name, "New");
});

test("detect respects ALLOWED_CHAT_IDS", async () => {
  const body = await json(await call("/detect", { env: { ALLOWED_CHAT_IDS: "111" } }));
  assert.deepEqual(body.chats.map((c) => c.id), ["111"]);
});

test("send validates input", async () => {
  assert.equal((await call("/send", { method: "POST", body: "not json" })).status, 400);
  assert.equal((await call("/send", { method: "POST", body: { chat_id: "abc", text: "x" } })).status, 400);
  assert.equal((await call("/send", { method: "POST", body: { chat_id: "1", text: "  " } })).status, 400);
  assert.equal((await call("/send", { method: "POST", body: { chat_id: "1", text: "x".repeat(4097) } })).status, 400);
  assert.equal((await call("/send", { method: "POST", body: { chat_id: "1", text: "x", event_id: "bad id!" } })).status, 400);
  assert.equal(calls.length, 0);
});

test("send delivers once and de-duplicates by event_id", async () => {
  const payload = { chat_id: "555", text: "WORK ATTENDANCE", event_id: "v1700000000000" };
  const first = await json(await call("/send", { method: "POST", body: payload }));
  assert.equal(first.ok, true);
  assert.equal(first.duplicate, undefined);
  const second = await json(await call("/send", { method: "POST", body: payload }));
  assert.equal(second.ok, true);
  assert.equal(second.duplicate, true);
  assert.equal(calls.filter((c) => c.method === "sendMessage").length, 1);
  const sent = calls.find((c) => c.method === "sendMessage").body;
  assert.equal(sent.chat_id, "555");
  assert.equal(sent.text, "WORK ATTENDANCE");
});

test("failed sends are not marked as delivered", async () => {
  telegramImpl = (method) =>
    tg({ ok: false, error_code: 502, description: "Bad Gateway" }, 502);
  const payload = { chat_id: "555", text: "hello", event_id: "retry-me" };
  assert.equal((await call("/send", { method: "POST", body: payload })).status, 502);
  telegramImpl = defaultTelegram;
  const retry = await json(await call("/send", { method: "POST", body: payload }));
  assert.equal(retry.ok, true);
  assert.equal(retry.duplicate, undefined);
});

test("telegram 400 maps to a clear client error", async () => {
  telegramImpl = () => tg({ ok: false, error_code: 400, description: "Bad Request: chat not found" }, 400);
  const r = await call("/send", { method: "POST", body: { chat_id: "9", text: "x" } });
  assert.equal(r.status, 400);
  assert.match((await json(r)).error, /chat not found/);
});

test("telegram 403 (bot not started) is reported", async () => {
  telegramImpl = () => tg({ ok: false, error_code: 403, description: "Forbidden: bot can't initiate conversation with a user" }, 403);
  const r = await call("/send", { method: "POST", body: { chat_id: "9", text: "x" } });
  assert.equal(r.status, 400);
  assert.match((await json(r)).error, /Forbidden/);
});

test("telegram 429 becomes retryable 429 with retry-after", async () => {
  telegramImpl = () => tg({ ok: false, error_code: 429, description: "Too Many Requests", parameters: { retry_after: 7 } }, 429);
  const r = await call("/send", { method: "POST", body: { chat_id: "9", text: "x" } });
  assert.equal(r.status, 429);
  assert.equal(r.headers.get("retry-after"), "7");
});

test("telegram 401 hides details and says the token is wrong", async () => {
  telegramImpl = () => tg({ ok: false, error_code: 401, description: `Unauthorized ${BOT}` }, 401);
  const r = await call("/health");
  assert.equal(r.status, 400);
  assert.match((await json(r)).error, /bot token/i);
});

test("network failure to telegram is a retryable 502", async () => {
  telegramImpl = () => {
    throw new Error(`connect failed for ${BOT}`);
  };
  const r = await call("/send", { method: "POST", body: { chat_id: "9", text: "x" } });
  assert.equal(r.status, 502);
  await json(r);
});

test("token is redacted from error descriptions", async () => {
  telegramImpl = () => tg({ ok: false, error_code: 400, description: `oops ${BOT} oops` }, 400);
  const r = await call("/send", { method: "POST", body: { chat_id: "9", text: "x" } });
  const body = await json(r);
  assert.match(body.error, /redacted/);
});

test("ALLOWED_CHAT_IDS blocks other chats", async () => {
  const r = await call("/send", { method: "POST", body: { chat_id: "999", text: "x" }, env: { ALLOWED_CHAT_IDS: "555,556" } });
  assert.equal(r.status, 403);
  assert.equal(calls.length, 0);
});

test("per-IP rate limit", async () => {
  const env = { RATE_LIMIT_PER_MINUTE: "3" };
  const ip = "203.0.113.9";
  for (let i = 0; i < 3; i++) assert.equal((await call("/health", { env, ip })).status, 200);
  const blocked = await call("/health", { env, ip });
  assert.equal(blocked.status, 429);
  assert.equal(blocked.headers.get("retry-after"), "60");
});

test("repeated bad keys lock the IP out", async () => {
  const ip = "203.0.113.50";
  const env = { RATE_LIMIT_PER_MINUTE: "1000" };
  let last = 0;
  for (let i = 0; i < 12; i++) last = (await call("/health", { key: "bad", ip, env })).status;
  assert.equal(last, 429);
  assert.equal((await call("/health", { ip, env })).status, 200, "valid key still passes the lockout bucket check until the IP limit");
});

test("unconfigured worker refuses requests", async () => {
  const r = await worker.fetch(
    new Request("https://x.workers.dev/health", { headers: { "x-app-key": KEY, "cf-connecting-ip": "10.9.9.9" } }),
    {}
  );
  assert.equal(r.status, 500);
});

let failed = 0;
for (const [name, fn] of tests) {
  calls = [];
  telegramImpl = defaultTelegram;
  try {
    await fn();
    console.log(`PASS ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}\n     ${error.message}`);
  }
}
console.log(`\n${tests.length - failed}/${tests.length} passed`);
process.exit(failed ? 1 : 0);
