package io.shudong.tipjar.web

import io.shudong.tipjar.service.TipEntry
import io.shudong.tipjar.service.TipJarInfo
import io.shudong.tipjar.service.TipJarService
import io.shudong.tipjar.service.TipResult
import io.shudong.tipjar.service.WithdrawResult
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
@RequestMapping("/api/tipjar/{address}")
class TipJarController(private val tipJarService: TipJarService) {

    @GetMapping
    fun info(@PathVariable address: String): TipJarInfo = tipJarService.info(address)

    @PostMapping("/tip")
    fun tip(@PathVariable address: String, @RequestBody request: TipRequest): TipResult =
        tipJarService.tip(address, request.message, request.amountWei)

    @GetMapping("/tips")
    fun tips(@PathVariable address: String): List<TipEntry> = tipJarService.tips(address)

    @PostMapping("/withdraw")
    fun withdraw(@PathVariable address: String): WithdrawResult = tipJarService.withdraw(address)

    @ExceptionHandler(IllegalArgumentException::class, ContractCallException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: Exception): ApiError = ApiError(e.message ?: "invalid request")

    // Reverts (e.g. NotOwner, EmptyTip) surface as TransactionException from web3j
    @ExceptionHandler(TransactionException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun reverted(e: Exception): ApiError = ApiError(e.message ?: "transaction reverted")
}
