package com.tablewise.payments;

import com.tablewise.security.Principals.StaffPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class PaymentController {
    private final PaymentService payments;

    PaymentController(PaymentService payments) { this.payments = payments; }

    @PostMapping("/orders/{id}/payments") @PreAuthorize("hasAnyRole('OWNER','CASHIER')") @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> start(@PathVariable UUID id, @RequestBody PaymentService.Start req, @AuthenticationPrincipal StaffPrincipal me) { return payments.start(id, me, req); }

    @GetMapping("/orders/{id}/payments") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    Map<String, Object> list(@PathVariable UUID id, @RequestParam(required = false) UUID counterId, @AuthenticationPrincipal StaffPrincipal me) { return payments.forOrder(id, counterId, me); }

    @PostMapping("/payments/{id}/cancel") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) { payments.cancel(id, me); }

    /** Public: authenticated by the Razorpay signature, not a JWT. */
    @PostMapping("/webhooks/razorpay")
    void razorpay(@RequestBody String raw, @RequestHeader(value = "X-Razorpay-Signature", required = false) String sig,
                  @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        payments.razorpayWebhook(raw, sig, eventId);
    }

    /** shortcut: plain @Scheduled, one instance only (README 12.3); add ShedLock before scaling out. */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    void sweep() { payments.sweep(); }
}
