package io.shudong.tipjar.web

import io.shudong.tipjar.service.TipEntry
import io.shudong.tipjar.service.TipJarInfo
import io.shudong.tipjar.service.TipJarService
import io.shudong.tipjar.service.TipResult
import io.shudong.tipjar.service.WithdrawResult
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.exceptions.ContractCallException
import java.math.BigInteger

data class TipRequest(
    val message: String,
    val amountWei: BigInteger,
)

data class ApiError(val error: String)

@RestController
@RequestMapping("/api/tipjar")
class TipJarController(private val tipJarService: TipJarService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun info(): TipJarInfo = tipJarService.info()

    @PostMapping("/tip")
    fun tip(@RequestBody request: TipRequest): TipResult =
        tipJarService.tip(request.message, request.amountWei)

    @GetMapping("/tips")
    fun tips(): List<TipEntry> = tipJarService.tips()

    @PostMapping("/withdraw")
    fun withdraw(): WithdrawResult = tipJarService.withdraw()

    @ExceptionHandler(IllegalArgumentException::class, ContractCallException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: Exception): ApiError {
        log.warn("Rejected request: {}", e.message)
        return ApiError(e.message ?: "invalid request")
    }

    // Reverts (e.g. NotOwner, EmptyTip) surface as TransactionException from web3j
    @ExceptionHandler(TransactionException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun reverted(e: TransactionException): ApiError {
        log.error(
            "Transaction reverted on-chain (tx={}): {}",
            e.transactionHash.orElse("unknown"),
            e.message,
        )
        return ApiError(e.message ?: "transaction reverted")
    }
}
