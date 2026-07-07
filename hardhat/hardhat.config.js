require("@nomicfoundation/hardhat-ethers");
require("@nomicfoundation/hardhat-chai-matchers");

/** @type import('hardhat/config').HardhatUserConfig */
module.exports = {
  solidity: {
    version: "0.8.30",
    settings: {
      // Must not exceed the newest fork activated in besu/genesis.json (see AGENTS.md)
      evmVersion: "prague",
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
