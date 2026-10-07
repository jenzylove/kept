import assert from "node:assert/strict";
import test from "node:test";

import { GET } from "../app/.well-known/assetlinks.json/route.js";

test("serves Digital Asset Links with Phantom-compatible exact MIME type", async () => {
  const response = GET();
  assert.equal(response.headers.get("content-type"), "application/json");
  assert.deepEqual(await response.json(), [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: "app.kept",
        sha256_cert_fingerprints: [
          "14:95:A4:39:6E:61:04:6A:9D:EA:D1:17:DF:E6:1C:03:B7:24:DB:12:2F:82:72:D4:3A:62:47:0A:E3:FB:EB:C2",
        ],
      },
    },
  ]);
});
