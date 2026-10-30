package com.tablewise.realtime;

import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STOMP publisher. Events go out only after the surrounding transaction commits, so a screen never
 * hears about an order that was rolled back. Payloads are small "something changed" events; clients re-fetch.
 */
@Component
public class Realtime {
    private final SimpMessagingTemplate template;

    Realtime(SimpMessagingTemplate template) { this.template = template; }

    public void orders(UUID outletId, String type, UUID orderId, int orderNo) {
        send("/topic/outlets/" + outletId + "/orders", Map.of("type", type, "orderId", orderId, "orderNo", orderNo));
    }

    public void online(UUID outletId, String type, UUID onlineOrderId) {
        send("/topic/outlets/" + outletId + "/online", Map.of("type", type, "onlineOrderId", onlineOrderId));
    }

    public void inventory(UUID outletId, UUID ingredientId, String name, String state) {
        send("/topic/outlets/" + outletId + "/inventory", Map.of("ingredientId", ingredientId, "name", name, "state", state));
    }

    /** Customer display state: idle, bill, paid or failed. */
    public void display(UUID counterId, Map<String, Object> state) {
        if (counterId != null) send("/topic/counters/" + counterId + "/display", state);
    }

    private void send(String destination, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { template.convertAndSend(destination, payload); }
            });
        } else {
            template.convertAndSend(destination, payload);
        }
    }
}
