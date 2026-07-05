package io.shudong.tipjar

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.shudong.tipjar.service.TipEntry
import io.shudong.tipjar.service.TipJarInfo
import io.shudong.tipjar.service.TipJarService
import io.shudong.tipjar.service.TipResult
import io.shudong.tipjar.web.TipJarController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.web3j.protocol.exceptions.TransactionException
import java.math.BigInteger

private const val CONTRACT = "0x42699a7612a82f1d9c36148af9c77354759b210b"
private const val OWNER = "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73"

/**
 * Web-layer slice test: JSON shapes and the error mapping (400/422),
 * with TipJarService mocked via springmockk.
 */
@WebMvcTest(TipJarController::class)
class TipJarControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var tipJarService: TipJarService

    @Test
    fun `info returns the configured contract state`() {
        every { tipJarService.info() } returns TipJarInfo(
            contractAddress = CONTRACT,
            owner = OWNER,
            totalTipsWei = BigInteger.valueOf(1000),
            balanceWei = BigInteger.valueOf(1000),
        )

        mockMvc.get("/api/tipjar").andExpect {
            status { isOk() }
            jsonPath("$.contractAddress") { value(CONTRACT) }
            jsonPath("$.owner") { value(OWNER) }
            jsonPath("$.totalTipsWei") { value(1000) }
            jsonPath("$.balanceWei") { value(1000) }
        }
    }

    @Test
    fun `tip returns the mined transaction details`() {
        every { tipJarService.tip("thanks", BigInteger.valueOf(500)) } returns TipResult(
            transactionHash = "0xabc",
            blockNumber = BigInteger.ONE,
            from = OWNER,
            amountWei = BigInteger.valueOf(500),
            message = "thanks",
        )

        mockMvc.post("/api/tipjar/tip") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message": "thanks", "amountWei": 500}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.transactionHash") { value("0xabc") }
            jsonPath("$.amountWei") { value(500) }
        }
    }

    @Test
    fun `tips returns the event history`() {
        every { tipJarService.tips() } returns listOf(
            TipEntry(
                from = OWNER,
                amountWei = BigInteger.valueOf(500),
                message = "thanks",
                transactionHash = "0xabc",
                blockNumber = BigInteger.ONE,
            ),
        )

        mockMvc.get("/api/tipjar/tips").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].message") { value("thanks") }
        }
    }

    @Test
    fun `invalid input maps to 400 with the error message`() {
        every { tipJarService.tip(any(), any()) } throws IllegalArgumentException("amountWei must be positive")

        mockMvc.post("/api/tipjar/tip") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message": "free", "amountWei": 0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("amountWei must be positive") }
        }
    }

    @Test
    fun `on-chain revert maps to 422 with the error message`() {
        every { tipJarService.withdraw() } throws TransactionException("Transaction reverted: NotOwner")

        mockMvc.post("/api/tipjar/withdraw").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error") { value("Transaction reverted: NotOwner") }
        }
    }
}