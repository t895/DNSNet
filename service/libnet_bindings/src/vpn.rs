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
use net::{
    backend::{
        DnsBackend, DnsResponseHandler, DnsServer, SocketProtector,
        doh3::{DoH3Backend, DoH3BackendError},
        standard::StandardDnsBackend,
    },
    cache::SerializableDnsCache,
    controller::VpnController,
    database::{Filter, FilterState},
    log::BlockLogger,
    packet::build_response_packet,
    proxy::DnsPacketProxy,
    vpn::{VpnError, VpnResult},
};

use log::{debug, error, info, warn};

use crate::{
    BlockLoggerBinding, FileHelperBinding, VpnCallback,
    cache::DnsCacheBinding,
    database::{FilterBinding, FilterStateBinding, RuleDatabaseBinding},
    validation::{NativeDnsServer, NativeDnsServerContainer},
};

/// Holds an event file descriptor and flag to meant to interrupt the VPN loop
///
/// Meant to be created on the Kotlin side and passed to the main Rust loop
#[derive(uniffi::Object)]
pub struct VpnControllerBinding {
    controller: RwLock<VpnController>,
}

#[uniffi::export]
impl VpnControllerBinding {
    #[uniffi::constructor]
    fn new() -> Arc<Self> {
        let vpn_controller = VpnController::new().unwrap();
        Arc::new(VpnControllerBinding {
            controller: RwLock::new(vpn_controller),
        })
    }

    pub fn stop(&self, result: VpnResultBinding) {
        match self.controller.write() {
            Ok(mut controller) => {
                controller.stop(result.into());
            }
            Err(error) => {
                error!(
                    "stop: Failed to acquire write lock on controller! - {:?}",
                    error
                );
                return;
            }
        }
    }

    pub fn get_event_fd(&self) -> Option<i32> {
        match self.controller.read() {
            Ok(controller) => Some(controller.get_receiver_fd()),
            Err(error) => {
                error!(
                    "get_event_fd: Failed to acquire read lock on controller! - {:?}",
                    error
                );
                None
            }
        }
    }

