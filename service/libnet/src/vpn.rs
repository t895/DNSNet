/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/// Represents the possible results that can occur in the VPN
#[derive(PartialEq, PartialOrd, Debug, Clone, Copy)]
pub enum VpnResult {
    // Loop should continue
    Continuing,

    // Loop should stop
    Stopping,

    // Loop should stop, the VPN should be reconfigured, and then the loop should start again
    Reconnecting,
}

/// Represents the possible errors that can occur in the VPN and that will be passed back to Kotlin
#[derive(Debug, thiserror::Error)]
pub enum VpnError {
    #[error("Failed to set up polling for the tunnel file descriptor")]
    TunnelPollRegistrationFailure,

    #[error("Failed to set up polling for a source")]
    SourcePollRegistrationFailure,

    #[error("Failed to write to the tunnel file descriptor")]
    TunnelWriteFailure,

    #[error("Failed to read from the tunnel file descriptor")]
    TunnelReadFailure,

    #[error("Poll returned an error")]
    PollFailure,

    #[error("Not connected to a network")]
    NoNetwork,

    #[error("Failed to create the tunnel file descriptor")]
    ConfigurationFailure,

    #[error("All DNS servers provided were invalid")]
    InvalidDnsServers,

    #[error("Failed to send/receive data on a socket")]
    SocketFailure,

    #[error("Failed to get event fd from controller")]
    ControllerFailure,
}
