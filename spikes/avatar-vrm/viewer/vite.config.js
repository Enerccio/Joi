import {appendFileSync, realpathSync, writeFileSync} from "node:fs";
import {resolve} from "node:path";
import {defineConfig} from "vite";

const joiRoot = resolve(__dirname, "../../..");
const reportFile = resolve(joiRoot, "tmp/viewer-report.jsonl");

// The page posts its measurements (fps, load time, what it found in the model) here, so they can be read without a
// browser console: one JSON line per report in tmp/viewer-report.jsonl.
const report = {
  name: "joi-report",
  configureServer(server) {
    server.middlewares.use("/__report", (req, res) => {
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        appendFileSync(reportFile, JSON.stringify({ at: new Date().toISOString(), ...JSON.parse(body || "{}") }) + "\n");
        res.end("ok");
      });
    });
    // canvas snapshots (PNG data URLs) to tmp/snapshot-<name>.png
    server.middlewares.use("/__snapshot", (req, res) => {
      const name = new URL(req.url, "http://x").searchParams.get("name")?.replace(/[^\w-]/g, "") || "canvas";
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        writeFileSync(resolve(joiRoot, `tmp/snapshot-${name}.png`), Buffer.from(body.replace(/^data:image\/png;base64,/, ""), "base64"));
        res.end("ok");
      });
    });
  },
};

export default defineConfig({
  plugins: [report],
  server: {
    // the converted model lives in joi/tmp (excluded from git), served through /@fs/
    // tmp may be a symlink to another volume (the Annex image), Vite checks the real path
    fs: { allow: [joiRoot, realpathSync(resolve(joiRoot, "tmp"))] },
  },
});
