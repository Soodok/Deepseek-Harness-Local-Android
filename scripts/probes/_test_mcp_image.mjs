#!/usr/bin/env node
/**
 * Runtime checks for the MCP device server's image path (v1.7.2).
 *
 * Why a separate probe: the screenshot-to-image-block conversion is the whole
 * point of the change, and it fails silently -- a wrong base64 alphabet or a
 * missing mimeType makes the harness project the image as a text diagnostic
 * ("[image unavailable: ...]") while the tool call still reports success. So
 * the assertions below pin the exact wire shape the harness validates:
 * canonical base64, one of the four declared media types, and a text block
 * that no longer carries the payload.
 *
 * Run: node scripts/probes/_test_mcp_image.mjs
 */

import {
  buildTools,
  imageBlockOf,
  parseOnly,
  withoutPayload,
} from "../../app/src/main/assets/tools/mcp-device.mjs";

let passed = 0;
const failures = [];
function check(label, cond) {
  if (cond) { passed++; console.log("  PASS  " + label); }
  else { failures.push(label); console.log("  FAIL  " + label); }
}

// 1x1 PNG, the smallest canonical payload there is.
const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
const shot = { id: "screenshot", method: "GET", path: "/screenshot" };
const screen = { id: "screen", method: "GET", path: "/screen" };

console.log("== imageBlockOf ==");
const block = imageBlockOf(shot, { ok: true, format: "png", width: 1080, height: 2280, base64: PNG });
check("a screenshot becomes an image block", block !== undefined);
check("the block is declared as an image", block?.type === "image");
check("png maps to image/png", block?.mimeType === "image/png");
check("the payload is carried verbatim", block?.data === PNG);
check(
  "the payload is canonical base64 (the harness re-encodes and compares)",
  Buffer.from(block.data, "base64").toString("base64") === block.data,
);

check("jpeg maps to image/jpeg",
  imageBlockOf(shot, { ok: true, format: "jpeg", base64: PNG })?.mimeType === "image/jpeg");
check("webp maps to image/webp",
  imageBlockOf(shot, { ok: true, format: "webp", base64: PNG })?.mimeType === "image/webp");
check("an unknown format falls back to png",
  imageBlockOf(shot, { ok: true, format: "tiff", base64: PNG })?.mimeType === "image/png");

check("a non-image endpoint never yields a block",
  imageBlockOf(screen, { ok: true, base64: PNG }) === undefined);
check("a response without base64 yields no block",
  imageBlockOf(shot, { ok: true, format: "png" }) === undefined);
check("an error response yields no block",
  imageBlockOf(shot, { ok: false, error: "screenshot failed" }) === undefined);
check("a null response yields no block", imageBlockOf(shot, null) === undefined);
check("whitespace-wrapped base64 is rejected (not canonical)",
  imageBlockOf(shot, { ok: true, base64: " " + PNG + " " }) === undefined);
check("a data: URL prefix is rejected (not bare base64)",
  imageBlockOf(shot, { ok: true, base64: "data:image/png;base64," + PNG }) === undefined);
check("an empty string yields no block",
  imageBlockOf(shot, { ok: true, base64: "" }) === undefined);

console.log("== withoutPayload ==");
const trimmed = withoutPayload({ ok: true, format: "png", width: 1080, height: 2280, base64: PNG });
check("the payload is dropped from the text form", trimmed.base64 === undefined);
check("the dimensions survive", trimmed.width === 1080 && trimmed.height === 2280);
check("a note replaces the payload", typeof trimmed.image === "string");
check("a value without base64 is returned unchanged",
  withoutPayload({ ok: true, format: "png" }).image === undefined);

console.log("== parseOnly ==");
check("--only a,b parses two ids",
  [...parseOnly(["--only", "a,b"])].join(",") === "a,b");
check("--only=a,b parses two ids",
  [...parseOnly(["--only=a,b"])].join(",") === "a,b");
check("repeated --only values merge",
  [...parseOnly(["--only", "a", "--only", "b"])].join(",") === "a,b");
check("no flag means no filter", parseOnly(["serve"]) === undefined);
check("blank ids are ignored", parseOnly(["--only", " , a , "])?.size === 1);

console.log("== buildTools filter ==");
const groups = [{ group: "g", items: [shot, screen] }];
const all = buildTools(groups);
check("without a filter every endpoint becomes a tool", all.length === 2);
const only = buildTools(groups, new Set(["screenshot"]));
check("with a filter only the named endpoint survives", only.length === 1);
check("and it keeps its catalog name", only[0]?.name === "screenshot");
check("filtering to an unknown id yields nothing (serve() then refuses)",
  buildTools(groups, new Set(["nope"])).length === 0);

console.log();
if (failures.length > 0) {
  console.log("FAILED " + failures.length + " of " + (passed + failures.length));
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
}
console.log(passed + " checks passed");