// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {LibDiamond} from "../libraries/LibDiamond.sol";
import {LibTipJar} from "../libraries/LibTipJar.sol";

contract TipJarFacet {
    error NotOwner();
    error EmptyTip();

    event Tipped(address indexed from, uint256 amount, string message);

    function tip(string calldata message) external payable {
        if (msg.value == 0) revert EmptyTip();
        LibTipJar.tipJarStorage().totalTips += msg.value;
        emit Tipped(msg.sender, msg.value, message);
    }

    function withdraw() external {
        if (msg.sender != LibDiamond.contractOwner()) revert NotOwner();
        payable(msg.sender).transfer(address(this).balance);
    }

    function totalTips() external view returns (uint256) {
        return LibTipJar.tipJarStorage().totalTips;
    }
}
