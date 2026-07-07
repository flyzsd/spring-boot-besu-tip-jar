// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;



contract TipJar {
    address public owner;
    uint256 public totalTips;

    error NotOwner();
    error EmptyTip();

    event Tipped(address indexed from, uint256 amount, string message);

    constructor() {
        owner = msg.sender;
    }

    function tip(string calldata message) external payable {
        if (msg.value == 0) revert EmptyTip();
        totalTips += msg.value;
        emit Tipped(msg.sender, msg.value, message);
    }

    function withdraw() external {
        if (msg.sender != owner) revert NotOwner();
        payable(owner).transfer(address(this).balance);
    }
}