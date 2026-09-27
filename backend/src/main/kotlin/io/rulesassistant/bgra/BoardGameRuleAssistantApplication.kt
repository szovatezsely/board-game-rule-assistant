package io.rulesassistant.bgra

import io.rulesassistant.bgra.config.GeminiProperties
import io.rulesassistant.bgra.config.GroqProperties
import io.rulesassistant.bgra.config.IngestionProperties
import io.rulesassistant.bgra.config.TtsProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(
    GroqProperties::class, GeminiProperties::class, TtsProperties::class, IngestionProperties::class,
)
class BoardGameRuleAssistantApplication

fun main(args: Array<String>) {
    runApplication<BoardGameRuleAssistantApplication>(*args)
}
