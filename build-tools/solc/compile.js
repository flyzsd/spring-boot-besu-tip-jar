// Usage: node compile.js <contract.sol> <evmVersion> <outputDir>
// The evmVersion must not exceed the newest fork activated in the chain genesis
// (see AGENTS.md); it is passed explicitly because solc's default moves between releases.
const fs = require('fs');
const path = require('path');
const solc = require('solc');

const [contractPath, evmVersion, outputDir] = process.argv.slice(2);
if (!contractPath || !evmVersion || !outputDir) {
  console.error('Usage: node compile.js <contract.sol> <evmVersion> <outputDir>');
  process.exit(1);
}

console.log(`solc-js ${solc.version()} | evmVersion=${evmVersion} | ${contractPath}`);

const input = {
  language: 'Solidity',
  sources: {
    [path.basename(contractPath)]: { content: fs.readFileSync(contractPath, 'utf8') }
  },
  settings: {
    evmVersion,
    optimizer: { enabled: true, runs: 200 },
    outputSelection: { '*': { '*': ['abi', 'evm.bytecode.object'] } }
  }
};

const output = JSON.parse(solc.compile(JSON.stringify(input)));

let failed = false;
for (const message of output.errors ?? []) {
  if (message.severity === 'error') {
    failed = true;
    console.error(message.formattedMessage);
  } else {
    console.warn(message.formattedMessage);
  }
}
if (failed) process.exit(1);

fs.mkdirSync(outputDir, { recursive: true });
for (const contracts of Object.values(output.contracts)) {
  for (const [contractName, contract] of Object.entries(contracts)) {
    fs.writeFileSync(path.join(outputDir, `${contractName}.abi`), JSON.stringify(contract.abi));
    fs.writeFileSync(path.join(outputDir, `${contractName}.bin`), contract.evm.bytecode.object);
    console.log(`Wrote ${contractName}.abi / ${contractName}.bin to ${outputDir}`);
  }
}