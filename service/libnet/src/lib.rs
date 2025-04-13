use std::{
    collections::{HashMap, VecDeque},
    fs::File,
    io::{self, BufRead, Read, Write},
    net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6},
    os::fd::{AsRawFd, FromRawFd},
    str::FromStr,
    sync::{Arc, RwLock, atomic::AtomicBool},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
    usize,
};

use android_logger::Config;
use etherparse::{
    IpHeaders, IpNumber, Ipv4Header, Ipv6FlowLabel, Ipv6Header, NetSlice, PacketBuilder,
    PacketBuilderStep, SlicedPacket, TransportSlice, UdpSlice, ip_number,
};
use log::LevelFilter;
use mio::{Events, Interest, Poll, Token, net::UdpSocket};
use mio::{event::Source, unix::SourceFd};
use simple_dns::{Name, PacketFlag, ResourceRecord, rdata::RData};

#[macro_use]
extern crate log;
extern crate android_logger;

uniffi::setup_scaffolding!();

/// Initializes the logger for the Rust side of the VPN
///
/// This should be called before any other Rust functions in the Kotlin code
#[uniffi::export]
pub fn rust_init(debug: bool) {
    android_logger::init_once(
        Config::default()
            .with_max_level(if debug {
                LevelFilter::Trace
            } else {
                LevelFilter::Info
            }) // limit log level
            .with_tag("DNSNet Native"), // logs will show under mytag tag
    );
}

/// Entrypoint for starting the VPN from Kotlin
///
/// Runs the main loop for the service based on the descriptor given
/// by the Android system.
#[uniffi::export]
pub fn run_vpn_native(
    ad_vpn_callback: Box<dyn AdVpnCallback>,
    block_logger_callback: Box<dyn BlockLoggerCallback>,
    vpn_controller: Arc<VpnController>,
    rule_database: Arc<RuleDatabase>,
) -> Result<VpnResult, VpnError> {
    let mut vpn = AdVpn::new(vpn_controller);
    let result = vpn.run(ad_vpn_callback, block_logger_callback, rule_database);
    info!("run_vpn_native: Stopped");
    return result;
}

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
    fn get_stop_result(&self) -> Option<VpnResult> {
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
            Err(e) => {
                error!(
                    "get_should_stop: Failed to get write lock for should_stop - {:?}",
                    e
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
            Err(e) => {
                error!(
                    "stop: Failed to get write lock for should_stop. This should never happen. - {:?}",
                    e
                );
            }
        }
    }
}

impl Drop for VpnController {
    fn drop(&mut self) {
        unsafe { libc::close(self.event_fd) };
    }
}

fn build_ipv4_packet_with_udp_payload(
    source_address: &[u8; 4],
    source_port: u16,
    destination_address: &[u8; 4],
    destination_port: u16,
    time_to_live: u8,
    identification: u16,
    udp_payload: &[u8],
) -> Option<Vec<u8>> {
    let mut header = match Ipv4Header::new(
        udp_payload.len() as u16,
        time_to_live,
        ip_number::UDP,
        *source_address,
        *destination_address,
    ) {
        Ok(value) => value,
        Err(e) => {
            error!("build_packet_v4: Failed to create Ipv4Header! - {:?}", e);
            return None;
        }
    };

    header.identification = identification;
    let builder = PacketBuilder::ip(IpHeaders::Ipv4(header, Default::default()));
    return build_ip_packet_with_udp_payload(builder, source_port, destination_port, udp_payload);
}

fn build_ipv6_packet_with_udp_payload(
    source_address: &[u8; 16],
    source_port: u16,
    destination_address: &[u8; 16],
    destination_port: u16,
    traffic_class: u8,
    flow_label: Ipv6FlowLabel,
    hop_limit: u8,
    udp_payload: &[u8],
) -> Option<Vec<u8>> {
    let header = Ipv6Header {
        traffic_class,
        flow_label,
        payload_length: udp_payload.len() as u16,
        next_header: IpNumber::UDP,
        hop_limit,
        source: *source_address,
        destination: *destination_address,
    };
    let builder = PacketBuilder::ip(IpHeaders::Ipv6(header, Default::default()));
    return build_ip_packet_with_udp_payload(builder, source_port, destination_port, udp_payload);
}

fn build_ip_packet_with_udp_payload(
    builder: PacketBuilderStep<IpHeaders>,
    source_port: u16,
    destination_port: u16,
    udp_payload: &[u8],
) -> Option<Vec<u8>> {
    let udp_builder = builder.udp(source_port, destination_port);
    let mut result = Vec::<u8>::with_capacity(udp_builder.size(udp_payload.len()));
    if let Err(e) = udp_builder.write(&mut result, &udp_payload) {
        error!("build_packet: Failed to build packet! - {:?}", e);
        return None;
    }
    return Some(result);
}

/// Basic abstraction over a packet that lets us get a slice of a IPv4 or IPv6 header or payload
/// without doing extra allocations
#[derive(Debug)]
struct GenericIpPacket<'a> {
    packet: SlicedPacket<'a>,
}

impl<'a> GenericIpPacket<'a> {
    /// Creates a new GenericIpPacket from a raw IP packet byte array
    fn from_ip_packet(data: &'a [u8]) -> Option<Self> {
        match SlicedPacket::from_ip(data) {
            Ok(value) => Some(GenericIpPacket::new(value)),
            Err(_) => None,
        }
    }

