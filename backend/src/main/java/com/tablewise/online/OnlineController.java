package com.tablewise.online;

import com.tablewise.security.Principals.StaffPrincipal;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class OnlineController {
    record Accept(@NotNull Integer prepMinutes) {}
    record Reject(String reason) {}
    record Pause(boolean accepting) {}
    record Setup(String externalStoreId, Short acceptWindowSeconds) {}
    record Mapping(@NotNull String externalItemId, @NotNull UUID menuItemId) {}

    private final OnlineService online;

    OnlineController(OnlineService online) { this.online = online; }

    @GetMapping("/online-orders") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    List<Map<String, Object>> list(@RequestParam(defaultValue = "incoming") String tab, @AuthenticationPrincipal StaffPrincipal me) { return online.list(tab, me); }

    @PostMapping("/online-orders/{id}/accept") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    Map<String, Object> accept(@PathVariable UUID id, @RequestBody Accept r, @AuthenticationPrincipal StaffPrincipal me) { return online.accept(id, r.prepMinutes(), me); }

    @PostMapping("/online-orders/{id}/reject") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    void reject(@PathVariable UUID id, @RequestBody Reject r, @AuthenticationPrincipal StaffPrincipal me) { online.reject(id, r.reason(), me); }

    @GetMapping("/platforms") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    List<Map<String, Object>> platforms(@AuthenticationPrincipal StaffPrincipal me) { return online.platforms(me); }

    @PatchMapping("/platforms/{platform}") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    void pause(@PathVariable String platform, @RequestBody Pause r, @AuthenticationPrincipal StaffPrincipal me) { online.setAccepting(platform, r.accepting(), me); }

    @PutMapping("/platforms/{platform}/setup") @PreAuthorize("hasRole('OWNER')")
    void setup(@PathVariable String platform, @RequestBody Setup r, @AuthenticationPrincipal StaffPrincipal me) { online.setup(platform, r.externalStoreId(), r.acceptWindowSeconds(), me); }

    @PutMapping("/platforms/{platform}/mappings") @PreAuthorize("hasRole('OWNER')")
    void map(@PathVariable String platform, @RequestBody Mapping r, @AuthenticationPrincipal StaffPrincipal me) { online.map(platform, r.externalItemId(), r.menuItemId(), me); }

    /** Public: authenticated by the platform signature (X-Signature), not a JWT. */
    @PostMapping("/webhooks/{platform}")
    void webhook(@PathVariable String platform, @RequestBody String raw, @RequestHeader(value = "X-Signature", required = false) String signature) { online.intake(platform, raw, signature); }
}
