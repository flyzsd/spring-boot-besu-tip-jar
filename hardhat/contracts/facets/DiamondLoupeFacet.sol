// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {IDiamondLoupe} from "../interfaces/IDiamondLoupe.sol";
import {LibDiamond} from "../libraries/LibDiamond.sol";

/// Introspection over the selector registry. O(n²) over the selector list —
/// fine for a diamond with a handful of facets.
contract DiamondLoupeFacet is IDiamondLoupe {
    function facets() external view override returns (Facet[] memory facets_) {
        LibDiamond.DiamondStorage storage ds = LibDiamond.diamondStorage();
        uint256 selectorCount = ds.selectors.length;
        address[] memory addresses = new address[](selectorCount);
        uint256 facetCount = 0;
        Facet[] memory temp = new Facet[](selectorCount);

        for (uint256 i = 0; i < selectorCount; i++) {
            bytes4 selector = ds.selectors[i];
            address facet = ds.selectorToFacet[selector];
            bool seen = false;
            for (uint256 j = 0; j < facetCount; j++) {
                if (addresses[j] == facet) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                addresses[facetCount] = facet;
                temp[facetCount] = Facet(facet, facetFunctionSelectors(facet));
                facetCount++;
            }
        }

        facets_ = new Facet[](facetCount);
        for (uint256 i = 0; i < facetCount; i++) {
            facets_[i] = temp[i];
        }
    }

    function facetFunctionSelectors(address _facet) public view override returns (bytes4[] memory selectors_) {
        LibDiamond.DiamondStorage storage ds = LibDiamond.diamondStorage();
        uint256 selectorCount = ds.selectors.length;
        uint256 count = 0;
        bytes4[] memory temp = new bytes4[](selectorCount);
        for (uint256 i = 0; i < selectorCount; i++) {
            bytes4 selector = ds.selectors[i];
            if (ds.selectorToFacet[selector] == _facet) {
                temp[count] = selector;
                count++;
            }
        }
        selectors_ = new bytes4[](count);
        for (uint256 i = 0; i < count; i++) {
            selectors_[i] = temp[i];
        }
    }

    function facetAddresses() external view override returns (address[] memory addresses_) {
        LibDiamond.DiamondStorage storage ds = LibDiamond.diamondStorage();
        uint256 selectorCount = ds.selectors.length;
        address[] memory temp = new address[](selectorCount);
        uint256 count = 0;
        for (uint256 i = 0; i < selectorCount; i++) {
            address facet = ds.selectorToFacet[ds.selectors[i]];
            bool seen = false;
            for (uint256 j = 0; j < count; j++) {
                if (temp[j] == facet) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                temp[count] = facet;
                count++;
            }
        }
        addresses_ = new address[](count);
        for (uint256 i = 0; i < count; i++) {
            addresses_[i] = temp[i];
        }
    }

    function facetAddress(bytes4 _functionSelector) external view override returns (address) {
        return LibDiamond.diamondStorage().selectorToFacet[_functionSelector];
    }
}
