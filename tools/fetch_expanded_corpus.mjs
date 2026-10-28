// Compatibility entry point; collection is incremental and uses structured XML.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./collect_expanded_sources.py", import.meta.url));
const args = process.argv.slice(2);
if (!args.length) {
    console.error("Choose --discover and/or --ingest; no ledger is overwritten.");
    process.exit(2);
}
const result = spawnSync(process.env.PYTHON || "python", [script, ...args], { stdio: "inherit" });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
