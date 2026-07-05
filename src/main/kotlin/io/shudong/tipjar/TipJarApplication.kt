package io.shudong.tipjar

import io.shudong.tipjar.config.Web3Properties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(Web3Properties::class)
class TipJarApplication

fun main(args: Array<String>) {
    runApplication<TipJarApplication>(*args)
}
