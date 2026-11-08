const MAX_BODY_BYTES = 8 * 1024;
const MAX_TEXT_LENGTH = 4096;
const DEDUPE_TTL_SECONDS = 7 * 24 * 3600;
const memory = new Map();

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};

function reply(body, status = 200, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...JSON_HEADERS, ...extra },
  });
}

function fail(status, error, extra = {}) {
  return reply({ ok: false, error }, status, extra);
}

async function sha256(text) {
  const data = new TextEncoder().encode(text);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data));
}

async function keyMatches(provided, expected) {
  if (!provided || !expected) return false;
  const [a, b] = await Promise.all([sha256(provided), sha256(expected)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

function memGet(key) {
  const hit = memory.get(key);
  if (!hit) return null;
  if (hit.expires <= Date.now()) {
    memory.delete(key);
    return null;
  }
  return hit.value;
}

function memPut(key, value, ttlSeconds) {
  if (memory.size > 2000) {
    const now = Date.now();
    for (const [k, v] of memory) if (v.expires <= now) memory.delete(k);
    if (memory.size > 2000) memory.clear();
  }
  memory.set(key, { value, expires: Date.now() + ttlSeconds * 1000 });
}

async function storeGet(env, key) {
  if (env.ATTENDANCE_KV) return env.ATTENDANCE_KV.get(key);
  return memGet(key);
}

async function storePut(env, key, value, ttlSeconds) {
  if (env.ATTENDANCE_KV) {
    await env.ATTENDANCE_KV.put(key, value, { expirationTtl: Math.max(60, ttlSeconds) });
  } else {
    memPut(key, value, ttlSeconds);
  }
}

async function overLimit(env, bucket, limit, windowSeconds) {
  const slot = Math.floor(Date.now() / (windowSeconds * 1000));
  const key = `rl:${bucket}:${slot}`;
  const count = parseInt((await storeGet(env, key)) || "0", 10) + 1;
  await storePut(env, key, String(count), windowSeconds * 2);
  return count > limit;
}

function redact(text, env) {
  let out = String(text ?? "");
  if (env.BOT_TOKEN) out = out.split(env.BOT_TOKEN).join("[redacted]");
  return out;
}

async function telegram(env, method, payload) {
  const base = (env.TELEGRAM_API_BASE || "https://api.telegram.org").replace(/\/+$/, "");
  const response = await fetch(`${base}/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  let data = null;
  try {
    data = await response.json();
  } catch (_) {
    data = null;
  }
  return { status: response.status, data };
}

function describeTelegramFailure(result, env) {
  const code = result.data?.error_code ?? result.status;
  const description = redact(result.data?.description || `HTTP ${result.status}`, env);
  if (code === 429) {
    const wait = result.data?.parameters?.retry_after ?? 5;
    return fail(429, `Telegram rate limit, retry in ${wait}s`, { "retry-after": String(wait) });
  }
  if (code >= 500 || !result.data) return fail(502, `Telegram unavailable: ${description}`);
  if (code === 401) return fail(400, "Telegram rejected the bot token. Check the BOT_TOKEN secret.");
  return fail(400, `Telegram: ${description}`);
}

function allowedChat(env, chatId) {
  const list = (env.ALLOWED_CHAT_IDS || "")
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
  return list.length === 0 || list.includes(String(chatId));
}

async function handleHealth(env) {
  const result = await telegram(env, "getMe", {});
  if (!result.data?.ok) return describeTelegramFailure(result, env);
  return reply({ ok: true, bot: result.data.result?.username || "" });
}

async function handleSend(request, env) {
  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) return fail(413, "Request too large");

  let body;
  try {
    body = JSON.parse(raw);
  } catch (_) {
    return fail(400, "Invalid JSON");
  }

  const chatId = String(body.chat_id ?? "").trim();
  const text = typeof body.text === "string" ? body.text : "";
  const eventId = body.event_id == null ? "" : String(body.event_id);

  if (!/^-?\d{1,20}$/.test(chatId) && !/^@[A-Za-z][A-Za-z0-9_]{4,31}$/.test(chatId)) {
    return fail(400, "Invalid chat_id");
  }
  if (!text.trim() || text.length > MAX_TEXT_LENGTH) return fail(400, "Invalid text");
  if (eventId && !/^[A-Za-z0-9_.:-]{1,64}$/.test(eventId)) return fail(400, "Invalid event_id");
  if (!allowedChat(env, chatId)) return fail(403, "Chat ID is not allowed");

  const dedupeKey = eventId ? `evt:${chatId}:${eventId}` : "";
  if (dedupeKey && (await storeGet(env, dedupeKey))) {
    return reply({ ok: true, duplicate: true });
  }

  const result = await telegram(env, "sendMessage", {
    chat_id: chatId,
    text,
    disable_web_page_preview: true,
  });
  if (!result.data?.ok) return describeTelegramFailure(result, env);

  if (dedupeKey) await storePut(env, dedupeKey, "1", DEDUPE_TTL_SECONDS);
  return reply({ ok: true });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const ip = request.headers.get("cf-connecting-ip") || "unknown";

    try {
      if (path === "/" && request.method === "GET") {
        return reply({ ok: true, service: "smart-attendance-relay" });
      }

      const isHealth = path === "/health" && request.method === "GET";
      const isSend = path === "/send" && request.method === "POST";
      if (!isHealth && !isSend) return fail(404, "Not found");

      if (!env.BOT_TOKEN || !env.APP_KEY) return fail(500, "Worker is not configured");

      const perMinute = parseInt(env.RATE_LIMIT_PER_MINUTE || "30", 10);
      if (await overLimit(env, `ip:${ip}`, perMinute, 60)) {
        return fail(429, "Too many requests", { "retry-after": "60" });
      }

      const authorized = await keyMatches(request.headers.get("x-app-key"), env.APP_KEY);
      if (!authorized) {
        if (await overLimit(env, `bad:${ip}`, 10, 600)) {
          return fail(429, "Too many failed attempts", { "retry-after": "600" });
        }
        return fail(401, "Unauthorized");
      }

      return isHealth ? await handleHealth(env) : await handleSend(request, env);
    } catch (error) {
      console.log(JSON.stringify({ event: "error", message: redact(error?.message, env) }));
      return fail(502, "Relay error");
    }
  },
};
