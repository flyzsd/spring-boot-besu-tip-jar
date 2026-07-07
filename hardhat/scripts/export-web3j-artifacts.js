// Exports Hardhat artifacts as the flat .abi/.bin files web3j codegen expects.
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

for (const solDir of fs.readdirSync(contractsDir)) {
  for (const file of fs.readdirSync(path.join(contractsDir, solDir))) {
    if (!file.endsWith(".json") || file.endsWith(".dbg.json")) continue;
    const artifact = JSON.parse(fs.readFileSync(path.join(contractsDir, solDir, file), "utf8"));
    const name = artifact.contractName;
    fs.writeFileSync(path.join(outputDir, `${name}.abi`), JSON.stringify(artifact.abi));
    fs.writeFileSync(path.join(outputDir, `${name}.bin`), artifact.bytecode.replace(/^0x/, ""));
    console.log(`Exported ${name}.abi / ${name}.bin to ${outputDir}`);
  }
}
