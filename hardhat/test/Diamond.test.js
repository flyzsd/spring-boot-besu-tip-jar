const { expect } = require("chai");
const { ethers } = require("hardhat");
const { deployDiamond, getSelectors, FacetCutAction } = require("../lib/diamond");

describe("Diamond", function () {
  let diamond;
  let diamondAddress;
  let facets;
  let cuts;
  let loupe;
  let cut;
  let owner;
  let stranger;

  beforeEach(async function () {
    [owner, stranger] = await ethers.getSigners();
    ({ diamond, facets, cuts } = await deployDiamond());
    diamondAddress = await diamond.getAddress();
    loupe = await ethers.getContractAt("DiamondLoupeFacet", diamondAddress);
    cut = await ethers.getContractAt("DiamondCutFacet", diamondAddress);
  });

  describe("loupe", function () {
    it("reports all four facets with their selectors", async function () {
      const reported = await loupe.facets();
      expect(reported.length).to.equal(4);

      for (const expected of cuts) {
        const entry = reported.find((f) => f.facetAddress === expected.facetAddress);
        expect(entry, `facet ${expected.facetAddress} missing from loupe`).to.not.be.undefined;
        expect([...entry.functionSelectors]).to.have.members(expected.functionSelectors);
      }
    });

    it("resolves a selector to its facet", async function () {
      const tipSelector = facets.TipJarFacet.interface.getFunction("tip").selector;
      expect(await loupe.facetAddress(tipSelector))
        .to.equal(await facets.TipJarFacet.getAddress());
    });

    it("returns zero address for unknown selectors", async function () {
      expect(await loupe.facetAddress("0xdeadbeef")).to.equal(ethers.ZeroAddress);
    });
  });

  describe("dispatcher", function () {
    it("reverts unknown selectors with FunctionNotFound", async function () {
      await expect(owner.sendTransaction({ to: diamondAddress, data: "0xdeadbeef" }))
        .to.be.revertedWithCustomError(diamond, "FunctionNotFound");
    });

    it("rejects plain ETH transfers", async function () {
      await expect(owner.sendTransaction({ to: diamondAddress, value: 1n }))
        .to.be.revertedWithCustomError(diamond, "FunctionNotFound");
    });
  });

  describe("diamondCut", function () {
    it("is owner-gated", async function () {
      const lib = await ethers.getContractAt("LibDiamond", diamondAddress);
      await expect(cut.connect(stranger).diamondCut([], ethers.ZeroAddress, "0x"))
        .to.be.revertedWithCustomError(lib, "NotContractOwner");
    });

    it("Replace routes a selector to a new facet implementation", async function () {
      const replacement = await ethers.deployContract("TipJarFacet");
      const tipSelector = facets.TipJarFacet.interface.getFunction("tip").selector;

      await cut.diamondCut(
        [{
          facetAddress: await replacement.getAddress(),
          action: FacetCutAction.Replace,
          functionSelectors: [tipSelector],
        }],
        ethers.ZeroAddress,
        "0x",
      );

      expect(await loupe.facetAddress(tipSelector)).to.equal(await replacement.getAddress());
      // behavior intact through the new facet
      const tipJar = await ethers.getContractAt("TipJarFacet", diamondAddress);
      await tipJar.tip("still works", { value: 1000n });
      expect(await tipJar.totalTips()).to.equal(1000n);
    });

    it("Remove unregisters a selector", async function () {
      const tipJar = await ethers.getContractAt("TipJarFacet", diamondAddress);
      const withdrawSelector = facets.TipJarFacet.interface.getFunction("withdraw").selector;

      await cut.diamondCut(
        [{
          facetAddress: ethers.ZeroAddress,
          action: FacetCutAction.Remove,
          functionSelectors: [withdrawSelector],
        }],
        ethers.ZeroAddress,
        "0x",
      );

      await expect(tipJar.withdraw()).to.be.revertedWithCustomError(diamond, "FunctionNotFound");
      expect(await loupe.facetAddress(withdrawSelector)).to.equal(ethers.ZeroAddress);
    });

    it("Add registers selectors from a new facet deployment", async function () {
      // remove tip, then add it back from a fresh facet instance
      const tipSelector = facets.TipJarFacet.interface.getFunction("tip").selector;
      await cut.diamondCut(
        [{ facetAddress: ethers.ZeroAddress, action: FacetCutAction.Remove, functionSelectors: [tipSelector] }],
        ethers.ZeroAddress,
        "0x",
      );

      const fresh = await ethers.deployContract("TipJarFacet");
      await cut.diamondCut(
        [{ facetAddress: await fresh.getAddress(), action: FacetCutAction.Add, functionSelectors: [tipSelector] }],
        ethers.ZeroAddress,
        "0x",
      );

      const tipJar = await ethers.getContractAt("TipJarFacet", diamondAddress);
      await tipJar.tip("re-added", { value: 500n });
      expect(await tipJar.totalTips()).to.equal(500n);
    });
  });
});
