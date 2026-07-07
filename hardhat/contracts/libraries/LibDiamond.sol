// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {IDiamondCut} from "../interfaces/IDiamondCut.sol";

/// Owns the diamond's core storage (selector routing + ownership) and the cut logic.
/// Facets call into this library; they must never declare their own declaration-order
/// state variables (all state lives in namespaced storage structs like this one).
library LibDiamond {
    bytes32 internal constant DIAMOND_STORAGE_POSITION = keccak256("diamond.standard.storage");

    struct DiamondStorage {
        mapping(bytes4 => address) selectorToFacet;
        // Selector enumeration for the loupe; position mapping enables swap-and-pop removal
        bytes4[] selectors;
        mapping(bytes4 => uint256) selectorPosition;
        address contractOwner;
    }

    error NotContractOwner();
    error NoSelectorsInFacet();
    error FunctionAlreadyExists(bytes4 selector);
    error FunctionDoesNotExist(bytes4 selector);
    error FacetAddressIsZero();
    error FacetAddressMustBeZero();
    error ReplaceWithSameFacet(bytes4 selector);
    error InitCallFailed();
    error InitCalldataMismatch();

    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    function diamondStorage() internal pure returns (DiamondStorage storage ds) {
        bytes32 position = DIAMOND_STORAGE_POSITION;
        assembly {
            ds.slot := position
        }
    }

    function setContractOwner(address _newOwner) internal {
        DiamondStorage storage ds = diamondStorage();
        address previousOwner = ds.contractOwner;
        ds.contractOwner = _newOwner;
        emit OwnershipTransferred(previousOwner, _newOwner);
    }

    function contractOwner() internal view returns (address) {
        return diamondStorage().contractOwner;
    }

    function enforceIsContractOwner() internal view {
        if (msg.sender != diamondStorage().contractOwner) revert NotContractOwner();
    }

    function diamondCut(IDiamondCut.FacetCut[] memory _diamondCut, address _init, bytes memory _calldata) internal {
        for (uint256 i = 0; i < _diamondCut.length; i++) {
            IDiamondCut.FacetCut memory cut = _diamondCut[i];
            if (cut.functionSelectors.length == 0) revert NoSelectorsInFacet();
            if (cut.action == IDiamondCut.FacetCutAction.Add) {
                addFunctions(cut.facetAddress, cut.functionSelectors);
            } else if (cut.action == IDiamondCut.FacetCutAction.Replace) {
                replaceFunctions(cut.facetAddress, cut.functionSelectors);
            } else {
                removeFunctions(cut.facetAddress, cut.functionSelectors);
            }
        }
        emit IDiamondCut.DiamondCut(_diamondCut, _init, _calldata);
        initializeDiamondCut(_init, _calldata);
    }

    function addFunctions(address _facet, bytes4[] memory _selectors) private {
        if (_facet == address(0)) revert FacetAddressIsZero();
        DiamondStorage storage ds = diamondStorage();
        for (uint256 i = 0; i < _selectors.length; i++) {
            bytes4 selector = _selectors[i];
            if (ds.selectorToFacet[selector] != address(0)) revert FunctionAlreadyExists(selector);
            ds.selectorToFacet[selector] = _facet;
            ds.selectorPosition[selector] = ds.selectors.length;
            ds.selectors.push(selector);
        }
    }

    function replaceFunctions(address _facet, bytes4[] memory _selectors) private {
        if (_facet == address(0)) revert FacetAddressIsZero();
        DiamondStorage storage ds = diamondStorage();
        for (uint256 i = 0; i < _selectors.length; i++) {
            bytes4 selector = _selectors[i];
            address oldFacet = ds.selectorToFacet[selector];
            if (oldFacet == address(0)) revert FunctionDoesNotExist(selector);
            if (oldFacet == _facet) revert ReplaceWithSameFacet(selector);
            ds.selectorToFacet[selector] = _facet;
        }
    }

    function removeFunctions(address _facet, bytes4[] memory _selectors) private {
        // Per EIP-2535, the facet address for a Remove cut must be address(0)
        if (_facet != address(0)) revert FacetAddressMustBeZero();
        DiamondStorage storage ds = diamondStorage();
        for (uint256 i = 0; i < _selectors.length; i++) {
            bytes4 selector = _selectors[i];
            if (ds.selectorToFacet[selector] == address(0)) revert FunctionDoesNotExist(selector);
            // Swap-and-pop from the enumeration array
            uint256 position = ds.selectorPosition[selector];
            uint256 lastPosition = ds.selectors.length - 1;
            if (position != lastPosition) {
                bytes4 lastSelector = ds.selectors[lastPosition];
                ds.selectors[position] = lastSelector;
                ds.selectorPosition[lastSelector] = position;
            }
            ds.selectors.pop();
            delete ds.selectorPosition[selector];
            delete ds.selectorToFacet[selector];
        }
    }

    function initializeDiamondCut(address _init, bytes memory _calldata) private {
        if (_init == address(0)) {
            if (_calldata.length != 0) revert InitCalldataMismatch();
            return;
        }
        if (_calldata.length == 0) revert InitCalldataMismatch();
        (bool success, bytes memory error) = _init.delegatecall(_calldata);
        if (!success) {
            if (error.length > 0) {
                assembly {
                    revert(add(error, 32), mload(error))
                }
            }
            revert InitCallFailed();
        }
    }
}
