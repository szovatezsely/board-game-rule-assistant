package io.rulesassistant.bgra.config

import io.rulesassistant.bgra.repository.GameRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.Executor

@Configuration
class AppConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Single-threaded on purpose. Groq's free tier caps requests per minute for
     * the whole organisation, so processing two rulebooks at once would only
     * make both of them collect 429s. Uploads queue instead.
     */
    @Bean("ingestionExecutor")
    fun ingestionExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 64
        setThreadNamePrefix("ingest-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    /**
     * Writes Groq summary notes for long rulebooks while their pages are still
     * being transcribed (only when Gemini is not configured). One thread is
     * enough: the per-minute token allowance, not concurrency, is the limit.
     */
    @Bean("summaryExecutor")
    fun summaryExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 256
        setThreadNamePrefix("summary-")
        setWaitForTasksToCompleteOnShutdown(false)
        initialize()
    }

    /**
     * Any game left mid-flight by a restart is marked failed at startup, so the
     * UI shows an actionable error with a retry button instead of a spinner that
     * never resolves. Transcripts survive, so the retry is cheap.
     */
    @Bean
    fun recoverInterruptedIngestions(games: GameRepository): ApplicationRunner = ApplicationRunner {
        val affected = games.failInterrupted()
        if (affected > 0) {
            log.warn("Marked {} interrupted ingestion(s) as failed after restart", affected)
        }
    }
}

/**
 * CORS for local development, where Vite serves the frontend on port 5173 and
 * talks to the backend on 8080. In Docker both sit behind the same nginx origin,
 * so this is a no-op there.
 */
@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("*")
    }
}