    fn new(packet: SlicedPacket<'a>) -> Self {
        Self { packet }
    }

    /// Gets a slice of the IPv4 header from the packet and returns None if the packet is not IPv4
    fn get_ipv4_header(&self) -> Option<Ipv4Header> {
        match &self.packet.net {
            Some(net) => match net {
                NetSlice::Ipv4(value) => Some(value.header().to_header()),
                _ => None,
            },
            None => None,
        }
    }

    /// Gets a slice of the IPv6 header from the packet and returns None if the packet is not IPv6
    fn get_ipv6_header(&self) -> Option<Ipv6Header> {
        match &self.packet.net {
            Some(net) => match net {
                NetSlice::Ipv6(value) => Some(value.header().to_header()),
                _ => None,
            },
            None => None,
        }
    }

    /// Gets a slice of the destination address from the packet header
    fn get_destination_address(&self) -> Option<Vec<u8>> {
        let ipv4_header = self.get_ipv4_header();
        if ipv4_header.is_some() {
            return Some(ipv4_header.unwrap().destination.to_vec());
        }

        let ipv6_header = self.get_ipv6_header();
        if ipv6_header.is_some() {
            return Some(ipv6_header.unwrap().destination.to_vec());
        }

        return None;
    }

    /// Gets a slice of the UDP payload from the packet
    pub fn get_udp_packet(&self) -> Option<&UdpSlice> {
        match &self.packet.transport {
            Some(transport) => match transport {
                TransportSlice::Udp(udp) => Some(udp),
                _ => None,
            },
            None => None,
        }
    }
}

/// Takes the header information from the request packet and builds a new packet using it and the response payload
fn build_response_packet(request_packet: &[u8], response_payload: &[u8]) -> Option<Vec<u8>> {
    let generic_request_packet = match GenericIpPacket::from_ip_packet(request_packet) {
        Some(value) => value,
        None => return None,
    };

    let request_payload = match generic_request_packet.get_udp_packet() {
        Some(value) => value,
        None => return None,
    };

    if let Some(header) = generic_request_packet.get_ipv4_header() {
        return build_ipv4_packet_with_udp_payload(
            &header.destination,
            request_payload.destination_port(),
            &header.source,
            request_payload.source_port(),
            header.time_to_live,
            header.identification,
            &response_payload,
        );
    }

    if let Some(header) = generic_request_packet.get_ipv6_header() {
        return build_ipv6_packet_with_udp_payload(
            &header.destination,
            request_payload.destination_port(),
            &header.source,
            request_payload.source_port(),
            header.traffic_class,
            header.flow_label,
            header.hop_limit,
            &response_payload,
        );
    }

    return None;
}

/// Convenience function to get the [Duration] since the Unix epoch
fn get_epoch() -> Duration {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap()
}

/// Convenience function to get the current time in milliseconds since the Unix epoch
fn get_epoch_millis() -> u128 {
    get_epoch().as_millis()
}

/// Convenience function to get the current time in nanoseconds since the Unix epoch
#[allow(dead_code)]
fn get_epoch_nanos() -> u128 {
    get_epoch().as_nanos()
}

/// Represents the current status of the VPN (Mirrors the version in Kotlin)
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
    TunnelPollFailure,

    #[error("Failed to set up polling for a source")]
    SourcePollFailure,

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
}

#[derive(uniffi::Enum)]
pub enum VpnConfigurationResult {
    // The device is not connected to any networks and should wait before establishing the VPN
    NoNetwork,

    // The Android VpnService builder returned a null file descriptor and we should restart
    BuilderFailure,

    // The VpnService was established correctly with a valid file descriptor and the upstream DNS servers
    Success(i32, Vec<String>),
}

/// Callback interface to be implemented by a Kotlin class and then passed into the main loop
#[uniffi::export(callback_interface)]
pub trait AdVpnCallback: Send + Sync {
    fn configure(&self) -> VpnConfigurationResult;

    fn protect_raw_socket_fd(&self, socket_fd: i32) -> bool;

    fn notify(&self, native_status: i32);

    fn send_doh3_configuration_error_notification(&self);
}

#[derive(Debug)]
enum DnsBackendError {
    ForwardFailure,
}

trait DnsBackend {
    /// Do any initialization needed before the tunnel is opened.
    /// Returns the max number of sources that will be registered with the poller.
    fn init(&self) -> usize;

    /// Register sources with the poller.
    /// Returns the number of sources that were registered.
    /// You MUST NOT register sources that have a token value of [usize::MAX] or [usize::MAX] - 1.
    fn register_sources(&mut self, poll: &mut Poll) -> usize;

    fn forward_packet(
        &mut self,
        android_vpn_service: &Box<dyn AdVpnCallback>,
        packet: &[u8],
        request_packet: &[u8],
        destination_address: SocketAddr,
    ) -> Result<(), DnsBackendError>;

    /// Process an event from the poller and send any processed packets to the [DnsPacketProxy].
    /// Return a [Source] if it should be removed from the poller and [None] if it should be kept.
    fn process_event(
        &mut self,
        ad_vpn: &mut AdVpn,
        event: &mio::event::Event,
    ) -> Result<Option<Box<dyn Source>>, DnsBackendError>;
}

struct StandardDnsBackend {
    wosp_list: WospList,
    response_packet: Vec<u8>,
    unspecified_bind_address: SocketAddr,
}

