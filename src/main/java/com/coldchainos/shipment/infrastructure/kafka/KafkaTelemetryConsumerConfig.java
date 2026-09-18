package com.coldchainos.shipment.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Spring Kafka consumer configuration for high-throughput IoT telemetry streaming.
 * Configures concurrency matching topic partitions and installs a Dead Letter Queue (DLQ)
 * error handler with non-retryable exception routing.
 */
@Slf4j
@Configuration
public class KafkaTelemetryConsumerConfig {

    @Value("${coldchainos.telemetry.raw-topic:coldchain.telemetry.raw}")
    private String rawTopic;

    @Value("${coldchainos.telemetry.dlq-topic:coldchain.telemetry.dlq}")
    private String dlqTopic;

    @Bean
    public NewTopic rawTelemetryTopic() {
        return TopicBuilder.name(rawTopic)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    public NewTopic dlqTelemetryTopic() {
        return TopicBuilder.name(dlqTopic)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> telemetryKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3); // 3 listener threads matching topic partitions

        // DLQ recoverer: forwards exhausted/poison messages to DLQ topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
            (record, ex) -> {
                log.error("Routing poison message from topic '{}' partition {} offset {} to DLQ '{}'. Reason: {}",
                    record.topic(), record.partition(), record.offset(), dlqTopic, ex.getMessage());
                return new TopicPartition(dlqTopic, record.partition());
            });

        // 2 retries with 300ms backoff before routing to DLQ
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(300L, 2L));

        // Immediately bypass retry for unrecoverable poison pills (malformed JSON / invalid schemas)
        errorHandler.addNotRetryableExceptions(
            JsonParseException.class,
            JsonMappingException.class,
            IllegalArgumentException.class
        );

        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        return factory;
    }
}
