package com.tablewise.realtime;

import com.tablewise.security.Principals.DevicePrincipal;
import java.util.UUID;

/** What the socket layer needs from device pairing (implemented in the device package). */
public interface DeviceAuthenticator {
    /** Looks up the token hash, rejects revoked devices, marks the device seen and remembers its socket session. */
    DevicePrincipal authenticate(String rawToken, String sessionId);

    void heartbeat(UUID deviceId);
}
