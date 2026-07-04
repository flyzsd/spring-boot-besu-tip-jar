package io.shudong.tipjar

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TipJarApplication

fun main(args: Array<String>) {
    runApplication<TipJarApplication>(*args)
}