    pub fn get_stop_result(&self) -> Option<VpnResultBinding> {
        match self.controller.write() {
            Ok(mut controller) => match controller.get_stop_result() {
                Some(result) => Some(result.into()),
                None => None,
            },
            Err(error) => {
                error!(
                    "get_stop_result: Failed to acquire write lock on controller! - {:?}",
                    error
                );
                return None;
            }
        }
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
pub enum VpnResultBinding {
    // Loop should continue
    Continuing,

    // Loop should stop
    Stopping,

    // Loop should stop, the VPN should be reconfigured, and then the loop should start again
    Reconnecting,
}

impl From<VpnResult> for VpnResultBinding {
    fn from(value: VpnResult) -> Self {
        match value {
            VpnResult::Continuing => VpnResultBinding::Continuing,
            VpnResult::Stopping => VpnResultBinding::Stopping,
            VpnResult::Reconnecting => VpnResultBinding::Reconnecting,
        }
    }
}

impl From<VpnResultBinding> for VpnResult {
    fn from(value: VpnResultBinding) -> Self {
        match value {
            VpnResultBinding::Continuing => VpnResult::Continuing,
            VpnResultBinding::Stopping => VpnResult::Stopping,
            VpnResultBinding::Reconnecting => VpnResult::Reconnecting,
        }
    }
}

/// Represents the possible errors that can occur in the VPN and that will be passed back to Kotlin
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum VpnErrorBinding {
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

impl From<VpnError> for VpnErrorBinding {
    fn from(value: VpnError) -> Self {
        match value {
            VpnError::TunnelPollRegistrationFailure => {
                VpnErrorBinding::TunnelPollRegistrationFailure
            }
            VpnError::SourcePollRegistrationFailure => {
                VpnErrorBinding::SourcePollRegistrationFailure
            }
            VpnError::TunnelWriteFailure => VpnErrorBinding::TunnelWriteFailure,
            VpnError::TunnelReadFailure => VpnErrorBinding::TunnelReadFailure,
            VpnError::PollFailure => VpnErrorBinding::PollFailure,
            VpnError::NoNetwork => VpnErrorBinding::NoNetwork,
            VpnError::ConfigurationFailure => VpnErrorBinding::ConfigurationFailure,
            VpnError::InvalidDnsServers => VpnErrorBinding::InvalidDnsServers,
            VpnError::SocketFailure => VpnErrorBinding::SocketFailure,
            VpnError::ControllerFailure => VpnErrorBinding::ControllerFailure,
        }
    }
}

impl From<VpnErrorBinding> for VpnError {
    fn from(value: VpnErrorBinding) -> Self {
        match value {
            VpnErrorBinding::TunnelPollRegistrationFailure => {
                VpnError::TunnelPollRegistrationFailure
            }
            VpnErrorBinding::SourcePollRegistrationFailure => {
                VpnError::SourcePollRegistrationFailure
            }
            VpnErrorBinding::TunnelWriteFailure => VpnError::TunnelWriteFailure,
            VpnErrorBinding::TunnelReadFailure => VpnError::TunnelReadFailure,
            VpnErrorBinding::PollFailure => VpnError::PollFailure,
            VpnErrorBinding::NoNetwork => VpnError::NoNetwork,
            VpnErrorBinding::ConfigurationFailure => VpnError::ConfigurationFailure,
            VpnErrorBinding::InvalidDnsServers => VpnError::InvalidDnsServers,
            VpnErrorBinding::SocketFailure => VpnError::SocketFailure,
            VpnErrorBinding::ControllerFailure => VpnError::ControllerFailure,
        }
    }
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
    Interrupted(VpnResultBinding),

    // The VpnService was established correctly with a valid file descriptor
    Success(i32, Vec<Arc<NativeDnsServerContainer>>),
}

/// Main struct that holds the state of the VPN and runs the main loop
pub struct Vpn {
    vpn_controller: Arc<VpnControllerBinding>,
    device_writes: VecDeque<Vec<u8>>,
}

impl Into<FilterBinding> for &Filter {
    fn into(self) -> FilterBinding {
        FilterBinding::new(self.title.clone(), self.data.clone(), self.state.into())
    }
}

impl Into<FilterStateBinding> for FilterState {
    fn into(self) -> FilterStateBinding {
        match self {
            FilterState::IGNORE => FilterStateBinding::IGNORE,
            FilterState::DENY => FilterStateBinding::DENY,
            FilterState::ALLOW => FilterStateBinding::ALLOW,
        }
    }
}

impl Vpn {
    const VPN_TOKEN: Token = Token(usize::MAX);
    const VPN_CONTROLLER_TOKEN: Token = Token(usize::MAX - 1);

    pub fn new(vpn_controller: Arc<VpnControllerBinding>) -> Self {
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
        vpn_callback: Box<dyn VpnCallback>,
        block_logger: Option<Box<dyn BlockLoggerBinding>>,
        rule_database: Arc<RuleDatabaseBinding>,
        file_helper: Box<dyn FileHelperBinding>,
        is_doh3: bool,
    ) -> Result<VpnResultBinding, VpnErrorBinding> {
        let mut packet = vec![0u8; i16::MAX as usize];

        let mut dns_cache_file = match file_helper.get_dns_cache_file_fd() {
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
                Arc::new(DnsCacheBinding::from(serializable_cache))
            }
            None => Arc::new(DnsCacheBinding::new()),
        };

        let (vpn_fd, dns_servers) =
            match vpn_callback.configure(self.vpn_controller.clone(), dns_cache.clone()) {
                VpnConfigurationResult::NoNetwork => {
                    error!("run: No network available");
                    return Result::Err(VpnErrorBinding::NoNetwork);
                }
                VpnConfigurationResult::BuilderFailure => {
                    error!("run: Failed to configure VPN");
                    return Result::Err(VpnErrorBinding::ConfigurationFailure);
                }
                VpnConfigurationResult::InvalidDnsServers => {
                    error!("run: No valid DNS servers found");
                    return Result::Err(VpnErrorBinding::InvalidDnsServers);
                }
                VpnConfigurationResult::Interrupted(result) => {
                    debug!("run: Interrupted");
                    return Result::Ok(result);
                }
                VpnConfigurationResult::Success(fd, servers) => (fd, servers),
            };

        let mut backend: Box<dyn DnsBackend> =
            if is_doh3 {
                match DoH3Backend::new(
                    &dns_servers
                        .iter()
                        .filter_map(|container| match &container.server {
                            NativeDnsServer::DoH3(address, host_name, path) => Some(
                                DnsServer::DoH3(address.clone(), host_name.clone(), path.clone()),
                            ),
                            NativeDnsServer::Standard(_) => None,
                        })
                        .collect(),
                ) {
                    Ok(backend) => {
                        info!("run: Starting DoH3 backend");
                        Box::new(backend)
                    }
                    Err(error) => match error {
                        DoH3BackendError::ConfigurationFailure => {
                            return Result::Err(VpnErrorBinding::ConfigurationFailure);
                        }
                    },
                }
            } else {
                info!("run: Starting standard backend");
                Box::new(StandardDnsBackend::new())
            };

        // SAFETY: The descriptor is guaranteed to be valid by Android and detached from the Kotlin side
        let mut vpn_file = unsafe { File::from_raw_fd(vpn_fd) };

        let socket_protector = Box::from(&vpn_callback as &dyn SocketProtector);
        let block_logger = match block_logger {
            Some(ref value) => Some(Box::from(value as &dyn BlockLogger)),
            None => None,
        };
        let mut dns_packet_proxy = DnsPacketProxy::new(
            &socket_protector,
            block_logger,
            rule_database,
            dns_servers
                .iter()
                .filter_map(|container| match &container.server {
                    NativeDnsServer::DoH3(_, host_name, _) => {
                        if is_doh3 {
                            Some(host_name.clone().into_bytes())
                        } else {
                            None
                        }
                    }
                    NativeDnsServer::Standard(address) => {
                        if is_doh3 {
                            None
                        } else {
                            Some(address.clone())
                        }
                    }
                })
                .collect(),
        );

        let mut poll = match Poll::new() {
            Ok(value) => value,
            Err(error) => {
                error!("do_one: Failed to create poller! - {:?}", error);
                return Result::Err(VpnErrorBinding::TunnelPollRegistrationFailure);
            }
        };

        let event_fd = match self.vpn_controller.get_event_fd() {
            Some(fd) => fd,
            None => {
                error!("run: Failed to get event fd from controller!");
                return Result::Err(VpnErrorBinding::ControllerFailure);
            }
        };

        if let Err(error) = poll.registry().register(
            &mut SourceFd(&event_fd),
            Self::VPN_CONTROLLER_TOKEN,
            Interest::READABLE,
        ) {
            error!("run: Failed to register signal descriptor! - {:?}", error);
            return Result::Err(VpnErrorBinding::TunnelPollRegistrationFailure);
        }
        let mut events = Events::with_capacity(backend.get_max_events_count() + 2);

        vpn_callback.update_status(VpnStatus::Running as i32);
        loop {
            match self.do_one(
                &mut poll,
                &mut events,
                &mut vpn_file,
                &mut backend,
                &mut dns_packet_proxy,
                // dns_cache.clone(),
                packet.as_mut_slice(),
            ) {
                Ok(result) => match result {
                    VpnResultBinding::Continuing => continue,
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
        // dns_cache: Arc<DnsCacheBinding>,
        packet: &mut [u8],
    ) -> Result<VpnResultBinding, VpnErrorBinding> {
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
            return Result::Err(VpnErrorBinding::TunnelPollRegistrationFailure);
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
                return Result::Err(VpnErrorBinding::PollFailure);
            }
        }

        if let Some(result) = self.vpn_controller.get_stop_result() {
            info!("do_one: Told to stop");
            return Ok(result.into());
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

        let mut response_handler = Box::from(self as &mut dyn DnsResponseHandler);
        match backend.process_events(&mut response_handler, events_to_process) {
            Ok(mut sources_to_remove) => {
                for source in sources_to_remove.iter_mut() {
                    if let Err(error) = poll.registry().deregister(source) {
                        warn!("do_one: Failed to remove socket from poller! - {:?}", error);
                    }
                }
            }
            Err(error) => {
                error!("do_one: Failed to process DnsBackend event - {:?}", error);
                return Result::Err(VpnErrorBinding::SourcePollRegistrationFailure);
            }
        }

        if write_to_device {
            self.write_to_device(vpn_file)?;
        }

        if read_from_device {
            self.read_packet_from_device(vpn_file, backend, dns_packet_proxy, packet)?;
        }

        if let Err(error) = poll
            .registry()
            .deregister(&mut SourceFd(&vpn_file.as_raw_fd()))
        {
            error!("do_one: Failed to remove VPN FD from poller! - {:?}", error);
            return Result::Err(VpnErrorBinding::TunnelPollRegistrationFailure);
        }

        return Result::Ok(VpnResultBinding::Continuing);
    }

    /// Writes a packet to the tunnel from the device_writes queue
    fn write_to_device(&mut self, vpn_file: &mut File) -> Result<(), VpnErrorBinding> {
        let device_write = match self.device_writes.pop_front() {
            Some(value) => value,
            None => {
                error!("write_to_device: device_writes is empty! This should be impossible");
                return Result::Err(VpnErrorBinding::TunnelWriteFailure);
            }
        };

        match vpn_file.write(&device_write) {
            Ok(_) => Result::Ok(()),
            Err(error) => {
                error!("write_to_device: Failed writing - {:?}", error);
                Result::Err(VpnErrorBinding::TunnelWriteFailure)
            }
        }
    }

    /// Reads a packet from the tunnel and then handles a DNS request if there is one
    fn read_packet_from_device(
        &mut self,
        vpn_file: &mut File,
        backend: &mut Box<dyn DnsBackend>,
        dns_packet_proxy: &mut DnsPacketProxy,
        // dns_cache: Arc<DnsCacheBinding>,
        packet: &mut [u8],
    ) -> Result<(), VpnErrorBinding> {
        let length = match vpn_file.read(packet) {
            Ok(value) => value,
            Err(error) => {
                error!(
                    "read_packet_from_device: Cannot read from device - {:?}",
                    error
                );
                return Result::Err(VpnErrorBinding::TunnelReadFailure);
            }
        };

        if length == 0 {
            warn!("read_packet_from_device: Got empty packet!");
            return Result::Ok(());
        }

        let mut response_handler = Box::from(self as &mut dyn DnsResponseHandler);
        dns_packet_proxy
            .handle_dns_request(&mut response_handler, backend, &packet[..length])
            .map_err(|error: VpnError| <VpnErrorBinding as From<VpnError>>::from(error))?;

        return Result::Ok(());
    }

    /// Handles a DNS response and forwards it to the tunnel with the translated destination
    pub fn handle_dns_response(
        &mut self,
        // dns_cache: Option<Arc<DnsCacheBinding>>,
        request_packet: &[u8],
        response_payload: &[u8],
    ) {
        match build_response_packet(request_packet, response_payload) {
            Some(packet) => {
                // if let Some(dns_cache) = dns_cache {
                //     dns_cache.put_packet(response_payload);
                // }
                self.device_writes.push_back(packet)
            }
            None => return,
        };
    }
}

impl DnsResponseHandler for Vpn {
    fn handle(&mut self, request_packet: &[u8], request_payload: &[u8]) {
        self.handle_dns_response(request_packet, request_payload);
    }
}
