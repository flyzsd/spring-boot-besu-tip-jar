// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {LibDiamond} from "../libraries/LibDiamond.sol";

/// ERC-173 ownership. Note: owner() lives here and ONLY here — a selector can
/// map to exactly one facet, so no other facet may declare owner().
contract OwnershipFacet {
    function owner() external view returns (address) {
        return LibDiamond.contractOwner();
    }

    function transferOwnership(address _newOwner) external {
        LibDiamond.enforceIsContractOwner();
        LibDiamond.setContractOwner(_newOwner);
    }
}
