package io.shudong.tipjar.service

import io.shudong.tipjar.contracts.TipJarFacet
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.protocol.core.methods.response.Log
import java.math.BigInteger

// codegen 4.9.4 wrappers lack the getTippedEventFromLog(Log) helper that newer
// versions generate; this replicates it with the public decoder API
internal object TippedEvents {
    fun fromLog(log: Log): TipJarFacet.TippedEventResponse {
        val event = TipJarFacet.TIPPED_EVENT
        val nonIndexed = FunctionReturnDecoder.decode(log.data, event.nonIndexedParameters)
        return TipJarFacet.TippedEventResponse().apply {
            this.log = log
            // topics[0] is the event signature; indexed params follow
            from = FunctionReturnDecoder.decodeIndexedValue(log.topics[1], event.indexedParameters[0]).value as String
            amount = nonIndexed[0].value as BigInteger
            message = nonIndexed[1].value as String
        }
    }
}
