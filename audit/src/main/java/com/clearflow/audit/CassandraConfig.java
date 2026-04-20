package com.clearflow.audit;

import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import org.springframework.boot.autoconfigure.cassandra.CqlSessionBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CassandraConfig {

    @Bean
    public CqlSessionBuilderCustomizer localDatacenterCustomizer() {
        return (CqlSessionBuilder builder) -> builder.withLocalDatacenter("datacenter1");
    }
}
