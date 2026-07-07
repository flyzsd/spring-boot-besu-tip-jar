// Exports Hardhat artifacts as the flat .abi/.bin files web3j codegen expects.
// Walks nested source dirs (facets/, interfaces/, libraries/) recursively.
// Usage: node scripts/export-web3j-artifacts.js <outputDir>
const fs = require("fs");
const path = require("path");

const outputDir = process.argv[2];
if (!outputDir) {
  console.error("Usage: node export-web3j-artifacts.js <outputDir>");
  process.exit(1);
}

const contractsDir = path.join(__dirname, "..", "artifacts", "contracts");
fs.mkdirSync(outputDir, { recursive: true });

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(fullPath);
      continue;
    }
    if (!entry.name.endsWith(".json") || entry.name.endsWith(".dbg.json")) continue;
    const artifact = JSON.parse(fs.readFileSync(fullPath, "utf8"));
    if (!artifact.bytecode || artifact.bytecode === "0x") continue; // interfaces and pure libraries
    const name = artifact.contractName;
    fs.writeFileSync(path.join(outputDir, `${name}.abi`), JSON.stringify(artifact.abi));
    fs.writeFileSync(path.join(outputDir, `${name}.bin`), artifact.bytecode.replace(/^0x/, ""));
    console.log(`Exported ${name}.abi / ${name}.bin to ${outputDir}`);
  }
}

walk(contractsDir);
