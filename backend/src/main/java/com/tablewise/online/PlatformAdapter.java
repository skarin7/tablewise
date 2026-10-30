package com.tablewise.online;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * One seam for Swiggy, Zomato and the website, whether they call us directly or through an integration middleware (README 7.5).
 * Inbound: verify the signature and translate the webhook. Outbound: tell the platform what the outlet decided.
 */
public interface PlatformAdapter {
    record Item(String externalItemId, String name, int quantity, BigDecimal price) {}

    /** type is "order.created" or "order.cancelled". */
    record Event(String eventId, String type, String storeId, String externalOrderId, List<Item> items, String instructions) {}

    boolean verify(String rawBody, String signature);

    Event parse(JsonNode body);

    void accept(String platform, String externalOrderId, int prepMinutes);

    void reject(String platform, String externalOrderId, String reason);

    void setStoreOpen(String platform, boolean open);
}
