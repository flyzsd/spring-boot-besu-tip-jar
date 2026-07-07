// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

interface IDiamondCut {
    enum FacetCutAction {
        Add,
        Replace,
        Remove
    }

    struct FacetCut {
        address facetAddress;
        FacetCutAction action;
        bytes4[] functionSelectors;
    }

    event DiamondCut(FacetCut[] _diamondCut, address _init, bytes _calldata);

    /// @param _diamondCut The facet addresses and function selectors to add/replace/remove
    /// @param _init Address of a contract to delegatecall for initialization, or address(0)
    /// @param _calldata Call data for the _init delegatecall
    function diamondCut(FacetCut[] calldata _diamondCut, address _init, bytes calldata _calldata) external;
}
