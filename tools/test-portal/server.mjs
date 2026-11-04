// Local page for trying the app's OpenAI prompts without the app: node tools/test-portal/server.mjs
// It uses the app's own key, model, prompts and schemas, and only accepts connections from this computer.
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";

const PORT = 8787;
const MAX_BODY = 10000;

const appFile = (path) => fileURLToPath(new URL(`../../app/src/main/${path}`, import.meta.url));
const asset = (name) => readFileSync(appFile(`assets/llm/${name}`), "utf8");
const page = readFileSync(fileURLToPath(new URL("page.html", import.meta.url)));

function secret(name) {
  const source = readFileSync(appFile("java/com/example/myapplication/nlp/LlmSecrets.kt"), "utf8");
  const value = source.match(new RegExp(`${name}\\s*=\\s*"([^"]*)"`))?.[1];
  if (!value || value.startsWith("PASTE_")) throw new Error(`Fill in ${name} in LlmSecrets.kt, then send again.`);
  return value;
}

// Same as LlmJson.kt: the instructions never change, and what does goes in the input.
function buildPrompt(body) {
  const today = body.today;
  if (body.endpoint === "command") {
    return {
      name: "voice_commands",
      schema: JSON.parse(asset("command_schema.json")),
      instructions: asset("command_prompt.txt"),
      input: `Today: ${today}\nCommand: ${body.utterance}`,
    };
  }
  return {
    name: "follow_up_answer",
    schema: JSON.parse(asset("answer_schema.json")),
    instructions: asset("answer_prompt.txt"),
    input: `Today: ${today}\nDetail being collected: ${body.slot}\nQuestion: ${body.question}\nReply: ${body.answer}`,
  };
}

async function callOpenAi(body) {
  const { name, schema, instructions, input } = buildPrompt(body);
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${secret("OPENAI_API_KEY")}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: secret("OPENAI_MODEL"),
      store: false,
      prompt_cache_key: `zeroui-${name}`,
      instructions,
      input,
      text: { format: { type: "json_schema", strict: true, name, schema } },
    }),
  });
  const data = await response.json();
  if (!response.ok) return { status: response.status, error: data.error?.message ?? "OpenAI request failed" };
  const part = (data.output ?? [])
    .filter((item) => item.type === "message")
    .flatMap((item) => item.content ?? [])
    .find((content) => content.type === "output_text");
  if (!part) return { status: 502, error: "The model returned no text, possibly a refusal." };
  return { status: 200, result: JSON.parse(part.text), usage: data.usage };
}

async function readBody(request) {
  let text = "";
  for await (const chunk of request) {
    text += chunk;
    if (text.length > MAX_BODY) throw new Error("Request is too long.");
  }
  return JSON.parse(text);
}

// A page on another website can't read the key, but it could spend it: only accept this page's own requests.
function isFromThisPage(request) {
  return request.headers.origin === `http://localhost:${PORT}`;
}

const server = createServer(async (request, response) => {
  const send = (status, type, content) => {
    response.writeHead(status, { "content-type": type });
    response.end(content);
  };
  if (request.method === "GET" && request.url === "/") return send(200, "text/html; charset=utf-8", page);
  if (request.method !== "POST" || request.url !== "/api" || !isFromThisPage(request)) {
    return send(404, "application/json", JSON.stringify({ error: "Not found" }));
  }
  let reply;
  try {
    reply = await callOpenAi(await readBody(request));
  } catch (error) {
    reply = { status: 500, error: error.message };
  }
  send(200, "application/json", JSON.stringify(reply));
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`ZeroUI test portal: http://localhost:${PORT}`);
  console.log("Press Ctrl+C to stop it.");
});
