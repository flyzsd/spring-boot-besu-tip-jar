// Deploys the TipJar diamond (npx hardhat run scripts/deploy.js --network besu).
const { ethers } = require("hardhat");
const { deployDiamond } = require("../lib/diamond");

async function main() {
  const { diamond, facets } = await deployDiamond();
  const diamondAddress = await diamond.getAddress();
  for (const [name, facet] of Object.entries(facets)) {
    console.log(`${name} deployed at ${await facet.getAddress()}`);
  }
  const ownership = await ethers.getContractAt("OwnershipFacet", diamondAddress);
  console.log(`TipJar diamond deployed at ${diamondAddress} (owner: ${await ownership.owner()})`);
  console.log("If this differs from web3.contract-address, update application.yml and restart the app.");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
