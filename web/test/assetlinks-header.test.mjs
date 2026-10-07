import assert from "node:assert/strict";
import test from "node:test";

import nextConfig from "../next.config.mjs";

test("serves Digital Asset Links with Phantom-compatible exact MIME type", async () => {
  assert.equal(typeof nextConfig.headers, "function");
  const rules = await nextConfig.headers();
  const assetLinks = rules.find((rule) => rule.source === "/.well-known/assetlinks.json");
  assert.ok(assetLinks, "missing assetlinks response-header rule");
  assert.deepEqual(assetLinks.headers, [{ key: "Content-Type", value: "application/json" }]);
});
