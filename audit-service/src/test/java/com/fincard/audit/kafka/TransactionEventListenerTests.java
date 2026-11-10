package com.fincard.audit.kafka;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fincard.audit.event.TransactionEvent;
import com.fincard.audit.service.TransactionAuditService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TransactionEventListenerTests {

    private final TransactionAuditService audit = mock(TransactionAuditService.class);
    private final TransactionEventListener listener = new TransactionEventListener(audit);

    @Test
    void delegatesValidEventToAuditService() {
        TransactionEvent event = approvedEvent(UUID.randomUUID());

        listener.onTransactionEvent(event);

        verify(audit).record(event);
    }

    @Test
    void rejectsEventWithoutIdBeforeCallingAuditService() {
        TransactionEvent event = approvedEvent(null);

        assertThrows(IllegalArgumentException.class, () -> listener.onTransactionEvent(event));

        verifyNoInteractions(audit);
    }

    @Test
    void propagatesPersistenceFailureToKafkaListenerContainer() {
        TransactionEvent event = approvedEvent(UUID.randomUUID());
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(audit.record(event)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> listener.onTransactionEvent(event)));
    }

    private static TransactionEvent approvedEvent(UUID eventId) {
        return new TransactionEvent(eventId, UUID.randomUUID(), 42L, "APPROVED",
                null, "TRANSACTION_APPROVED", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
