// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// Namespaced storage for the TipJar facet. Facets share the diamond's storage
/// space, so each facet's state lives in its own struct at a hashed slot.
library LibTipJar {
    bytes32 internal constant TIPJAR_STORAGE_POSITION = keccak256("io.shudong.tipjar.storage");

    struct TipJarStorage {
        uint256 totalTips;
    }

    function tipJarStorage() internal pure returns (TipJarStorage storage ts) {
        bytes32 position = TIPJAR_STORAGE_POSITION;
        assembly {
            ts.slot := position
        }
    }
}
