const assetLinks = [
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
];

const body = new TextEncoder().encode(JSON.stringify(assetLinks));

export function GET() {
  return new Response(body, {
    headers: {
      "Cache-Control": "public, max-age=300",
      "Content-Type": "application/json",
    },
  });
}