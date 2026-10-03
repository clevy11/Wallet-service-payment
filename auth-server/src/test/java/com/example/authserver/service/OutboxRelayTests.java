package com.example.authserver.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.authserver.repository.OutboxEventRepository;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real broker, because the property under test is a cross-system
 * one: the row must be marked published only once Kafka has taken responsibility for
 * the message. A mocked Kafka proves the code calls the API; only a broker proves the
 * ordering survives a real send.
 *
 * <p>Read back as a raw {@link String} rather than into {@code EventEnvelope},
 * because that is what is actually on the wire. Deserialising into the record would
 * hide two things worth seeing: that no Java class name leaks into the message, and
 * that the payload arrives as plain JSON.
 *
 * <p>Each test gets its own topic and its own relay instance rather than sharing the
 * configured one. A shared topic would accumulate records across tests, and a fresh
 * consumer reading from the earliest offset would then see earlier tests' messages
 * and count them as its own.
 */
@EmbeddedKafka
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        // Park the scheduled relay so it cannot race the assertions. These tests call
        // publishPending() themselves; a relay firing every 2s would publish rows
        // mid-assertion and make failures intermittent.
        "app.outbox.fixed-delay-ms=600000",
        "app.outbox.initial-delay-ms=600000"
})
class OutboxRelayTests extends com.example.authserver.support.PostgresIntegrationTest {

    @Autowired
    private UserRegistrationService registrations;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Broker broker;

    private String topic;
    private OutboxRelay relay;
    private Consumer<String, String> consumer;

    @BeforeEach
    void startWithAFreshTopic() throws Exception {
        topic = broker.createTopic("auth.events.test." + UUID.randomUUID());
        relay = new OutboxRelay(outbox, new OutboxMarker(outbox), kafka, objectMapper, topic, 50);
        consumer = broker.newConsumer();
        consumer.subscribe(List.of(topic));
    }

    @AfterEach
    void stopConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("an unpublished row reaches the topic and is then marked published")
    void publishesAndMarksPublished() {
        UUID correlationId = UUID.randomUUID();
        UserRegistrationService.RegistrationResult result =
                registrations.register("ada", "ada@example.com", "pw", correlationId);

        relay.publishPending();

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(20));
        JsonNode envelope = objectMapper.readTree(record.value());

        assertThat(envelope.get("eventId").asText()).isEqualTo(result.outboxEventId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("UserCreated");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("User");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(result.userId().toString());
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(envelope.get("payload").get("username").asText()).isEqualTo("ada");

        // Keyed by aggregateId: this is what pins every event for one aggregate to one
        // partition, so events about the same aggregate stay ordered.
        assertThat(record.key()).isEqualTo(result.userId().toString());

        // And only now, with the broker's acknowledgement in hand.
        assertThat(outbox.findById(result.outboxEventId()).orElseThrow().getPublishedAt())
                .isNotNull();
    }

    /**
     * The relay must not re-publish what it has already published. Without this, every
     * tick would resend the entire history and the consumer's dedup table would be
     * carrying the whole load forever.
     */
    @Test
    @DisplayName("an already published row is not sent again")
    void doesNotRepublishPublishedRows() {
        UserRegistrationService.RegistrationResult result =
                registrations.register("grace", "grace@example.com", "pw", UUID.randomUUID());

        relay.publishPending();
        KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(20));

        relay.publishPending();

        assertThat(consumer.poll(Duration.ofSeconds(3))).isEmpty();
        assertThat(outbox.findById(result.outboxEventId()).orElseThrow().getPublishedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("a whole backlog is drained in one pass")
    void publishesTheWholeBacklog() {
        registrations.register("ada", "ada@example.com", "pw", UUID.randomUUID());
        registrations.register("grace", "grace@example.com", "pw", UUID.randomUUID());
        registrations.register("alan", "alan@example.com", "pw", UUID.randomUUID());

        relay.publishPending();

        assertThat(pollUntil(3)).hasSize(3);
        assertThat(outbox.findAll()).allSatisfy(row -> assertThat(row.getPublishedAt()).isNotNull());
    }

    private List<ConsumerRecord<String, String>> pollUntil(int expected) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (collected.size() < expected && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            records.forEach(collected::add);
        }
        return collected;
    }

    /**
     * Topic and consumer plumbing, kept out of the tests themselves. The consumer is
     * built by hand rather than injected so the wire bytes are read with none of the
     * producer's own configuration applied to them.
     */
    @TestConfiguration
    static class BrokerConfiguration {

        @Bean
        Broker broker(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
            return new Broker(bootstrapServers);
        }
    }

    record Broker(String bootstrapServers) {

        String createTopic(String name) throws Exception {
            try (Admin admin = Admin.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
                try {
                    admin.createTopics(List.of(new NewTopic(name, 1, (short) 1)))
                            .all().get();
                } catch (Exception e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
            return name;
        }

        Consumer<String, String> newConsumer() {
            Map<String, Object> props = new java.util.HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            // A fresh group per test, so the consumer starts from the topic's earliest
            // offset and sees only what this test produced.
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            return new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        }
    }
}