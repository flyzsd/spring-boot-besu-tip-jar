// The single diamond deployment choreography — used by scripts/deploy.js, the JS
// tests, and (via the deploy script) the Kotlin Testcontainers e2e test.
const { ethers } = require("hardhat");

const FacetCutAction = { Add: 0, Replace: 1, Remove: 2 };

const FACET_NAMES = ["DiamondCutFacet", "DiamondLoupeFacet", "OwnershipFacet", "TipJarFacet"];

function getSelectors(contract) {
  return contract.interface.fragments
    .filter((fragment) => fragment.type === "function")
    .map((fragment) => fragment.selector);
}

async function deployDiamond() {
  const facets = {};
  const cuts = [];
  for (const name of FACET_NAMES) {
    const facet = await ethers.deployContract(name);
    await facet.waitForDeployment();
    facets[name] = facet;
    cuts.push({
      facetAddress: await facet.getAddress(),
      action: FacetCutAction.Add,
      functionSelectors: getSelectors(facet),
    });
  }
  const diamond = await ethers.deployContract("Diamond", [cuts]);
  await diamond.waitForDeployment();
  return { diamond, facets, cuts };
}

module.exports = { FacetCutAction, FACET_NAMES, getSelectors, deployDiamond };
