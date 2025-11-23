/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::{
    collections::VecDeque,
    fs::File,
    io::{self, Read, Write},
    os::fd::{AsRawFd, FromRawFd},
    sync::{Arc, RwLock},
};

use mio::{Events, Interest, Poll, Token, unix::SourceFd};

use crate::{
    AndroidFileHelper, BlockLoggerCallback, VpnCallback,
    backend::{
        DnsBackend,
        doh3::{DoH3Backend, DoH3BackendError},
        standard::StandardDnsBackend,
    },
    cache::{DnsCache, SerializableDnsCache},
    database::RuleDatabase,
    packet::build_response_packet,
    proxy::DnsPacketProxy,
    validation::{NativeDnsServer, NativeDnsServerType},
};

/// Holds an event file descriptor and flag to meant to interrupt the VPN loop
///
/// Meant to be created on the Kotlin side and passed to the main Rust loop
#[derive(uniffi::Object)]
pub struct VpnController {
    event_fd: i32,
    stop_result: RwLock<Option<VpnResult>>,
}

#[uniffi::export]
impl VpnController {
    #[uniffi::constructor]
    fn new() -> Arc<Self> {
        Arc::new(VpnController {
            event_fd: unsafe {
                let result = libc::eventfd(0, 0);
                if result != -1 { result } else { panic!() }
            },
            stop_result: RwLock::new(None),
        })
    }

    /// Returns whether the VPN has been given a reason to stop. The main loop should stop if the result is [Some].
    /// If [None], it should be ignored.
    ///
    /// Once this function is called and the result is [Some], the result will be cleared and the next call will return [None].
    pub fn get_stop_result(&self) -> Option<VpnResult> {
        return match self.stop_result.write() {
            Ok(mut lock) => match *lock {
                Some(result) => {
                    // Additionally clear the eventfd
                    unsafe {
                        let mut eventfd_result = libc::eventfd_t::default();
                        libc::eventfd_read(self.event_fd, &mut eventfd_result);
                    };

                    let result_clone = result.clone();
                    *lock = None;
                    Some(result_clone)
                }
                None => None,
            },
            Err(error) => {
                error!(
                    "get_should_stop: Failed to get write lock for should_stop - {:?}",
                    error
                );
                None
            }
        };
    }

    /// Writes an int to the event file descriptor and sets the stop flag so we can interrupt epoll and stop the VPN
    fn stop(&self, result: VpnResult) {
        if result == VpnResult::Continuing {
            error!("stop: Cannot stop with VpnResult::Continuing");
            return;
        }

        info!("VpnController::stop");
        match self.stop_result.write() {
            Ok(mut lock) => {
                if lock.is_none() {
                    unsafe { libc::eventfd_write(self.event_fd, 1) };
                    *lock = Some(result);
                } else {
                    warn!("stop: stop_result is already set!");
                }
            }
            Err(error) => {
                error!(
                    "stop: Failed to get write lock for should_stop. This should never happen. - {:?}",
                    error
                );
            }
        }
    }

    pub fn get_event_fd(&self) -> i32 {
        self.event_fd
    }
}

impl Drop for VpnController {
    fn drop(&mut self) {
        unsafe { libc::close(self.event_fd) };
    }
}

/// Represents the current status of the VPN (Mirrors the version in Kotlin)
#[allow(dead_code)]
pub enum VpnStatus {
    Stopped = 0,
    Starting = 1,
    Stopping = 2,
    WaitingForNetwork = 3,
    Reconnecting = 4,
    Running = 5,
}

/// Represents the possible results that can occur in the VPN and that will be passed back to Kotlin
#[derive(uniffi::Enum, PartialEq, PartialOrd, Debug, Clone, Copy)]
pub enum VpnResult {
    // Loop should continue
    Continuing,

    // Loop should stop
    Stopping,

    // Loop should stop, the VPN should be reconfigured, and then the loop should start again
    Reconnecting,
}

