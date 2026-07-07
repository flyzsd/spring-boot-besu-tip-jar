const { expect } = require("chai");
const { ethers } = require("hardhat");

const ONE_ETHER = ethers.parseEther("1");

describe("TipJar", function () {
  let tipJar;
  let owner;
  let stranger;

  beforeEach(async function () {
    [owner, stranger] = await ethers.getSigners();
    tipJar = await ethers.deployContract("TipJar");
  });

  it("deploy sets deployer as owner with zero totals", async function () {
    expect(await tipJar.owner()).to.equal(owner.address);
    expect(await tipJar.totalTips()).to.equal(0);
    expect(await ethers.provider.getBalance(tipJar)).to.equal(0);
  });

  it("tip increases totalTips and balance and emits Tipped", async function () {
    await expect(tipJar.tip("great work!", { value: ONE_ETHER }))
      .to.emit(tipJar, "Tipped")
      .withArgs(owner.address, ONE_ETHER, "great work!");

    expect(await tipJar.totalTips()).to.equal(ONE_ETHER);
    expect(await ethers.provider.getBalance(tipJar)).to.equal(ONE_ETHER);
  });

  it("tips accumulate across senders", async function () {
    await tipJar.tip("first", { value: ONE_ETHER });
    await tipJar.connect(stranger).tip("second", { value: ONE_ETHER * 2n });

    expect(await tipJar.totalTips()).to.equal(ONE_ETHER * 3n);
    expect(await ethers.provider.getBalance(tipJar)).to.equal(ONE_ETHER * 3n);
  });

  it("zero-value tip reverts with EmptyTip", async function () {
    await expect(tipJar.tip("freeloader", { value: 0 }))
      .to.be.revertedWithCustomError(tipJar, "EmptyTip");

    expect(await tipJar.totalTips()).to.equal(0);
    expect(await ethers.provider.getBalance(tipJar)).to.equal(0);
  });

  it("owner withdraw sweeps the balance to the owner", async function () {
    await tipJar.tip("for you", { value: ONE_ETHER });

    // changeEtherBalances ignores gas fees, so the owner delta is exact
    await expect(tipJar.withdraw())
      .to.changeEtherBalances([owner, tipJar], [ONE_ETHER, -ONE_ETHER]);

    // totalTips is a running total, not the current balance
    expect(await tipJar.totalTips()).to.equal(ONE_ETHER);
  });

  it("non-owner withdraw reverts with NotOwner", async function () {
    await tipJar.tip("locked in", { value: ONE_ETHER });

    await expect(tipJar.connect(stranger).withdraw())
      .to.be.revertedWithCustomError(tipJar, "NotOwner");

    expect(await ethers.provider.getBalance(tipJar)).to.equal(ONE_ETHER);
  });
});