impl StandardDnsBackend {
    const DNS_RESPONSE_PACKET_SIZE: usize = 1024;

    fn new() -> Self {
        StandardDnsBackend {
            wosp_list: WospList::new(),
            response_packet: vec![0; Self::DNS_RESPONSE_PACKET_SIZE],
            unspecified_bind_address: SocketAddr::new(
                std::net::IpAddr::V6(Ipv6Addr::UNSPECIFIED),
                0,
            ),
        }
    }
}

impl DnsBackend for StandardDnsBackend {
    fn init(&self) -> usize {
        return WospList::DNS_MAXIMUM_WAITING;
    }

    fn register_sources(&mut self, poll: &mut Poll) -> usize {
        let mut waiting_sockets = 0;
        self.wosp_list.list.retain_mut(|wosp| {
            match poll.registry().register(
                &mut wosp.socket,
                Token(wosp.time as usize),
                Interest::READABLE,
            ) {
                Ok(_) => {
                    waiting_sockets += 1;
                    true
                }
                Err(e) => {
                    if e.kind() == std::io::ErrorKind::AlreadyExists {
                        waiting_sockets += 1;
                        true
                    } else {
                        warn!(
                            "register_sources: Failed to add socket {:?} to poller! - {:?}",
                            wosp, e
                        );
                        false
                    }
                }
            }
        });
        return waiting_sockets;
    }

    fn forward_packet(
        &mut self,
        android_vpn_service: &Box<dyn AdVpnCallback>,
        packet: &[u8],
        request_packet: &[u8],
        destination_address: SocketAddr,
    ) -> Result<(), DnsBackendError> {
        let socket = match UdpSocket::bind(self.unspecified_bind_address) {
            Ok(value) => value,
            Err(e) => {
                error!("forward_packet: Failed to create socket! - {:?}", e);
                return Err(DnsBackendError::ForwardFailure);
            }
        };

        // Packets to be sent to the real DNS server will need to be protected from the VPN
        if !android_vpn_service.protect_raw_socket_fd(socket.as_raw_fd()) {
            error!("forward_packet: Failed for protect socket fd!");
            return Err(DnsBackendError::ForwardFailure);
        }

        let destination_sockaddr = SocketAddr::from(destination_address);
        return match socket.send_to(packet, destination_sockaddr) {
            Ok(_) => {
                self.wosp_list
                    .add(WaitingOnSocketPacket::new(socket, request_packet.to_vec()));
                Ok(())
            }
            Err(e) => {
                error!("forward_packet: Failed to send packet! - {:?}", e);
                Err(DnsBackendError::ForwardFailure)
            }
        };
    }

    fn process_event(
        &mut self,
        ad_vpn: &mut AdVpn,
        event: &mio::event::Event,
    ) -> Result<Option<Box<dyn Source>>, DnsBackendError> {
        if let Some(index) = self
            .wosp_list
            .list
            .iter()
            .position(|value| (value.time as usize) == event.token().0)
        {
            if let Some(wosp) = self.wosp_list.list.remove(index) {
                debug!("process_event: Read from DNS socket: {:?}", wosp.socket);

                match wosp.socket.recv(&mut self.response_packet.as_mut_slice()) {
                    Ok(size) => {
                        ad_vpn.handle_dns_response(&wosp.packet, &mut self.response_packet[..size]);
                    }
                    Err(e) => {
                        warn!(
                            "process_event: Failed to receive response packet from DNS socket! - {:?}",
                            e
                        );
                        return Ok(None);
                    }
                };

                return Ok(Some(Box::new(wosp.socket)));
            }
        }
        return Ok(None);
    }
}

/// Main struct that holds the state of the VPN and runs the main loop
struct AdVpn {
    vpn_controller: Arc<VpnController>,
    device_writes: VecDeque<Vec<u8>>,
}

impl AdVpn {
    const VPN_TOKEN: Token = Token(usize::MAX);
    const VPN_CONTROLLER_TOKEN: Token = Token(usize::MAX - 1);

