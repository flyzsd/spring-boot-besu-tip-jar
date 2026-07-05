package io.shudong.tipjar.service

import io.shudong.tipjar.contracts.TipJar
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.protocol.Web3j
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger
import kotlin.jvm.optionals.getOrNull

data class DeploymentResult(
    val contractAddress: String,
    val transactionHash: String?,
    val blockNumber: BigInteger?,
    val owner: String,
)

@Service
class TipJarDeploymentService(
    private val web3j: Web3j,
    private val transactionManager: TransactionManager,
    private val gasProvider: ContractGasProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun deploy(): DeploymentResult {
        val contract = TipJar.deploy(web3j, transactionManager, gasProvider).send()
        val receipt = contract.transactionReceipt.getOrNull()
        log.info(
            "TipJar deployed at {}: tx={}, block={}",
            contract.contractAddress, receipt?.transactionHash, receipt?.blockNumber,
        )
        return DeploymentResult(
            contractAddress = contract.contractAddress,
            transactionHash = receipt?.transactionHash,
            blockNumber = receipt?.blockNumber,
            owner = contract.owner().send(),
        )
    }
}
