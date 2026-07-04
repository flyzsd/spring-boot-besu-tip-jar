package io.shudong.tipjar

import io.shudong.tipjar.contracts.TipJar
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.web3j.abi.datatypes.Address
import org.web3j.crypto.Credentials
import org.web3j.evm.Configuration
import org.web3j.evm.EmbeddedWeb3jService
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.tx.Transfer
import org.web3j.tx.gas.StaticGasProvider
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Contract-level tests running the build's actual (prague-compiled) TipJar
 * bytecode on web3j-evm's in-process EVM — no node, no Docker.
 */
class TipJarContractTest {

    // Besu dev accounts 1 and 2 (publicly documented keys, local use only)
    private val owner =
        Credentials.create("0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")
    private val stranger =
        Credentials.create("0xc87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3")
    private val gasProvider = StaticGasProvider(BigInteger.ZERO, BigInteger.valueOf(4_000_000))

    private lateinit var web3j: Web3j
    private lateinit var tipJar: TipJar

    @BeforeEach
    fun deployFreshTipJar() {
        // Custom genesis: web3j-evm's default DEV genesis predates Shanghai and
        // rejects the prague-compiled bytecode (PUSH0)
        val genesis = javaClass.getResource("/embedded-genesis.json")!!
        web3j = Web3j.build(EmbeddedWeb3jService(Configuration(Address(owner.address), 1_000, genesis)))
        tipJar = TipJar.deploy(web3j, owner, gasProvider).send()
    }

    private fun balanceOf(address: String): BigInteger =
        web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().balance

    private fun contractBalance(): BigInteger = balanceOf(tipJar.contractAddress)

    private fun loadAsStranger(): TipJar {
        Transfer.sendFunds(web3j, owner, stranger.address, BigDecimal.TEN, Convert.Unit.ETHER).send()
        return TipJar.load(tipJar.contractAddress, web3j, stranger, gasProvider)
    }

    @Test
    fun `deploy sets deployer as owner with zero totals`() {
        assertEquals(owner.address, tipJar.owner().send())
        assertEquals(BigInteger.ZERO, tipJar.totalTips().send())
        assertEquals(BigInteger.ZERO, contractBalance())
    }

    @Test
    fun `tip increases totalTips and balance and emits Tipped`() {
        val receipt = tipJar.tip("great work!", ONE_ETHER).send()

        val event = TipJar.getTippedEvents(receipt).single()
        assertEquals(owner.address, event.from)
        assertEquals(ONE_ETHER, event.amount)
        assertEquals("great work!", event.message)

        assertEquals(ONE_ETHER, tipJar.totalTips().send())
        assertEquals(ONE_ETHER, contractBalance())
    }

    @Test
    fun `tips accumulate across senders`() {
        tipJar.tip("first", ONE_ETHER).send()
        loadAsStranger().tip("second", ONE_ETHER * BigInteger.TWO).send()

        val expected = ONE_ETHER * BigInteger.valueOf(3)
        assertEquals(expected, tipJar.totalTips().send())
        assertEquals(expected, contractBalance())
    }

    @Test
    fun `zero-value tip reverts with EmptyTip`() {
        assertFails { tipJar.tip("freeloader", BigInteger.ZERO).send() }

        assertEquals(BigInteger.ZERO, tipJar.totalTips().send())
        assertEquals(BigInteger.ZERO, contractBalance())
    }

    @Test
    fun `owner withdraw sweeps the balance to the owner`() {
        tipJar.tip("for you", ONE_ETHER).send()
        val ownerBalanceBefore = balanceOf(owner.address)

        tipJar.withdraw().send()

        assertEquals(BigInteger.ZERO, contractBalance())
        // Exact check works because the gas price is zero
        assertEquals(ownerBalanceBefore + ONE_ETHER, balanceOf(owner.address))
        // totalTips is a running total, not the current balance
        assertEquals(ONE_ETHER, tipJar.totalTips().send())
    }

    @Test
    fun `non-owner withdraw reverts with NotOwner`() {
        tipJar.tip("locked in", ONE_ETHER).send()

        assertFails { loadAsStranger().withdraw().send() }

        assertEquals(ONE_ETHER, contractBalance())
    }

    private companion object {
        val ONE_ETHER: BigInteger = Convert.toWei(BigDecimal.ONE, Convert.Unit.ETHER).toBigInteger()
    }
}
