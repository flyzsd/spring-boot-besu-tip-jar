package io.shudong.tipjar.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import org.web3j.tx.gas.StaticGasProvider
import org.web3j.utils.Async

@Configuration
class Web3jConfig(private val props: Web3Properties) {

    @Bean(destroyMethod = "shutdown")
    fun web3j(): Web3j =
        // Poll filters at the QBFT block period (2s) instead of web3j's 15s default
        Web3j.build(HttpService(props.rpcUrl), 2_000, Async.defaultExecutorService())

    @Bean
    fun credentials(): Credentials = Credentials.create(props.privateKey)

    @Bean
    fun transactionManager(web3j: Web3j, credentials: Credentials): TransactionManager {
        // Sign with the node's actual chain id (EIP-155); Besu dev mode uses 2018
        val chainId = web3j.ethChainId().send().chainId.toLong()
        return RawTransactionManager(web3j, credentials, chainId)
    }

    @Bean
    fun gasProvider(): ContractGasProvider = StaticGasProvider(props.gasPrice, props.gasLimit)
}
