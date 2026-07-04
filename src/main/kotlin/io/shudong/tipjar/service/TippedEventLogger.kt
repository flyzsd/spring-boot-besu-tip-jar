package io.shudong.tipjar.service

import io.reactivex.disposables.Disposable
import io.shudong.tipjar.contracts.TipJar
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.web3j.abi.EventEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter

/**
 * Logs every Tipped event on the chain from startup onward. Filters on the
 * event topic only (no contract address), so tips to any TipJar instance
 * are picked up, including ones deployed after the subscription started.
 */
@Component
class TippedEventLogger(private val web3j: Web3j) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    @PostConstruct
    fun subscribe() {
        val filter = EthFilter(
            DefaultBlockParameterName.LATEST,
            DefaultBlockParameterName.LATEST,
            emptyList(),
        ).addSingleTopic(EventEncoder.encode(TipJar.TIPPED_EVENT))
        subscription = web3j.ethLogFlowable(filter).subscribe(
            { eventLog ->
                val event = TipJar.getTippedEventFromLog(eventLog)
                log.info(
                    "Tipped {} wei from {} — \"{}\" (contract={}, block={}, tx={})",
                    event.amount,
                    event.from,
                    event.message,
                    eventLog.address,
                    eventLog.blockNumber,
                    eventLog.transactionHash,
                )
            },
            { error -> log.error("Tipped event subscription failed", error) },
        )
    }

    @PreDestroy
    fun unsubscribe() {
        subscription?.dispose()
    }
}