/// Represents the possible errors that can occur in the VPN and that will be passed back to Kotlin
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
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
}

#[derive(uniffi::Enum)]
pub enum VpnConfigurationResult {
    // The device is not connected to any networks and should wait before establishing the VPN
    NoNetwork,

    // The Android VpnService builder returned a null file descriptor and we should restart
    BuilderFailure,

    // At least one of the user's DNS servers were invalid
    InvalidDnsServers,

    // VPN controller interrupted configuration
    Interrupted(VpnResult),

    // The VpnService was established correctly with a valid file descriptor
    Success(i32, Vec<Arc<NativeDnsServer>>),
}

/// Main struct that holds the state of the VPN and runs the main loop
pub struct Vpn {
    vpn_controller: Arc<VpnController>,
    device_writes: VecDeque<Vec<u8>>,
}

impl Vpn {
    const VPN_TOKEN: Token = Token(usize::MAX);
    const VPN_CONTROLLER_TOKEN: Token = Token(usize::MAX - 1);

    pub fn new(vpn_controller: Arc<VpnController>) -> Self {
        Vpn {
            vpn_controller,
            device_writes: VecDeque::new(),
        }
    }

    /// Main loop for the VPN and tells the Kotlin side that we're running
    ///
    /// The general flow is as follows:
    ///
    /// 1. Poll the VPN file descriptor and the controller's event file descriptor
    ///
    /// 2. On an event, read a packet from the tunnel, translate its destination, create a socket to the real DNS server, and forward the packet
    ///
    /// 3. Poll the DNS sockets and once we get a response, translate the destination and send it back to the tunnel
    ///
    /// 4. The controller's event file descriptor may be updated during a loop iteration which will unblock the poller and then we'll return from the loop.
    /// Alternatively, we may run into a problem during the loop where we'll return a [VpnError] which will appear as an exception in Kotlin.
    pub fn run(
        &mut self,
        android_vpn_callback: Box<dyn VpnCallback>,
        block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
        rule_database: Arc<RuleDatabase>,
        android_file_helper: Box<dyn AndroidFileHelper>,
    ) -> Result<VpnResult, VpnError> {
        let mut packet = vec![0u8; i16::MAX as usize];

        let mut dns_cache_file = match android_file_helper.get_dns_cache_file_fd() {
            Some(cache_fd) => {
                // SAFETY: The descriptor is guaranteed to be valid by Android and detached from the Kotlin side
                Some(unsafe { File::from_raw_fd(cache_fd) })
            }
            None => {
                error!("run: Failed to get DNS cache file fd!");
                None
            }
        };

        let dns_cache = match dns_cache_file {
            Some(ref mut dns_cache_file) => {
                let serializable_cache = SerializableDnsCache::from(dns_cache_file);
                Arc::new(DnsCache::from(serializable_cache))
            }
            None => Arc::new(DnsCache::new()),
        };

        let (vpn_fd, dns_servers) =
            match android_vpn_callback.configure(self.vpn_controller.clone(), dns_cache.clone()) {
                VpnConfigurationResult::NoNetwork => {
                    error!("run: No network available");
                    return Result::Err(VpnError::NoNetwork);
                }
                VpnConfigurationResult::BuilderFailure => {
                    error!("run: Failed to configure VPN");
                    return Result::Err(VpnError::ConfigurationFailure);
                }
                VpnConfigurationResult::InvalidDnsServers => {
                    error!("run: No valid DNS servers found");
                    return Result::Err(VpnError::InvalidDnsServers);
                }
                VpnConfigurationResult::Interrupted(result) => {
                    debug!("run: Interrupted");
                    return Result::Ok(result);
                }
                VpnConfigurationResult::Success(fd, servers) => (fd, servers),
            };

        let is_doh3 = dns_servers.iter().any(|server| match server.get_type() {
            NativeDnsServerType::DoH3(_) => true,
            NativeDnsServerType::Standard => false,
        });
        let mut backend: Box<dyn DnsBackend> = if is_doh3 {
            match DoH3Backend::new(&dns_servers) {
                Ok(backend) => {
                    info!("run: Starting DoH3 backend");
                    Box::new(backend)
                }
                Err(error) => match error {
                    DoH3BackendError::ConfigurationFailure => {
                        return Result::Err(VpnError::ConfigurationFailure);
                    }
                },
            }
        } else {
            info!("run: Starting standard backend");
            Box::new(StandardDnsBackend::new())
        };

        // SAFETY: The descriptor is guaranteed to be valid by Android and detached from the Kotlin side
        let mut vpn_file = unsafe { File::from_raw_fd(vpn_fd) };

        let mut dns_packet_proxy = DnsPacketProxy::new(
            &android_vpn_callback,
            block_logger_callback,
            rule_database,
            dns_servers
                .iter()
                .filter_map(|server| {
                    if is_doh3 {
                        match &server.get_type() {
                            NativeDnsServerType::DoH3(server_name) => {
                                Some(server_name.clone().into_bytes())
                            }
                            NativeDnsServerType::Standard => None,
                        }
                    } else {
                        Some(server.get_address())
                    }
                })
                .collect(),
        );

        let mut poll = match Poll::new() {
            Ok(value) => value,
            Err(error) => {
                error!("do_one: Failed to create poller! - {:?}", error);
                return Result::Err(VpnError::TunnelPollRegistrationFailure);
            }
        };
        if let Err(error) = poll.registry().register(
            &mut SourceFd(&self.vpn_controller.event_fd),
            Self::VPN_CONTROLLER_TOKEN,
            Interest::READABLE,
        ) {
            error!("run: Failed to register signal descriptor! - {:?}", error);
            return Result::Err(VpnError::TunnelPollRegistrationFailure);
        }
        let mut events = Events::with_capacity(backend.get_max_events_count() + 2);

        android_vpn_callback.update_status(VpnStatus::Running as i32);
        loop {
            match self.do_one(
                &mut poll,
                &mut events,
                &mut vpn_file,
                &mut backend,
                &mut dns_packet_proxy,
                dns_cache.clone(),
                packet.as_mut_slice(),
            ) {
                Ok(result) => match result {
                    VpnResult::Continuing => continue,
                    _ => {
                        if let Some(ref mut dns_cache_file) = dns_cache_file {
                            dns_cache.to_disk_cache().write_to(dns_cache_file);
                        }
                        return Ok(result);
                    }
                },
                Err(error) => {
                    return Result::Err(error);
                }
            };
        }
    }

