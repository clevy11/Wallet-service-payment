package com.example.walletservice.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.example.walletservice.events.EventEnvelope;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The producer's serialiser, checked against the object Boot actually built.
 *
 * <p>Separate from {@code OutboxRelayTests}, which mocks {@code KafkaTemplate} and so
 * cannot catch this: the relay's {@code send} is stubbed, while the serialisation
 * failure that matters happens inside the real producer factory underneath it.
 *
 * <p>The bug this exists for was real. wallet-service consumed from Kafka long before
 * it produced to it, and because only the {@code consumer} block was configured, Boot
 * built a producer with Boot's default {@code StringSerializer}. Every publish failed
 * at runtime with a {@code SerializationException} naming the envelope class, the
 * outbox row retried every two seconds forever, and nothing failed at boot.
 */
class KafkaProducerConfigTests extends PostgresIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private ObjectMapper objectMapper;

    private EventEnvelope envelope() {
        return new EventEnvelope(
                "6f1c2b4e-0000-4000-8000-000000000001",
                "wallet.transfer.completed",
                "Transfer",
                "6f1c2b4e-0000-4000-8000-000000000002",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                "6f1c2b4e-0000-4000-8000-000000000003",
                objectMapper.readTree("{\"amount\":\"30.0000\"}"));
    }

    /**
     * The assertion that failed in production, made cheap: hand the real serialiser a
     * real envelope. With a StringSerializer this throws; with the Jackson 3 pair it
     * returns bytes.
     */
    /**
     * Read from the producer properties rather than {@code getValueSerializer()}, which
     * is null here: Boot configures the serialiser as a class name in the producer's
     * config map, and Kafka instantiates it at send time.
     */
    /** Instantiated the way Kafka does it: by the configured class name, reflectively. */
    @SuppressWarnings("unchecked")
    private Serializer<Object> configuredValueSerializer() throws Exception {
        Class<?> configured = (Class<?>) kafka.getProducerFactory().getConfigurationProperties()
                .get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        return (Serializer<Object>) configured.getDeclaredConstructor().newInstance();
    }

    private Class<?> configuredValueSerializerClass() {
        return (Class<?>) kafka.getProducerFactory().getConfigurationProperties()
                .get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
    }

    @Test
    @DisplayName("the producer uses the Jackson 3 serialiser, not the default")
    void producerUsesJacksonThree() {
        // Boot's default is StringSerializer, which throws the moment it is handed an
        // EventEnvelope. Asserting the absence of that specific class is the point:
        // "some serialiser is configured" would have passed while the relay retried
        // forever.
        assertThat(configuredValueSerializerClass()).isEqualTo(JacksonJsonSerializer.class);
    }

    @Test
    @DisplayName("the configured serialiser can actually serialise an envelope")
    void producerSerialisesTheEnvelope() throws Exception {
        Serializer<Object> serialiser = configuredValueSerializer();

        assertThatCode(() -> serialiser.serialize("wallet.events", envelope()))
                .doesNotThrowAnyException();
    }

    /**
     * A {@code __TypeId__} header would put this service's class names on the wire and
     * force every consumer to have this class on its classpath -- the coupling the
     * duplicate envelope records exist to avoid.
     */
    @Test
    @DisplayName("no type header is sent, so consumers resolve their own envelope type")
    void noTypeHeaderIsConfigured() {
        // Read as a String: the value reaches the producer config map as the literal
        // text "false", not as a Boolean, so asserting on a boolean would compare a
        // String to a Boolean and fail for a reason that has nothing to do with types.
        Object addTypeHeaders = kafka.getProducerFactory().getConfigurationProperties()
                .get("spring.json.add.type.headers");

        assertThat(String.valueOf(addTypeHeaders)).isEqualTo("false");
    }

    /**
     * Money is not a double.
     *
     * <p>Deserialised into {@code Map<String, Object>} every JSON number becomes a
     * double, so {@code 30.0000} would arrive as {@code 30.0} and a large id could lose
     * precision. The relay reads the payload as a tree for the same reason.
     */
    @Test
    @DisplayName("amounts keep their scale on the wire")
    void amountsSurviveTheRoundTrip() throws Exception {
        Serializer<Object> serialiser = configuredValueSerializer();
        byte[] bytes = serialiser.serialize("wallet.events", envelope());
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"amount\":\"30.0000\"");
    }
}
