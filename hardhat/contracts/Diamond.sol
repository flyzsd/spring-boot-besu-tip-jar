// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {IDiamondCut} from "./interfaces/IDiamondCut.sol";
import {LibDiamond} from "./libraries/LibDiamond.sol";

/// Minimal EIP-2535 diamond: routes each function selector to a facet via
/// delegatecall. The deployer becomes the contract owner.
contract Diamond {
    error FunctionNotFound(bytes4 selector);

    constructor(IDiamondCut.FacetCut[] memory _diamondCut) payable {
        LibDiamond.setContractOwner(msg.sender);
        LibDiamond.diamondCut(_diamondCut, address(0), "");
    }

    // Plain ETH transfers (empty calldata) land here too: msg.sig is 0x00000000,
    // which no facet registers, so they revert — same behavior as a contract
    // without receive().
    fallback() external payable {
        address facet = LibDiamond.diamondStorage().selectorToFacet[msg.sig];
        if (facet == address(0)) revert FunctionNotFound(msg.sig);
        assembly {
            calldatacopy(0, 0, calldatasize())
            let result := delegatecall(gas(), facet, 0, calldatasize(), 0, 0)
            returndatacopy(0, 0, returndatasize())
            switch result
            case 0 {
                revert(0, returndatasize())
            }
            default {
                return(0, returndatasize())
            }
        }
    }
}
