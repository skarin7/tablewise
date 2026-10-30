package com.tablewise.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.tablewise.common.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class RazorpayHttpClient implements RazorpayClient {
    private final RestClient http;
    private final boolean configured;

    RazorpayHttpClient(@Value("${tablewise.razorpay.base-url}") String baseUrl, @Value("${tablewise.razorpay.key-id}") String keyId,
                       @Value("${tablewise.razorpay.key-secret}") String secret) {
        this.configured = !keyId.isBlank() && !secret.isBlank();
        this.http = RestClient.builder().baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBasicAuth(keyId, secret)).build();
    }

    @Override public boolean configured() { return configured; }

    @Override
    public Qr createQr(UUID orderId, int orderNo, BigDecimal amount, Instant closeBy) {
        if (!configured) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPI QR is not set up. Add the Razorpay keys.");
        try {
            JsonNode r = http.post().uri("/v1/payments/qr_codes").body(Map.of(
                    "type", "upi_qr", "name", "Order #" + orderNo, "usage", "single_use", "fixed_amount", true,
                    "payment_amount", amount.movePointRight(2).longValueExact(), "description", "Order #" + orderNo,
                    "close_by", closeBy.getEpochSecond(), "notes", Map.of("order_id", orderId.toString()))).retrieve().body(JsonNode.class);
            return new Qr(r.get("id").asText(), r.get("image_content").asText(), closeBy);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not create the UPI QR. Try again or use another method.");
        }
    }

    @Override
    public void closeQr(String qrId) {
        if (configured) http.post().uri("/v1/payments/qr_codes/{id}/close", qrId).retrieve().toBodilessEntity();
    }

    @Override
    public List<Captured> capturedPayments(String qrId) {
        var out = new ArrayList<Captured>();
        JsonNode r = http.get().uri("/v1/payments/qr_codes/{id}/payments", qrId).retrieve().body(JsonNode.class);
        for (JsonNode p : r.path("items")) {
            if ("captured".equals(p.path("status").asText())) {
                out.add(new Captured(p.get("id").asText(), p.path("acquirer_data").path("rrn").asText(p.path("acquirer_data").path("upi_transaction_id").asText(null))));
            }
        }
        return out;
    }
}
