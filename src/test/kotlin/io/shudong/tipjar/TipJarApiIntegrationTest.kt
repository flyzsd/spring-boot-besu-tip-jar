package io.shudong.tipjar

import io.shudong.tipjar.contracts.TipJar
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.StaticGasProvider
import java.math.BigInteger
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val DEV_ACCOUNT_1 = "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73"
private const val DEV_ACCOUNT_1_KEY = "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"

/**
 * End-to-end scenario against a real Besu QBFT node (same image and config
 * as docker-compose.yml) through the REST API. The contract under test is
 * deployed via web3j before the Spring context boots, because the app reads
 * web3.contract-address at startup.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(OutputCaptureExtension::class)
class TipJarApiIntegrationTest {

    @LocalServerPort
    private var port = 0

    private val client: RestClient by lazy { RestClient.create("http://localhost:$port") }

    @Test
    @Order(1)
    fun `node-info reports the QBFT dev chain`() {
        val info = get("/api/contract/node-info")
        assertEquals(1337, (info["chainId"] as Number).toInt())
        assertTrue((info["clientVersion"] as String).startsWith("besu/"))
    }

    @Test
    @Order(2)
    fun `configured contract is served with the signer as owner`() {
        val info = get("/api/tipjar")
        assertEquals(deployedContract, info["contractAddress"])
        assertEquals(DEV_ACCOUNT_1, info["owner"])
    }

    @Test
    @Order(3)
    fun `deploy provisions a fresh contract without switching the app to it`() {
        val result = post("/api/contract/deploy")
        val newAddress = result["contractAddress"] as String
        assertTrue(newAddress.startsWith("0x"))
        assertEquals(DEV_ACCOUNT_1, result["owner"])
        assertNotEquals(deployedContract, newAddress)

        // the app keeps talking to the configured contract
        assertEquals(deployedContract, get("/api/tipjar")["contractAddress"])
    }

    @Test
    @Order(4)
    fun `tip is mined, reflected in state, and logged by the listener`(output: CapturedOutput) {
        val tip = post(
            "/api/tipjar/tip",
            mapOf("message" to "integration!", "amountWei" to 1000),
        )
        assertEquals(DEV_ACCOUNT_1, tip["from"])
        assertEquals(1000, (tip["amountWei"] as Number).toInt())

        val info = get("/api/tipjar")
        assertEquals(1000, (info["totalTipsWei"] as Number).toInt())
        assertEquals(1000, (info["balanceWei"] as Number).toInt())

        // TippedEventLogger polls the node's filter every 2s
        awaitUntil("Tipped event in console log") { output.out.contains("Tipped 1000 wei") }
    }

    @Test
    @Order(5)
    fun `tips lists the full history from event logs`() {
        val tips = client.get().uri("/api/tipjar/tips")
            .retrieve().body(List::class.java)!!
        val entry = tips.single() as Map<*, *>
        assertEquals("integration!", entry["message"])
        assertEquals(DEV_ACCOUNT_1, entry["from"])
    }

    @Test
    @Order(6)
    fun `zero tip is rejected with 400 before reaching the chain`() {
        val e = assertFailsWith<HttpClientErrorException> {
            post("/api/tipjar/tip", mapOf("message" to "free", "amountWei" to 0))
        }
        assertEquals(400, e.statusCode.value())
    }

    @Test
    @Order(7)
    fun `withdraw sweeps the balance to the owner`() {
        val result = post("/api/tipjar/withdraw")
        assertEquals(1000, (result["withdrawnWei"] as Number).toInt())
        assertEquals(DEV_ACCOUNT_1, result["recipient"])

        val info = get("/api/tipjar")
        assertEquals(0, (info["balanceWei"] as Number).toInt())
    }

    private fun get(uri: String): Map<*, *> =
        client.get().uri(uri).retrieve().body(Map::class.java)!!

    private fun post(uri: String, body: Map<String, Any>? = null): Map<*, *> {
        val spec = client.post().uri(uri)
        val withBody = if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body) else spec
        return withBody.retrieve().body(Map::class.java)!!
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        error("Timed out waiting for $what")
    }

    companion object {
        @Container
        @JvmStatic
        private val besu: GenericContainer<*> = GenericContainer("hyperledger/besu:26.6.1")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Paths.get("besu").toAbsolutePath()),
                "/config",
            )
            .withCommand(
                "--genesis-file=/config/genesis.json",
                "--node-private-key-file=/config/nodekey",
                "--rpc-http-enabled",
                "--rpc-http-host=0.0.0.0",
                "--host-allowlist=*",
                "--min-gas-price=0",
            )
            .withExposedPorts(8545)
            // /readiness expects >= 1 peer by default, which a single-node network never has
            .waitingFor(Wait.forHttp("/readiness?minPeers=0").forPort(8545))

        // Deployed directly via web3j once the container is up (lazily, from the
        // property supplier below) so the address is known before the context boots.
        private val deployedContract: String by lazy {
            val web3j = Web3j.build(HttpService("http://${besu.host}:${besu.getMappedPort(8545)}"))
            val txManager = RawTransactionManager(web3j, Credentials.create(DEV_ACCOUNT_1_KEY), 1337)
            val gasProvider = StaticGasProvider(BigInteger.ZERO, BigInteger.valueOf(4_000_000))
            TipJar.deploy(web3j, txManager, gasProvider).send().contractAddress
        }

        @DynamicPropertySource
        @JvmStatic
        fun web3Properties(registry: DynamicPropertyRegistry) {
            registry.add("web3.rpc-url") { "http://${besu.host}:${besu.getMappedPort(8545)}" }
            registry.add("web3.contract-address") { deployedContract }
        }
    }
}
