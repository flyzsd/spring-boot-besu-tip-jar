package io.shudong.tipjar.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.web3j.protocol.Web3j
import java.math.BigInteger

data class NodeInfo(
    val clientVersion: String,
    val chainId: BigInteger,
    val blockNumber: BigInteger,
)

@RestController
@RequestMapping("/api/contract")
class ContractController(private val web3j: Web3j) {

    @GetMapping("/node-info")
    fun nodeInfo(): NodeInfo = NodeInfo(
        clientVersion = web3j.web3ClientVersion().send().web3ClientVersion,
        chainId = web3j.ethChainId().send().chainId,
        blockNumber = web3j.ethBlockNumber().send().blockNumber,
    )
}
