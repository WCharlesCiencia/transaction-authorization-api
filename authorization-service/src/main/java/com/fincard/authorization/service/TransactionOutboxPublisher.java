package com.fincard.authorization.service;

import com.fincard.authorization.event.TransactionEvent;
import com.fincard.authorization.repository.TransactionOutboxRepository;
import com.fincard.authorization.repository.TransactionOutboxRepository.PendingEvent;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TransactionOutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionOutboxPublisher.class);
    private static final int BATCH_SIZE = 5;
    private static final long ACK_TIMEOUT_SECONDS = 11;
    private static final long RETRY_DELAY_SECONDS = 30;

    private final TransactionOutboxRepository outbox;
    private final KafkaTemplate<String, TransactionEvent> kafka;
    private final JsonMapper jsonMapper;
    private final String topic;

	public TransactionOutboxPublisher(TransactionOutboxRepository outbox, KafkaTemplate<String, TransactionEvent> kafka,
			JsonMapper jsonMapper, @Value("${app.kafka.topics.transaction-events}") String topic) {
		this.outbox = outbox;
		this.kafka = kafka;
		this.jsonMapper = jsonMapper;
		this.topic = topic;
	}

    @Scheduled(fixedDelayString = "${app.kafka.outbox.poll-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        for (PendingEvent pending : outbox.lockPendingBatch(BATCH_SIZE)) {
            try {
                TransactionEvent event = jsonMapper.readValue(pending.payload(), TransactionEvent.class);
                kafka.send(topic, Long.toString(pending.transactionId()), event)
                        .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                outbox.recordFailure(pending.eventId(), Instant.now().plusSeconds(RETRY_DELAY_SECONDS));
                LOG.warn("Failed to publish outbox event {}; retry scheduled", pending.eventId(), failure);
                continue;
            }
            outbox.markPublished(pending.eventId());
        }
    }
}
