package com.tablewise.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.tablewise.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The mock platform: a canonical webhook JSON signed with PLATFORM_WEBHOOK_SECRET (HMAC-SHA256 in X-Signature);
 * outbound calls are only logged. Replace with real Swiggy / Zomato / middleware adapters once partner access exists.
 */
@Component
public class LoggingPlatformAdapter implements PlatformAdapter {
    private static final Logger log = LoggerFactory.getLogger(LoggingPlatformAdapter.class);

    /** Outbound calls, newest last; tests read it. */
    public final List<String> calls = new CopyOnWriteArrayList<>();
    private final String secret;

    LoggingPlatformAdapter(@Value("${tablewise.platform-webhook-secret}") String secret) { this.secret = secret; }

    @Override
    public boolean verify(String rawBody, String signature) {
        if (secret.isBlank() || signature == null) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Event parse(JsonNode b) {
        if (!b.hasNonNull("eventId") || !b.hasNonNull("orderId") || !b.hasNonNull("storeId")) throw ApiException.badRequest("Missing eventId, storeId or orderId");
        var items = new ArrayList<Item>();
        for (JsonNode i : b.path("items")) {
            items.add(new Item(i.path("itemId").asText(), i.path("name").asText(), i.path("quantity").asInt(1), new BigDecimal(i.path("price").asText("0"))));
        }
        return new Event(b.get("eventId").asText(), b.path("type").asText("order.created"), b.get("storeId").asText(), b.get("orderId").asText(), items, b.path("instructions").asText(null));
    }

    @Override public void accept(String platform, String id, int prep) { record("accept " + platform + " " + id + " " + prep); }
    @Override public void reject(String platform, String id, String reason) { record("reject " + platform + " " + id + " " + reason); }
    @Override public void setStoreOpen(String platform, boolean open) { record("store " + platform + " " + (open ? "on" : "off")); }

    private void record(String call) {
        calls.add(call);
        log.info("platform call: {}", call);
    }
}
