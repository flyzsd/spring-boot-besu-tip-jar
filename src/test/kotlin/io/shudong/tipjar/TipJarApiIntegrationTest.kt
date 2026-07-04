package io.shudong.tipjar

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
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DEV_ACCOUNT_1 = "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73"

/**
 * End-to-end scenario against a real Besu QBFT node (same image and config
 * as docker-compose.yml) through the REST API: deploy → tip → history →
 * validation error → withdraw, including the console event listener.
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
    fun `deploy returns the contract with the signer as owner`() {
        val result = post("/api/contract/deploy")
        contractAddress = result["contractAddress"] as String
        assertTrue(contractAddress.startsWith("0x"))
        assertEquals(DEV_ACCOUNT_1, result["owner"])
    }

    @Test
    @Order(3)
    fun `tip is mined, reflected in state, and logged by the listener`(output: CapturedOutput) {
        val tip = post(
            "/api/tipjar/$contractAddress/tip",
            mapOf("message" to "integration!", "amountWei" to 1000),
        )
        assertEquals(DEV_ACCOUNT_1, tip["from"])
        assertEquals(1000, (tip["amountWei"] as Number).toInt())

        val info = get("/api/tipjar/$contractAddress")
        assertEquals(1000, (info["totalTipsWei"] as Number).toInt())
        assertEquals(1000, (info["balanceWei"] as Number).toInt())

        // TippedEventLogger polls the node's filter every 2s
        awaitUntil("Tipped event in console log") { output.out.contains("Tipped 1000 wei") }
    }

    @Test
    @Order(4)
    fun `tips lists the full history from event logs`() {
        val tips = client.get().uri("/api/tipjar/$contractAddress/tips")
            .retrieve().body(List::class.java)!!
        val entry = tips.single() as Map<*, *>
        assertEquals("integration!", entry["message"])
        assertEquals(DEV_ACCOUNT_1, entry["from"])
    }

    @Test
    @Order(5)
    fun `zero tip is rejected with 400 before reaching the chain`() {
        val e = assertFailsWith<HttpClientErrorException> {
            post("/api/tipjar/$contractAddress/tip", mapOf("message" to "free", "amountWei" to 0))
        }
        assertEquals(400, e.statusCode.value())
    }

    @Test
    @Order(6)
    fun `withdraw sweeps the balance to the owner`() {
        val result = post("/api/tipjar/$contractAddress/withdraw")
        assertEquals(1000, (result["withdrawnWei"] as Number).toInt())
        assertEquals(DEV_ACCOUNT_1, result["recipient"])

        val info = get("/api/tipjar/$contractAddress")
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
        private lateinit var contractAddress: String

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

        @DynamicPropertySource
        @JvmStatic
        fun web3Properties(registry: DynamicPropertyRegistry) {
            registry.add("web3.rpc-url") { "http://${besu.host}:${besu.getMappedPort(8545)}" }
        }
    }
}
