package io.shudong.tipjar.service

import io.shudong.tipjar.contracts.TipJar
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
    fun deploy(): DeploymentResult {
        val contract = TipJar.deploy(web3j, transactionManager, gasProvider).send()
        val receipt = contract.transactionReceipt.getOrNull()
        return DeploymentResult(
            contractAddress = contract.contractAddress,
            transactionHash = receipt?.transactionHash,
            blockNumber = receipt?.blockNumber,
            owner = contract.owner().send(),
        )
    }
}
