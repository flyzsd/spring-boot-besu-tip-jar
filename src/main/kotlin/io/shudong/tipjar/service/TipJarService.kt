package io.shudong.tipjar.service

import io.shudong.tipjar.contracts.TipJar
import org.springframework.stereotype.Service
import org.web3j.abi.EventEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger

data class TipJarInfo(
    val contractAddress: String,
    val owner: String,
    val totalTipsWei: BigInteger,
    val balanceWei: BigInteger,
)

data class TipResult(
    val transactionHash: String,
    val blockNumber: BigInteger,
    val from: String,
    val amountWei: BigInteger,
    val message: String,
)

data class TipEntry(
    val from: String,
    val amountWei: BigInteger,
    val message: String,
    val transactionHash: String,
    val blockNumber: BigInteger,
)

data class WithdrawResult(
    val transactionHash: String,
    val blockNumber: BigInteger,
    val withdrawnWei: BigInteger,
    val recipient: String,
)

@Service
class TipJarService(
    private val web3j: Web3j,
    private val transactionManager: TransactionManager,
    private val gasProvider: ContractGasProvider,
) {
    private fun contract(address: String): TipJar =
        TipJar.load(address, web3j, transactionManager, gasProvider)

    private fun balanceOf(address: String): BigInteger =
        web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().balance

    fun info(address: String): TipJarInfo {
        val contract = contract(address)
        return TipJarInfo(
            contractAddress = address,
            owner = contract.owner().send(),
            totalTipsWei = contract.totalTips().send(),
            balanceWei = balanceOf(address),
        )
    }

    fun tip(address: String, message: String, amountWei: BigInteger): TipResult {
        require(amountWei > BigInteger.ZERO) { "amountWei must be positive" }
        val receipt = contract(address).tip(message, amountWei).send()
        val event = TipJar.getTippedEvents(receipt).single()
        return TipResult(
            transactionHash = receipt.transactionHash,
            blockNumber = receipt.blockNumber,
            from = event.from,
            amountWei = event.amount,
            message = event.message,
        )
    }

    fun tips(address: String): List<TipEntry> {
        val filter = EthFilter(
            DefaultBlockParameterName.EARLIEST,
            DefaultBlockParameterName.LATEST,
            address,
        ).addSingleTopic(EventEncoder.encode(TipJar.TIPPED_EVENT))
        return web3j.ethGetLogs(filter).send().logs.map {
            val log = (it as EthLog.LogObject).get()
            val event = TipJar.getTippedEventFromLog(log)
            TipEntry(
                from = event.from,
                amountWei = event.amount,
                message = event.message,
                transactionHash = log.transactionHash,
                blockNumber = log.blockNumber,
            )
        }
    }

    fun withdraw(address: String): WithdrawResult {
        val contract = contract(address)
        val recipient = contract.owner().send()
        val balanceBefore = balanceOf(address)
        val receipt = contract.withdraw().send()
        return WithdrawResult(
            transactionHash = receipt.transactionHash,
            blockNumber = receipt.blockNumber,
            withdrawnWei = balanceBefore,
            recipient = recipient,
        )
    }
}
