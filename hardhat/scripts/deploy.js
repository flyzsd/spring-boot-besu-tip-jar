// Deploys TipJar to the target network (npx hardhat run scripts/deploy.js --network besu).
const hre = require("hardhat");

async function main() {
  const tipJar = await hre.ethers.deployContract("TipJar");
  await tipJar.waitForDeployment();
  const address = await tipJar.getAddress();
  console.log(`TipJar deployed at ${address} (owner: ${await tipJar.owner()})`);
  console.log("If this differs from web3.contract-address, update application.yml and restart the app.");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
