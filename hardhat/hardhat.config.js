require("@nomicfoundation/hardhat-ethers");
require("@nomicfoundation/hardhat-chai-matchers");

const { subtask } = require("hardhat/config");
const { TASK_COMPILE_SOLIDITY_GET_SOLC_BUILD } = require("hardhat/builtin-tasks/task-names");

// Use the compiler embedded in the npm `solc` package instead of letting Hardhat
// download one from binaries.soliditylang.org — the npm registry (mirrorable in
// restricted networks) stays the single delivery channel for the whole toolchain.
subtask(TASK_COMPILE_SOLIDITY_GET_SOLC_BUILD, async (args, hre, runSuper) => {
  const solc = require("solc/package.json");
  const solcVersion = solc.version.split("+")[0];
  if (args.solcVersion !== solcVersion) {
    throw new Error(
      `hardhat.config.js pins solidity ${args.solcVersion} but the npm solc package is ${solcVersion} — keep them in sync`
    );
  }
  return {
    compilerPath: require.resolve("solc/soljson.js"),
    isSolcJs: true,
    version: args.solcVersion,
    longVersion: solc.version,
  };
});

/** @type import('hardhat/config').HardhatUserConfig */
module.exports = {
  solidity: {
    // Enterprise-approved compiler version; 0.8.26 supports at most the cancun EVM
    // target (prague needs solc >= 0.8.27)
    version: "0.8.26",
    settings: {
      // Must not exceed the newest fork activated in besu/genesis.json (see AGENTS.md);
      // older-than-chain targets are safe
      evmVersion: "cancun",
      optimizer: { enabled: true, runs: 200 },
    },
  },
  networks: {
    // Same fork as the compile target so the tests catch EVM-version mismatches
    hardhat: { hardfork: "prague" },
    besu: {
      // BESU_RPC_URL lets the Testcontainers e2e test point this at its mapped port
      url: process.env.BESU_RPC_URL || "http://localhost:8545",
      chainId: 1337,
      // Besu dev account #1 (publicly documented key, local dev only)
      accounts: ["0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"],
    },
  },
};
