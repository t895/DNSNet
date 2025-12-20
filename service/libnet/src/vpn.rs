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
