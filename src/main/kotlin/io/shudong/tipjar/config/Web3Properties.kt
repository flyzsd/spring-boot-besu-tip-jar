package io.shudong.tipjar.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigInteger

@ConfigurationProperties("web3")
data class Web3Properties(
    val rpcUrl: String,
    val privateKey: String,
    val contractAddress: String,
    val gasPrice: BigInteger = BigInteger.ZERO,
    val gasLimit: BigInteger = BigInteger.valueOf(4_000_000),
)
