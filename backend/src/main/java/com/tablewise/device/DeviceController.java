package com.tablewise.device;

import com.tablewise.common.Db;
import com.tablewise.security.Principals.StaffPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class DeviceController {
    record NewCode(@NotNull UUID deviceKey) {}
    record Pair(@NotBlank String code, @NotNull UUID counterId, @NotBlank String name) {}

    private final DeviceService devices;
    private final Db db;

    DeviceController(DeviceService devices, Db db) {
        this.devices = devices;
        this.db = db;
    }

    // The two pairing endpoints are public: the tablet has no account yet.
    @PostMapping("/devices/pairing-codes") @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> code(@Valid @RequestBody NewCode r) { return devices.issueCode(r.deviceKey()); }

    @GetMapping("/devices/pairing-codes/{deviceKey}")
    Map<String, Object> poll(@PathVariable UUID deviceKey) { return devices.poll(deviceKey); }

    @PostMapping("/devices/pair") @PreAuthorize("hasRole('OWNER')")
    Map<String, Object> pair(@Valid @RequestBody Pair r, @AuthenticationPrincipal StaffPrincipal me) { return devices.pair(r.code(), r.counterId(), r.name(), me); }

    /** Admin > Customer displays. Online means seen in the last 15 seconds. */
    @GetMapping("/devices") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    List<Map<String, Object>> list(@AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("""
                select d.id, d.name, d.device_type, d.counter_id, c.name as counter, d.paired_at, d.last_seen_at,
                       coalesce(d.last_seen_at > now() - interval '15 seconds', false) as online
                  from pos.devices d left join pos.counters c on c.id = d.counter_id
                 where d.outlet_id = ? and d.revoked_at is null order by d.paired_at""", me.outletId());
    }

    @DeleteMapping("/devices/{id}") @PreAuthorize("hasRole('OWNER')")
    void revoke(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) { devices.revoke(id, me); }
}
