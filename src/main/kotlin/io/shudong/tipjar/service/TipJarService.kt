package io.shudong.tipjar.service

import io.shudong.tipjar.config.Web3Properties
import io.shudong.tipjar.contracts.OwnershipFacet
import io.shudong.tipjar.contracts.TipJarFacet
import org.slf4j.LoggerFactory
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
    props: Web3Properties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val address = props.contractAddress

    // Facet wrappers loaded at the diamond's address — the diamond's fallback
    // routes each selector to the right facet
    private fun contract(): TipJarFacet =
        TipJarFacet.load(address, web3j, transactionManager, gasProvider)

    private fun ownership(): OwnershipFacet =
        OwnershipFacet.load(address, web3j, transactionManager, gasProvider)

    private fun balance(): BigInteger =
        web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().balance

    fun info(): TipJarInfo = TipJarInfo(
        contractAddress = address,
        owner = ownership().owner().send(),
        totalTipsWei = contract().totalTips().send(),
        balanceWei = balance(),
    )

    fun tip(message: String, amountWei: BigInteger): TipResult {
        require(amountWei > BigInteger.ZERO) { "amountWei must be positive" }
        log.debug("Sending tip of {} wei to {}", amountWei, address)
        val receipt = contract().tip(message, amountWei).send()
        val event = TipJarFacet.getTippedEvents(receipt).single()
        log.info(
            "Tip of {} wei mined: contract={}, tx={}, block={}, gasUsed={}",
            amountWei, address, receipt.transactionHash, receipt.blockNumber, receipt.gasUsed,
        )
        return TipResult(
            transactionHash = receipt.transactionHash,
            blockNumber = receipt.blockNumber,
            from = event.from,
            amountWei = event.amount,
            message = event.message,
        )
    }

    fun tips(): List<TipEntry> {
        val filter = EthFilter(
            DefaultBlockParameterName.EARLIEST,
            DefaultBlockParameterName.LATEST,
            address,
        ).addSingleTopic(EventEncoder.encode(TipJarFacet.TIPPED_EVENT))
        return web3j.ethGetLogs(filter).send().logs.map {
            val log = (it as EthLog.LogObject).get()
            val event = TipJarFacet.getTippedEventFromLog(log)
            TipEntry(
                from = event.from,
                amountWei = event.amount,
                message = event.message,
                transactionHash = log.transactionHash,
                blockNumber = log.blockNumber,
            )
        }
    }

    fun withdraw(): WithdrawResult {
        val recipient = ownership().owner().send()
        val balanceBefore = balance()
        log.debug("Withdrawing {} wei from {} to {}", balanceBefore, address, recipient)
        val receipt = contract().withdraw().send()
        log.info(
            "Withdrawal of {} wei mined: contract={}, recipient={}, tx={}, block={}",
            balanceBefore, address, recipient, receipt.transactionHash, receipt.blockNumber,
        )
        return WithdrawResult(
            transactionHash = receipt.transactionHash,
            blockNumber = receipt.blockNumber,
            withdrawnWei = balanceBefore,
            recipient = recipient,
        )
    }
}