    fn new(vpn_controller: Arc<VpnController>) -> Self {
        AdVpn {
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
    /// 4. The controller's event file descriptor may close during a loop iteration which will unblock the poller and then we'll return from the loop.
    /// Alternatively, we may run into a problem during the loop where we'll return a [VpnError] which will appear as an exception in Kotlin.
    fn run(
        &mut self,
        android_vpn_callback: Box<dyn AdVpnCallback>,
        block_logger_callback: Box<dyn BlockLoggerCallback>,
        rule_database: Arc<RuleDatabase>,
    ) -> Result<VpnResult, VpnError> {
        let mut packet = vec![0u8; i16::MAX as usize];

        let mut backend: Box<dyn DnsBackend> = Box::new(StandardDnsBackend::new());
        let max_sources = backend.init();

        let (vpn_fd, upstream_dns_servers) = match android_vpn_callback.configure() {
            VpnConfigurationResult::NoNetwork => {
                error!("run: No network available");
                return Result::Err(VpnError::NoNetwork);
            }
            VpnConfigurationResult::BuilderFailure => {
                error!("run: Failed to configure VPN");
                return Result::Err(VpnError::ConfigurationFailure);
            }
            VpnConfigurationResult::Success(fd, servers) => (fd, servers),
        };

        // SAFETY: The descriptor is guaranteed to be valid by Android and detached from the Kotlin side
        let mut vpn_file = unsafe { File::from_raw_fd(vpn_fd) };

        let mut dns_packet_proxy = DnsPacketProxy::new(
            &android_vpn_callback,
            block_logger_callback,
            rule_database,
            upstream_dns_servers
                .iter()
                .filter_map(|server| {
                    if let Ok(ipv4_addr) = Ipv4Addr::from_str(server) {
                        return Some(ipv4_addr.octets().to_vec());
                    }

                    if let Ok(ipv6_addr) = Ipv6Addr::from_str(server) {
                        return Some(ipv6_addr.octets().to_vec());
                    }

                    return None;
                })
                .collect(),
        );

        let mut poll = match Poll::new() {
            Ok(value) => value,
            Err(e) => {
                error!("do_one: Failed to create poller! - {:?}", e);
                return Result::Err(VpnError::TunnelPollFailure);
            }
        };
        if let Err(e) = poll.registry().register(
            &mut SourceFd(&self.vpn_controller.event_fd),
            Self::VPN_CONTROLLER_TOKEN,
            Interest::READABLE,
        ) {
            error!("run: Failed to register signal descriptor! - {:?}", e);
            return Result::Err(VpnError::TunnelPollFailure);
        }
        let mut events = Events::with_capacity(max_sources + 2);

        android_vpn_callback.notify(VpnStatus::Running as i32);
        loop {
            match self.do_one(
                &mut poll,
                &mut events,
                &mut vpn_file,
                &mut backend,
                &mut dns_packet_proxy,
                packet.as_mut_slice(),
            ) {
                Ok(result) => match result {
                    VpnResult::Continuing => continue,
                    _ => return Ok(result),
                },
                Err(e) => {
                    return Result::Err(e);
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
        packet: &mut [u8],
    ) -> Result<VpnResult, VpnError> {
        if let Err(e) = poll.registry().register(
            &mut SourceFd(&vpn_file.as_raw_fd()),
            Self::VPN_TOKEN,
            if !self.device_writes.is_empty() {
                Interest::READABLE | Interest::WRITABLE
            } else {
                Interest::READABLE
            },
        ) {
            error!("do_one: Failed to add VPN descriptor to poller! - {:?}", e);
            return Result::Err(VpnError::TunnelPollFailure);
        }

        let backend_sources = backend.register_sources(poll);
        debug!("do_one: Polling {} sources(s)", backend_sources + 2);
        if let Err(e) = poll.poll(events, None) {
            error!("do_one: Failed to poll sockets! - {:?}", e);
            return Result::Err(VpnError::TunnelPollFailure);
        }

        if let Some(result) = self.vpn_controller.get_stop_result() {
            info!("do_one: Told to stop");
            return Ok(result);
        }

        // Need to do this before reading from the device, otherwise a new insertion there could
        // invalidate one of the sockets we want to read from either due to size or time out
        // constraints
        let mut read_from_device = false;
        let mut write_to_device = false;
        for event in events.iter() {
            debug!("do_one: Got event {:?}", event);
            if event.token() == Self::VPN_TOKEN {
                read_from_device = read_from_device || event.is_readable();
                write_to_device = write_to_device || event.is_writable();
            } else if event.token() == Self::VPN_CONTROLLER_TOKEN {
                break;
            } else {
                match backend.process_event(self, event) {
                    Ok(source) => {
                        if let Some(mut source) = source {
                            if let Err(e) = poll.registry().deregister(&mut source) {
                                warn!("do_one: Failed to remove socket from poller! - {:?}", e);
                            }
                        }
                    }
                    Err(e) => {
                        error!("do_one: Failed to process DnsBackend event - {:?}", e);
                        return Result::Err(VpnError::SourcePollFailure);
                    }
                }
            }
        }

        if write_to_device {
            self.write_to_device(vpn_file)?;
        }

        if read_from_device {
            self.read_packet_from_device(vpn_file, backend, dns_packet_proxy, packet)?;
        }

        if let Err(e) = poll
            .registry()
            .deregister(&mut SourceFd(&vpn_file.as_raw_fd()))
        {
            error!("do_one: Failed to remove VPN FD from poller! - {:?}", e);
            return Result::Err(VpnError::TunnelPollFailure);
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
            Err(e) => {
                error!("write_to_device: Failed writing - {:?}", e);
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
        packet: &mut [u8],
    ) -> Result<(), VpnError> {
        let length = match vpn_file.read(packet) {
            Ok(value) => value,
            Err(e) => {
                error!("read_packet_from_device: Cannot read from device - {:?}", e);
                return Result::Err(VpnError::TunnelReadFailure);
            }
        };

        if length == 0 {
            warn!("read_packet_from_device: Got empty packet!");
            return Result::Ok(());
        }

        dns_packet_proxy.handle_dns_request(self, backend, &packet[..length]);

        return Result::Ok(());
    }

    /// Handles a DNS response and forwards it to the tunnel with the translated destination
    fn handle_dns_response(&mut self, request_packet: &[u8], response_payload: &[u8]) {
        match build_response_packet(request_packet, response_payload) {
            Some(packet) => self.device_writes.push_back(packet),
            None => return,
        };
    }
}

/// Struct that holds a socket that we're waiting on and it's associated packet.
/// Additionally holds the time that we started waiting on it to see if we need to drop it.
#[derive(Debug)]
struct WaitingOnSocketPacket {
    socket: UdpSocket,
    packet: Vec<u8>,
    time: u128,
}

impl WaitingOnSocketPacket {
    fn new(socket: UdpSocket, packet: Vec<u8>) -> Self {
        Self {
            socket,
            packet,
            time: get_epoch_millis(),
        }
    }

    fn age_seconds(&self) -> u128 {
        (get_epoch_millis() - self.time) / 1000
    }
}

/// Holds a list of [WaitingOnSocketPacket]s and manages dropping sockets when they're too old
struct WospList {
    list: VecDeque<WaitingOnSocketPacket>,
}

impl WospList {
    const DNS_MAXIMUM_WAITING: usize = 1024;
    const DNS_TIMEOUT_SEC: u128 = 10;

    fn new() -> Self {
        Self {
            list: VecDeque::new(),
        }
    }

    fn add(&mut self, wosp: WaitingOnSocketPacket) {
        if self.list.len() > Self::DNS_MAXIMUM_WAITING {
            debug!(
                "add: Dropping socket due to space constraints: {:?}",
                self.list.front().unwrap().packet
            );
            self.list.pop_front();
        }

        while !self.list.is_empty()
            && self.list.front().unwrap().age_seconds() > Self::DNS_TIMEOUT_SEC
        {
            debug!(
                "add: Timeout on socket {:?}",
                self.list.front().unwrap().socket
            );
            self.list.pop_front();
        }

        self.list.push_back(wosp);
    }
}

/// Callback interface for accessing our hostfiles from the Android system
#[uniffi::export(callback_interface)]
pub trait AndroidFileHelper {
    fn get_host_fd(&self, host: String) -> Option<i32>;
}

/// Holds a few flags to tell the [RuleDatabase] what to do from the Kotlin side
#[derive(uniffi::Object)]
pub struct RuleDatabaseController {
    initialized: AtomicBool,
    reloading: AtomicBool,
    should_stop: AtomicBool,
}

#[uniffi::export]
impl RuleDatabaseController {
    #[uniffi::constructor]
    fn new() -> Self {
        RuleDatabaseController {
            initialized: AtomicBool::new(false),
            reloading: AtomicBool::new(false),
            should_stop: AtomicBool::new(false),
        }
    }

    fn set_initialized(&self) {
        self.initialized
            .store(true, std::sync::atomic::Ordering::Relaxed);
    }

    /// Returns whether the database has been initialized for the first time
    fn is_initialized(&self) -> bool {
        return self.initialized.load(std::sync::atomic::Ordering::Relaxed);
    }

    fn get_should_stop(&self) -> bool {
        return self.should_stop.load(std::sync::atomic::Ordering::Relaxed);
    }

    /// Tells the database that this controller is attached to that it should stop reloading
    ///
    /// This is reset to false when the database is told to initialize
    fn set_should_stop(&self, value: bool) {
        self.should_stop
            .store(value, std::sync::atomic::Ordering::Relaxed);
    }

    fn set_reloading(&self, value: bool) {
        self.reloading
            .store(value, std::sync::atomic::Ordering::Relaxed);
    }

    /// Returns whether the database is currently reloading
    fn is_reloading(&self) -> bool {
        return self.reloading.load(std::sync::atomic::Ordering::Relaxed);
    }
}

/// Represents the state of a host in the block list (Mirrors the version in Kotlin)
#[derive(uniffi::Enum, PartialEq, PartialOrd, Debug)]
pub enum NativeHostState {
    IGNORE,
    DENY,
    ALLOW,
}

/// Represents a host in the block list (Mirrors the version in Kotlin)
#[derive(uniffi::Record, Debug)]
pub struct NativeHost {
    title: String,
    data: String,
    state: NativeHostState,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
enum RuleDatabaseError {
    #[error("Bad host format")]
    BadHostFormat,

    #[error("Interrupted by VpnController")]
    Interrupted,

    #[error("Failed to acquire lock on hosts structures")]
    LockError,
}

/// Whether a single host should be denied or allowed
enum HostnameAction {
    Deny,
    Allow,
}

/// Whether a hostname is a wildcard or a single host in the [RuleDatabase]
#[derive(PartialEq)]
enum HostnameType {
    Host,
    Wildcard,
}

/// Holds the block list and manages the loading of the block list
#[derive(uniffi::Object)]
pub struct RuleDatabase {
    controller: Arc<RuleDatabaseController>,
    map: RwLock<HashMap<String, (HostnameType, HostnameAction)>>,
}

#[uniffi::export]
impl RuleDatabase {
    #[uniffi::constructor]
    fn new(controller: Arc<RuleDatabaseController>) -> Self {
        RuleDatabase {
            controller,
            map: RwLock::new(HashMap::new()),
        }
    }

    /// Initializes the block list with the given hosts and exceptions
    fn initialize(
        &self,
        android_file_helper: Box<dyn AndroidFileHelper>,
        host_items: Vec<NativeHost>,
        host_exceptions: Vec<NativeHost>,
    ) -> Result<(), RuleDatabaseError> {
        if self
            .controller
            .reloading
            .fetch_or(true, std::sync::atomic::Ordering::Relaxed)
        {
            info!("initialize: Already reloading, skipping");
            return Ok(());
        }
        if self
            .controller
            .should_stop
            .fetch_or(false, std::sync::atomic::Ordering::Relaxed)
        {
            info!("initialize: Told to stop, skipping");
            return Ok(());
        }
        info!(
            "initialize: Loading block list with {} hosts and {} exceptions",
            host_items.len(),
            host_exceptions.len()
        );

        let mut map = HashMap::<String, (HostnameType, HostnameAction)>::new();

        let mut sorted_host_items = host_items
            .iter()
            .filter(|item| item.state != NativeHostState::IGNORE)
            .collect::<Vec<&NativeHost>>();
        sorted_host_items.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for item in sorted_host_items.iter() {
            if let Err(database_error) =
                load_item(&android_file_helper, &self.controller, &mut map, item)
            {
                if let RuleDatabaseError::Interrupted = database_error {
                    return Err(database_error);
                }
            }
        }

        let mut sorted_host_exceptions = host_exceptions
            .iter()
            .filter(|item| item.state != NativeHostState::IGNORE)
            .collect::<Vec<&NativeHost>>();
        sorted_host_exceptions.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for exception in sorted_host_exceptions {
            if let Err(error) = add_host(
                &self.controller,
                &mut map,
                &exception.state,
                exception.data.clone(),
            ) {
                if let RuleDatabaseError::Interrupted = error {
                    return Err(error);
                }
            }
        }

        let mut hosts_guard = match self.map.write() {
            Ok(value) => value,
            Err(e) => {
                error!("initialize: Failed to get write lock for data - {:?}", e);
                return Err(RuleDatabaseError::LockError);
            }
        };

        *hosts_guard = map;

        info!(
            "initialize: Loaded {} value(s) into the block list",
            hosts_guard.len()
        );
        self.controller.set_reloading(false);
        self.controller.set_initialized();
        return Ok(());
    }

    /// Blocks the current thread until the database has been reloaded or told to stop
    fn wait_on_init(&self) {
        loop {
            if self.controller.is_initialized() && !self.controller.is_reloading() {
                break;
            }
            if self.controller.get_should_stop() {
                break;
            }
            thread::sleep(Duration::from_millis(100));
        }
    }

    /// Checks if a host is blocked
    fn is_blocked(&self, host: &str) -> bool {
        let map = match self.map.read() {
            Ok(value) => value,
            Err(e) => {
                error!("is_blocked: Failed to get read lock for hosts - {:?}", e);
                return false;
            }
        };

        if map.is_empty() {
            return false;
        }

        if let Some(value) = map.get(host) {
            return match value.1 {
                HostnameAction::Deny => true,
                HostnameAction::Allow => false,
            };
        } else {
            let mut sub_host = host;
            for split in host.split('.') {
                sub_host = match sub_host.split_once(&(split.to_owned() + ".")) {
                    Some(value) => value.1,
                    None => break,
                };
                if !sub_host.contains('.') {
                    break;
                }
                if let Some(value) = map.get(sub_host) {
                    if value.0 == HostnameType::Host {
                        continue;
                    }

                    return match value.1 {
                        HostnameAction::Deny => true,
                        HostnameAction::Allow => false,
                    };
                }
            }
            return false;
        }
    }
}

const IPV4_LOOPBACK: &'static str = "127.0.0.1";
const IPV6_LOOPBACK: &'static str = "::1";
const NO_ROUTE: &'static str = "0.0.0.0";

/// Parses a single line in a hostfile and returns the host if it's valid
fn parse_line(line: &str) -> Option<String> {
    if line.trim().is_empty() {
        return None;
    }

    // AdBlock Plus style hosts files use ## for extra functionality that we don't support
    if line.contains("##") {
        return None;
    }

    let end_of_line = match line.find('#') {
        Some(index) => index,
        None => line.len(),
    };

    let mut start_of_host = 0;

    if let Some(index) = line.find(IPV4_LOOPBACK) {
        start_of_host += index + IPV4_LOOPBACK.len();
    }

    if start_of_host == 0 {
        if let Some(index) = line.find(IPV6_LOOPBACK) {
            start_of_host += index + IPV6_LOOPBACK.len();
        }
    }

    if start_of_host == 0 {
        if let Some(index) = line.find(NO_ROUTE) {
            start_of_host += index + NO_ROUTE.len();
        }
    }

    if start_of_host >= end_of_line {
        return None;
    }

    let host = (&line[start_of_host..end_of_line]).trim().to_lowercase();
    if host.is_empty() || host.contains(char::is_whitespace) {
        return None;
    }

    return Some(host);
}

/// Loads a generic host (file or single host) and adds them to the block list
fn load_item(
    android_file_helper: &Box<dyn AndroidFileHelper>,
    controller: &Arc<RuleDatabaseController>,
    map: &mut HashMap<String, (HostnameType, HostnameAction)>,
    host: &NativeHost,
) -> Result<(), RuleDatabaseError> {
    if host.state == NativeHostState::IGNORE {
        return Err(RuleDatabaseError::Interrupted);
    }

    match android_file_helper.get_host_fd(host.data.clone()) {
        Some(value) => {
            let file = unsafe { File::from_raw_fd(value) };
            let lines: io::Lines<io::BufReader<File>> = io::BufReader::new(file).lines();
            if let Err(error) = load_file(controller, map, &host, lines) {
                if let RuleDatabaseError::Interrupted = error {
                    return Err(error);
                }
            }
        }
        None => {
            warn!(
                "Failed to open {}. Attempting to add as single host.",
                host.data
            );
            if let Err(error) = add_host(controller, map, &host.state, host.data.clone()) {
                if let RuleDatabaseError::Interrupted = error {
                    return Err(error);
                }
            }
        }
    };
    return Ok(());
}

/// Adds a single host to the block list
fn add_host(
    controller: &Arc<RuleDatabaseController>,
    map: &mut HashMap<String, (HostnameType, HostnameAction)>,
    state: &NativeHostState,
    data: String,
) -> Result<(), RuleDatabaseError> {
    if controller.get_should_stop() {
        return Err(RuleDatabaseError::Interrupted);
    }

    match data.get(..2) {
        Some(first_two_chars) => {
            // Star pseudo-wildcard style e.g. *.example.com
            if first_two_chars.chars().nth(0).unwrap() == '*' {
                // Ignore the *. at the start of a pseudo-wildcard host
                return match data.get(2..data.len()) {
                    Some(value) => {
                        match state {
                            NativeHostState::IGNORE => {}
                            NativeHostState::DENY => {
                                map.insert(
                                    value.to_owned(),
                                    (HostnameType::Wildcard, HostnameAction::Deny),
                                );
                            }
                            NativeHostState::ALLOW => {
                                map.insert(
                                    value.to_owned(),
                                    (HostnameType::Wildcard, HostnameAction::Allow),
                                );
                            }
                        };
                        Ok(())
                    }
                    None => Err(RuleDatabaseError::BadHostFormat),
                };
            } else if first_two_chars == "||" {
                // AdBlock Plus style pseudo-wildcard e.g. ||example.com^
                match data.chars().last() {
                    Some(last_char) => {
                        if last_char == '^' {
                            return match data.get(2..data.len() - 1) {
                                Some(value) => {
                                    match state {
                                        NativeHostState::IGNORE => {}
                                        NativeHostState::DENY => {
                                            map.insert(
                                                value.to_owned(),
                                                (HostnameType::Wildcard, HostnameAction::Deny),
                                            );
                                        }
                                        NativeHostState::ALLOW => {
                                            map.insert(
                                                value.to_owned(),
                                                (HostnameType::Wildcard, HostnameAction::Allow),
                                            );
                                        }
                                    };
                                    Ok(())
                                }
                                None => Err(RuleDatabaseError::BadHostFormat),
                            };
                        }
                    }
                    None => return Err(RuleDatabaseError::BadHostFormat),
                };
                return Err(RuleDatabaseError::BadHostFormat);
            }
        }
        None => return Err(RuleDatabaseError::BadHostFormat),
    };

    // Reject invalid characters in hostname
    if !data
        .chars()
        .all(|c| c.is_alphanumeric() || c == '.' || c == '-')
    {
        return Err(RuleDatabaseError::BadHostFormat);
    }

    // Plain host e.g. example.com
    match state {
        NativeHostState::IGNORE => {}
        NativeHostState::DENY => {
            map.insert(data, (HostnameType::Host, HostnameAction::Deny));
        }
        NativeHostState::ALLOW => {
            map.insert(data, (HostnameType::Host, HostnameAction::Allow));
        }
    };
    return Ok(());
}

/// Loads a file of hosts and adds them to the block list
fn load_file(
    controller: &Arc<RuleDatabaseController>,
    map: &mut HashMap<String, (HostnameType, HostnameAction)>,
    host: &NativeHost,
    lines: io::Lines<io::BufReader<File>>,
) -> Result<(), RuleDatabaseError> {
    let mut count = 0;
    for line in lines {
        match line {
            Ok(value) => {
                if let Some(data) = parse_line(value.as_str()) {
                    if let Err(error) = add_host(controller, map, &host.state, data) {
                        if let RuleDatabaseError::Interrupted = error {
                            return Err(error);
                        }
                    }
                }
                count += 1;
            }
            Err(e) => {
                error!(
                    "load_file: Error while reading {} after {} lines - {:?}",
                    &host.data, count, e
                );
                return Err(RuleDatabaseError::BadHostFormat);
            }
        }
    }
    debug!("load_file: Loaded {} hosts from {}", count, &host.data);
    return Ok(());
}

/// Callback interface for logging connections that we've blocked for the block logger
#[uniffi::export(callback_interface)]
pub trait BlockLoggerCallback: Send + Sync {
    fn log(&self, connection_name: String, allowed: bool);
}

/// Handler for DNS packets that accepts or blocks them based on our [RuleDatabase]
struct DnsPacketProxy<'a> {
    android_vpn_callback: &'a Box<dyn AdVpnCallback>,
    block_logger_callback: Box<dyn BlockLoggerCallback>,
    rule_database: Arc<RuleDatabase>,
    upstream_dns_servers: Vec<Vec<u8>>,
    negative_cache_record: ResourceRecord<'a>,
}

impl<'a> DnsPacketProxy<'a> {
    const INVALID_HOSTNAME: &'static str = "dnsnet.dnsnet.invalid.";
    const NEGATIVE_CACHE_TTL_SECONDS: u32 = 5;

    fn new(
        android_vpn_callback: &'a Box<dyn AdVpnCallback>,
        block_logger_callback: Box<dyn BlockLoggerCallback>,
        rule_database: Arc<RuleDatabase>,
        upstream_dns_servers: Vec<Vec<u8>>,
    ) -> Self {
        let name = match Name::new(Self::INVALID_HOSTNAME) {
            Ok(value) => value,
            Err(e) => {
                error!("Failed to parse our invalid hostname! - {:?}", e);
                panic!();
            }
        };
        let soa_record = RData::SOA(simple_dns::rdata::SOA {
            mname: name.clone(),
            rname: name.clone(),
            serial: 0,
            refresh: 0,
            retry: 0,
            expire: 0,
            minimum: Self::NEGATIVE_CACHE_TTL_SECONDS,
        });
        let negative_cache_record = ResourceRecord::new(
            name,
            simple_dns::CLASS::IN,
            Self::NEGATIVE_CACHE_TTL_SECONDS,
            soa_record,
        );
        DnsPacketProxy {
            android_vpn_callback,
            block_logger_callback,
            rule_database,
            upstream_dns_servers,
            negative_cache_record,
        }
    }

    /// Parses a DNS request and forwards it to the real DNS server if it's allowed
    fn handle_dns_request(
        &mut self,
        ad_vpn: &mut AdVpn,
        backend: &mut Box<dyn DnsBackend>,
        packet_data: &[u8],
    ) {
        let packet = match GenericIpPacket::from_ip_packet(packet_data) {
            Some(value) => value,
            None => {
                warn!(
                    "handle_dns_request: Failed to parse packet data - {:?}",
                    packet_data
                );
                return;
            }
        };

        let udp_packet = match packet.get_udp_packet() {
            Some(value) => value,
            None => {
                warn!("handle_dns_request: IP packet did not contain UDP payload");
                return;
            }
        };

        let destination_address = match packet.get_destination_address() {
            Some(value) => value,
            None => {
                warn!(
                    "handle_dns_request: Failed to get destination address for packet - {:?}",
                    packet
                );
                return;
            }
        };
        let translated_destination_address =
            match self.translate_destination_address(&destination_address) {
                Some(value) => value,
                None => {
                    warn!(
                        "handle_dns_request: Failed to translate destination address - {:?}",
                        destination_address
                    );
                    return;
                }
            };

        let destination_port = udp_packet.destination_port();
        let mut dns_packet = match simple_dns::Packet::parse(udp_packet.payload()) {
            Ok(value) => value,
            Err(e) => {
                warn!(
                    "handle_dns_request: Discarding no-DNS or invalid packet - {:?}",
                    e
                );
                return;
            }
        };

        if dns_packet.questions.is_empty() {
            warn!(
                "handle_dns_request: Discarding DNS packet with no questions - {:?}",
                dns_packet
            );
            return;
        }

        let dns_query_name = dns_packet
            .questions
            .first()
            .unwrap()
            .qname
            .to_string()
            .to_lowercase();
        if !self.rule_database.is_blocked(&dns_query_name) {
            info!(
                "handle_dns_request: DNS Name {} allowed. Sending to {:?}",
                dns_query_name, translated_destination_address
            );
            self.block_logger_callback.log(dns_query_name, true);

            if translated_destination_address.len() == 4 {
                // IPV4
                let destination_socket_address = SocketAddrV4::new(
                    Ipv4Addr::from(
                        TryInto::<[u8; 4]>::try_into(translated_destination_address).unwrap(),
                    ),
                    destination_port,
                );

                if let Err(e) = backend.forward_packet(
                    &self.android_vpn_callback,
                    udp_packet.payload(),
                    packet_data,
                    std::net::SocketAddr::V4(destination_socket_address),
                ) {
                    error!("handle_dns_request: Failed to forward packet - {:?}", e);
                }
            } else if translated_destination_address.len() == 16 {
                // IPV6
                let destination_socket_address = SocketAddrV6::new(
                    Ipv6Addr::from(
                        TryInto::<[u8; 16]>::try_into(translated_destination_address).unwrap(),
                    ),
                    destination_port,
                    0,
                    0,
                );

                if let Err(e) = backend.forward_packet(
                    &self.android_vpn_callback,
                    udp_packet.payload(),
                    packet_data,
                    std::net::SocketAddr::V6(destination_socket_address),
                ) {
                    error!("handle_dns_request: Failed to forward packet - {:?}", e);
                }
            } else {
                warn!(
                    "handle_dns_request: Received destination address with unknown protocol! - {:?}",
                    translated_destination_address
                );
            }
        } else {
            info!("handle_dns_request: DNS Name {} blocked!", dns_query_name);
            self.block_logger_callback.log(dns_query_name, false);

            dns_packet.set_flags(PacketFlag::RESPONSE);
            *dns_packet.rcode_mut() = simple_dns::RCODE::NoError;
            dns_packet
                .additional_records
                .push(self.negative_cache_record.clone());

            let mut wire = Vec::<u8>::new();
            if let Err(e) = dns_packet.write_to(&mut wire) {
                error!("Failed to write DNS packet to wire! - {:?}", e);
                return;
            }

            ad_vpn.handle_dns_response(packet_data, &wire);
        }
    }

    /// Translates the destination address using our upstream servers as configured by the AdVpnThread
    fn translate_destination_address(&self, destination_address: &Vec<u8>) -> Option<Vec<u8>> {
        return if !self.upstream_dns_servers.is_empty() {
            let index = match destination_address.get(destination_address.len() - 1) {
                Some(value) => value,
                None => {
                    debug!(
                        "translate_destination_address: Failed to get upstream index from destination address"
                    );
                    return None;
                }
            };

            self.upstream_dns_servers
                .get((*index - 2) as usize)
                .cloned()
        } else {
            Some(destination_address.clone())
        };
    }
}
