package com.clockwise.colabService.config

import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer

@Configuration
@EnableR2dbcRepositories(basePackages = ["com.clockwise.colabService.repository"])
class R2DBCConfig {

    @Bean
    fun initializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
        val initializer = ConnectionFactoryInitializer()
        initializer.setConnectionFactory(connectionFactory)
        
        val populator = CompositeDatabasePopulator()
        initializer.setDatabasePopulator(populator)
        
        return initializer
    }
}