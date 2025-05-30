/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

pub mod doh3;
pub mod standard;

use std::time::Duration;

use mio::{Poll, event::Source};

use crate::{Vpn, VpnCallback};

#[derive(Debug)]
pub enum DnsBackendError {
    SocketFailure,
    InvalidAddress,
    RandomGenerationFailure,
}

pub trait DnsBackend {
    /// Returns the max number of events that the events object will be initialized with.
    fn get_max_events_count(&self) -> usize;

    fn get_poll_timeout(&self) -> Option<Duration>;

    /// Register sources with the poller.
    /// Returns the number of sources that were registered.
    /// You MUST NOT register sources that have a token value of [usize::MAX] or [usize::MAX] - 1.
    fn register_sources(&mut self, poll: &mut Poll) -> usize;

    fn forward_packet(
        &mut self,
        android_vpn_service: &Box<dyn VpnCallback>,
        packet: &[u8],
        request_packet: &[u8],
        destination_address: Vec<u8>,
        destination_port: u16,
    ) -> Result<(), DnsBackendError>;

    /// Process all events from the poller and send any processed packets to the [DnsPacketProxy].
    /// Return a [Source] if it should be removed from the poller and [None] if it should be kept.
    fn process_events(
        &mut self,
        ad_vpn: &mut Vpn,
        events: Vec<&mio::event::Event>,
    ) -> Result<Vec<Box<dyn Source>>, DnsBackendError>;
}
