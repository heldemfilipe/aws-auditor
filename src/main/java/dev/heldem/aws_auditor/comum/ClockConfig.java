package dev.heldem.aws_auditor.comum;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** O relógio é injetado (e não Instant.now() solto) para os testes controlarem o tempo, inclusive o "exatamente N dias". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