    /// One iteration of the main loop that polls the VPN, DNS sockets, and the controller's event file descriptor
    fn do_one(
        &mut self,
        poll: &mut Poll,
        events: &mut Events,
        vpn_file: &mut File,
        backend: &mut Box<dyn DnsBackend>,
        dns_packet_proxy: &mut DnsPacketProxy,
        dns_cache: Arc<DnsCache>,
        packet: &mut [u8],
    ) -> Result<VpnResult, VpnError> {
        if let Err(error) = poll.registry().register(
            &mut SourceFd(&vpn_file.as_raw_fd()),
            Self::VPN_TOKEN,
            if !self.device_writes.is_empty() {
                Interest::READABLE | Interest::WRITABLE
            } else {
                Interest::READABLE
            },
        ) {
            error!(
                "do_one: Failed to add VPN descriptor to poller! - {:?}",
                error
            );
            return Result::Err(VpnError::TunnelPollRegistrationFailure);
        }

        let backend_sources = backend.register_sources(poll);
        let timeout = backend.get_poll_timeout();
        debug!(
            "do_one: Polling {} sources(s) with timeout {:?}",
            backend_sources + 2,
            timeout
        );
        if let Err(error) = poll.poll(events, backend.get_poll_timeout()) {
            if error.kind() != io::ErrorKind::Interrupted {
                error!("do_one: Got error when polling sockets! - {:?}", error);
                return Result::Err(VpnError::PollFailure);
            }
        }

        if let Some(result) = self.vpn_controller.get_stop_result() {
            info!("do_one: Told to stop");
            return Ok(result);
        }

        let mut read_from_device = false;
        let mut write_to_device = false;
        let mut events_to_process = Vec::<&mio::event::Event>::new();
        for event in events.iter() {
            debug!("do_one: Got event {:?}", event);
            if event.token() == Self::VPN_TOKEN {
                read_from_device = read_from_device || event.is_readable();
                write_to_device = write_to_device || event.is_writable();
            } else if event.token() == Self::VPN_CONTROLLER_TOKEN {
                break;
            } else {
                events_to_process.push(event);
            }
        }

        match backend.process_events(self, dns_cache.clone(), events_to_process) {
            Ok(mut sources_to_remove) => {
                for source in sources_to_remove.iter_mut() {
                    if let Err(error) = poll.registry().deregister(source) {
                        warn!("do_one: Failed to remove socket from poller! - {:?}", error);
                    }
                }
            }
            Err(error) => {
                error!("do_one: Failed to process DnsBackend event - {:?}", error);
                return Result::Err(VpnError::SourcePollRegistrationFailure);
            }
        }

        if write_to_device {
            self.write_to_device(vpn_file)?;
        }

        if read_from_device {
            self.read_packet_from_device(vpn_file, backend, dns_packet_proxy, dns_cache, packet)?;
        }

        if let Err(error) = poll
            .registry()
            .deregister(&mut SourceFd(&vpn_file.as_raw_fd()))
        {
            error!("do_one: Failed to remove VPN FD from poller! - {:?}", error);
            return Result::Err(VpnError::TunnelPollRegistrationFailure);
        }

        return Result::Ok(VpnResult::Continuing);
    }

    /// Writes a packet to the tunnel from the device_writes queue
    fn write_to_device(&mut self, vpn_file: &mut File) -> Result<(), VpnError> {
        let device_write = match self.device_writes.pop_front() {
            Some(value) => value,
            None => {
                error!("write_to_device: device_writes is empty! This should be impossible");
                return Result::Err(VpnError::TunnelWriteFailure);
            }
        };

        match vpn_file.write(&device_write) {
            Ok(_) => Result::Ok(()),
            Err(error) => {
                error!("write_to_device: Failed writing - {:?}", error);
                Result::Err(VpnError::TunnelWriteFailure)
            }
        }
    }

    /// Reads a packet from the tunnel and then handles a DNS request if there is one
    fn read_packet_from_device(
        &mut self,
        vpn_file: &mut File,
        backend: &mut Box<dyn DnsBackend>,
        dns_packet_proxy: &mut DnsPacketProxy,
        dns_cache: Arc<DnsCache>,
        packet: &mut [u8],
    ) -> Result<(), VpnError> {
        let length = match vpn_file.read(packet) {
            Ok(value) => value,
            Err(error) => {
                error!(
                    "read_packet_from_device: Cannot read from device - {:?}",
                    error
                );
                return Result::Err(VpnError::TunnelReadFailure);
            }
        };

        if length == 0 {
            warn!("read_packet_from_device: Got empty packet!");
            return Result::Ok(());
        }

        dns_packet_proxy.handle_dns_request(self, backend, dns_cache, &packet[..length])?;

        return Result::Ok(());
    }

    /// Handles a DNS response and forwards it to the tunnel with the translated destination
    pub fn handle_dns_response(
        &mut self,
        dns_cache: Option<Arc<DnsCache>>,
        request_packet: &[u8],
        response_payload: &[u8],
    ) {
        match build_response_packet(request_packet, response_payload) {
            Some(packet) => {
                if let Some(dns_cache) = dns_cache {
                    dns_cache.put_packet(response_payload);
                }
                self.device_writes.push_back(packet)
            }
            None => return,
        };
    }
}
