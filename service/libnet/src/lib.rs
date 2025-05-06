use std::{
    collections::{HashMap, VecDeque}, fs::File, io::{self, BufRead, Read, Write}, net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6}, os::fd::{AsRawFd, FromRawFd}, str, sync::{atomic::AtomicBool, Arc, RwLock}, thread, time::{Duration, Instant, SystemTime, UNIX_EPOCH}, u64, usize
};

use android_logger::Config;
use base64::{Engine, prelude::BASE64_STANDARD_NO_PAD};
use etherparse::{
    IpHeaders, IpNumber, Ipv4Header, Ipv6FlowLabel, Ipv6Header, NetSlice, PacketBuilder,
    PacketBuilderStep, SlicedPacket, TransportSlice, UdpSlice, ip_number,
};
use log::LevelFilter;
use mio::{Events, Interest, Poll, Token, net::UdpSocket};
use mio::{event::Source, unix::SourceFd};
use quiche::{h3::{Header, NameValue}, SendInfo};
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
    block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
    vpn_controller: Arc<VpnController>,
    rule_database: Arc<RuleDatabase>,
) -> Result<VpnResult, VpnError> {
    let mut vpn = AdVpn::new(vpn_controller);
    let result = vpn.run(ad_vpn_callback, block_logger_callback, rule_database);
    info!("run_vpn_native: Stopped");
    return result;
}

#[derive(uniffi::Enum)]
pub enum NativeDnsServerType {
    /// The DNS server is a DoH3 server (e.g. https://dns.google/dns-query).
    ///
    /// For convenience, the sanitized name (e.g. dns.google) is held in this enum.
    DoH3(String),

    /// The DNS server is a standard DNS server (e.g. 8.8.8.8)
    Standard,
}

#[derive(uniffi::Object)]
pub struct NativeDnsServer {
    address: Vec<u8>,
    address_type: NativeDnsServerType,
}

#[uniffi::export]
impl NativeDnsServer {
    #[uniffi::constructor]
    pub fn new(address: Vec<u8>, address_type: NativeDnsServerType) -> Self {
        Self {
            address,
            address_type,
        }
    }

    pub fn get_address(&self) -> Vec<u8> {
        self.address.clone()
    }
}

#[uniffi::export]
pub fn validate_dns_servers(user_servers: Vec<String>) -> Vec<Arc<NativeDnsServer>> {
    let mut validated_servers = Vec::<Arc<NativeDnsServer>>::new();
    for unvalidated_server in user_servers.iter() {
        if let Ok(ipv4_ip) = unvalidated_server.parse::<Ipv4Addr>() {
            validated_servers.push(Arc::new(NativeDnsServer::new(
                ipv4_ip.octets().to_vec(),
                NativeDnsServerType::Standard,
            )));
            continue;
        }

        if let Ok(ipv6_ip) = unvalidated_server.parse::<Ipv6Addr>() {
            validated_servers.push(Arc::new(NativeDnsServer::new(
                ipv6_ip.octets().to_vec(),
                NativeDnsServerType::Standard,
            )));
            continue;
        }

        let stripped_prefix_server = unvalidated_server
            .strip_prefix("https://")
            .unwrap_or(&unvalidated_server);
        let stripped_server = if let Some(index) = stripped_prefix_server.find("/") {
            &stripped_prefix_server[..index]
        } else {
            stripped_prefix_server
        };

        if stripped_server.is_empty() {
            error!(
                "validate_dns_servers: Rejecting invalid DoH3 server name - {unvalidated_server}"
            );
            continue;
        }

        let url = match url::Url::parse(format!("https://{stripped_server}").as_str()) {
            Ok(value) => value,
            Err(error) => {
                error!("new: Failed to parse URL! - {:?}", error);
                continue;
            }
        };

        let addresses = match url.socket_addrs(|| None) {
            Ok(value) => value,
            Err(error) => {
                error!("new: Failed to get socket address! - {:?}", error);
                continue;
            }
        };

        if addresses.is_empty() {
            error!("new: No socket address found!");
            continue;
        }

        info!("Resolved address - {:?}", addresses[0]);

        let ip = match addresses[0].ip() {
            std::net::IpAddr::V4(ipv4_addr) => ipv4_addr.octets().to_vec(),
            std::net::IpAddr::V6(ipv6_addr) => ipv6_addr.octets().to_vec(),
        };

        validated_servers.push(Arc::new(NativeDnsServer::new(
            ip,
            NativeDnsServerType::DoH3(stripped_server.to_string()),
        )));
    }
    return validated_servers;
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
        Err(error) => {
            error!(
                "build_packet_v4: Failed to create Ipv4Header! - {:?}",
                error
            );
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
    if let Err(error) = udp_builder.write(&mut result, &udp_payload) {
        error!("build_packet: Failed to build packet! - {:?}", error);
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
        if let Some(header) = self.get_ipv4_header() {
            return Some(header.destination.to_vec());
        }
        if let Some(header) = self.get_ipv6_header() {
            return Some(header.destination.to_vec());
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

    #[error("At least one invalid DNS server provided")]
    InvalidDnsServer,
}

#[derive(uniffi::Enum)]
pub enum VpnConfigurationResult {
    // The device is not connected to any networks and should wait before establishing the VPN
    NoNetwork,

    // The Android VpnService builder returned a null file descriptor and we should restart
    BuilderFailure,

    // At least one of the user's DNS servers were invalid
    InvalidDnsServer,

    // The VpnService was established correctly with a valid file descriptor
    Success(i32, Vec<Arc<NativeDnsServer>>),
}

/// Callback interface to be implemented by a Kotlin class and then passed into the main loop
#[uniffi::export(callback_interface)]
pub trait AdVpnCallback: Send + Sync {
    fn configure(&self) -> VpnConfigurationResult;

    fn protect_raw_socket_fd(&self, socket_fd: i32) -> bool;

    fn update_status(&self, native_status: i32);
}

#[derive(Debug)]
enum DnsBackendError {
    ForwardFailure,
    InvalidAddress,
    SocketFailure,
    RandomGenerationFailure,
}

trait DnsBackend {
    /// Returns the max number of events that the events object will be initialized with.
    fn get_max_events_count(&self) -> usize;

    fn get_poll_timeout(&self) -> Option<Duration>;

    /// Register sources with the poller.
    /// Returns the number of sources that were registered.
    /// You MUST NOT register sources that have a token value of [usize::MAX] or [usize::MAX] - 1.
    fn register_sources(&mut self, poll: &mut Poll) -> usize;

    fn forward_packet(
        &mut self,
        android_vpn_service: &Box<dyn AdVpnCallback>,
        packet: &[u8],
        request_packet: &[u8],
        destination_address: Vec<u8>,
        destination_port: u16,
    ) -> Result<(), DnsBackendError>;

    /// Process all events from the poller and send any processed packets to the [DnsPacketProxy].
    /// Return a [Source] if it should be removed from the poller and [None] if it should be kept.
    fn process_events(
        &mut self,
        ad_vpn: &mut AdVpn,
        events: Vec<&mio::event::Event>,
    ) -> Result<Vec<Box<dyn Source>>, DnsBackendError>;
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
    fn get_max_events_count(&self) -> usize {
        return WospList::DNS_MAXIMUM_WAITING;
    }

    fn get_poll_timeout(&self) -> Option<Duration> {
        None
    }

    fn register_sources(&mut self, poll: &mut Poll) -> usize {
        let mut waiting_sockets = 0;
        self.wosp_list.list.retain_mut(|wosp| {
            if wosp.socket_registered {
                waiting_sockets += 1;
                return true;
            }

            match poll.registry().register(
                &mut wosp.socket,
                Token(wosp.creation_time as usize),
                Interest::READABLE,
            ) {
                Ok(_) => {
                    wosp.socket_registered = true;
                    waiting_sockets += 1;
                    true
                }
                Err(error) => {
                    if error.kind() == std::io::ErrorKind::AlreadyExists {
                        wosp.socket_registered = true;
                        waiting_sockets += 1;
                        true
                    } else {
                        warn!(
                            "register_sources: Failed to add socket {:?} to poller! - {:?}",
                            wosp, error
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
        destination_address: Vec<u8>,
        destination_port: u16,
    ) -> Result<(), DnsBackendError> {
        let socket = match UdpSocket::bind(self.unspecified_bind_address) {
            Ok(value) => value,
            Err(error) => {
                error!("forward_packet: Failed to create socket! - {:?}", error);
                return Err(DnsBackendError::SocketFailure);
            }
        };

        // Packets to be sent to the real DNS server will need to be protected from the VPN
        if !android_vpn_service.protect_raw_socket_fd(socket.as_raw_fd()) {
            error!("forward_packet: Failed for protect socket fd!");
            return Err(DnsBackendError::SocketFailure);
        }

        let destination_socket_address: SocketAddr = if destination_address.len() == 4 {
            // IPV4
            let ipv4_address_array = match TryInto::<[u8; 4]>::try_into(destination_address) {
                Ok(value) => value,
                Err(error) => {
                    error!(
                        "forward_packet: Failed to convert destination address to IPV4! - {:?}",
                        error
                    );
                    return Err(DnsBackendError::InvalidAddress);
                }
            };

            SocketAddr::from(SocketAddrV4::new(
                Ipv4Addr::from(ipv4_address_array),
                destination_port,
            ))
        } else if destination_address.len() == 16 {
            // IPV6
            let ipv6_address_array = match TryInto::<[u8; 16]>::try_into(destination_address) {
                Ok(value) => value,
                Err(error) => {
                    error!(
                        "forward_packet: Failed to convert destination address to IPV6! - {:?}",
                        error
                    );
                    return Err(DnsBackendError::InvalidAddress);
                }
            };

            SocketAddr::from(SocketAddrV6::new(
                Ipv6Addr::from(ipv6_address_array),
                destination_port,
                0,
                0,
            ))
        } else {
            warn!(
                "handle_dns_request: Received destination address with unknown protocol! - {:?}",
                destination_address
            );
            return Ok(());
        };

        return match socket.send_to(packet, destination_socket_address) {
            Ok(_) => {
                self.wosp_list
                    .add(WaitingOnSocketPacket::new(socket, request_packet.to_vec()));
                Ok(())
            }
            Err(error) => {
                error!("forward_packet: Failed to send packet! - {:?}", error);
                Err(DnsBackendError::ForwardFailure)
            }
        };
    }

    fn process_events(
        &mut self,
        ad_vpn: &mut AdVpn,
        events: Vec<&mio::event::Event>,
    ) -> Result<Vec<Box<dyn Source>>, DnsBackendError> {
        let mut sources_to_remove = Vec::<Box<dyn Source>>::new();
        for event in events.iter() {
            if let Some(index) = self
                .wosp_list
                .list
                .iter()
                .position(|value| (value.creation_time as usize) == event.token().0)
            {
                if let Some(wosp) = self.wosp_list.list.remove(index) {
                    debug!("process_event: Read from DNS socket: {:?}", wosp.socket);

                    match wosp.socket.recv(&mut self.response_packet.as_mut_slice()) {
                        Ok(size) => {
                            ad_vpn.handle_dns_response(
                                &wosp.packet,
                                &mut self.response_packet[..size],
                            );
                        }
                        Err(error) => {
                            warn!(
                                "process_event: Failed to receive response packet from DNS socket! - {:?}",
                                error
                            );
                        }
                    };
                    sources_to_remove.push(Box::new(wosp.socket));
                }
            }
        }
        return Ok(sources_to_remove);
    }
}

#[derive(Debug)]
enum DoH3BackendError {
    /// Returned if we failed to build the quiche config
    ConfigurationFailure,
}

#[derive(Debug, Clone)]
struct DoH3Server {
    domain_name: String,
    resolved_address: SocketAddr,
}

#[derive(Debug)]
struct DoH3Request {
    creation_time: std::time::Instant,
    request_packet: Vec<u8>,
    payload: Vec<Header>,
}

impl DoH3Request {
    pub fn new(server_name: &str, request_packet: &[u8], payload: &[u8]) -> Self {
        Self {
            creation_time: std::time::Instant::now(),
            request_packet: request_packet.to_vec(),
            payload: Self::make_dns_request_header(server_name, &payload),
        }
    }

    fn make_dns_request_header(server_name: &str, dns_payload: &[u8]) -> Vec<Header> {
        vec![
            Header::new(b":method", b"GET"),
            Header::new(b":scheme", b"https"),
            Header::new(b":authority", server_name.as_bytes()),
            Header::new(
                b":path",
                ("/dns-query?dns=".to_owned() + &BASE64_STANDARD_NO_PAD.encode(dns_payload))
                    .as_bytes(),
            ),
            Header::new(b"accept", b"application/dns-message"),
        ]
    }
}

struct DoH3ServerSession {
    socket: Option<UdpSocket>,
    socket_registered: bool,
    client_connection: quiche::Connection,
    local_address: SocketAddr,
    http3_connection: Option<quiche::h3::Connection>,
}

struct QueuedDoH3Packet {
    send_info: SendInfo,
    buffer: Vec<u8>,
}

struct DoH3ServerConnectionContainer {
    server: DoH3Server,
    config: quiche::Config,
    h3_config: quiche::h3::Config,
    active_session: Option<DoH3ServerSession>,
    request_queue: VecDeque<DoH3Request>,
    queued_packets: Vec<QueuedDoH3Packet>,
    sent_request_streams: HashMap<u64, DoH3Request>,
    token: Token,
}

impl DoH3ServerConnectionContainer {
    fn new(server: DoH3Server, token: Token) -> Result<Self, DoH3BackendError> {
        let mut config = match quiche::Config::new(quiche::PROTOCOL_VERSION) {
            Ok(value) => value,
            Err(error) => {
                error!("new: Failed to create quiche config! - {:?}", error);
                return Err(DoH3BackendError::ConfigurationFailure);
            }
        };

        // Use HTTP/3.
        if let Err(error) = config.set_application_protos(quiche::h3::APPLICATION_PROTOCOL) {
            error!("new: Failed to set protocol as HTTP/3! - {:?}", error);
            return Err(DoH3BackendError::ConfigurationFailure);
        }

        config.set_max_idle_timeout(5000);
        config.set_max_send_udp_payload_size(DoH3Backend::OUTPUT_BUFFER_SIZE);
        config.set_max_recv_udp_payload_size(DoH3Backend::OUTPUT_BUFFER_SIZE);
        config.set_initial_max_streams_bidi(100);
        config.set_initial_max_streams_uni(100);
        config.set_initial_max_data(10_000_000);
        config.set_initial_max_stream_data_bidi_local(10_000_000);
        config.set_initial_max_stream_data_bidi_remote(10_000_000);
        config.set_initial_max_stream_data_uni(10_000_000);
        config.set_disable_active_migration(true);

        let h3_config = match quiche::h3::Config::new() {
            Ok(value) => value,
            Err(error) => {
                error!("new: Failed to create quiche h3 config! - {:?}", error);
                return Err(DoH3BackendError::ConfigurationFailure);
            }
        };

        return Ok(DoH3ServerConnectionContainer {
            server,
            config,
            active_session: None,
            h3_config,
            request_queue: VecDeque::new(),
            queued_packets: Vec::new(),
            sent_request_streams: HashMap::new(),
            token,
        });
    }

    fn start_session(
        &mut self,
        bind_address: SocketAddr,
        android_vpn_service: &Box<dyn AdVpnCallback>,
        output_buffer: &mut [u8],
    ) -> Result<(), DnsBackendError> {
        if let None = self.active_session {
            info!(
                "forward_packet: Starting new session for {}",
                self.server.domain_name
            );
            let socket = match UdpSocket::bind(bind_address) {
                Ok(value) => value,
                Err(error) => {
                    error!("forward_packet: Failed to create socket! - {:?}", error);
                    return Err(DnsBackendError::SocketFailure);
                }
            };

            if !android_vpn_service.protect_raw_socket_fd(socket.as_raw_fd()) {
                error!("forward_packet: Failed to protect socket fd!");
                return Err(DnsBackendError::SocketFailure);
            }

            let server_name = Some(self.server.domain_name.as_str());

            // Generate a random source connection ID for the connection.
            let mut scid = [0; quiche::MAX_CONN_ID_LEN];
            if let Err(error) = getrandom::fill(&mut scid) {
                error!(
                    "forward_packet: Failed to generate random connection ID! - {:?}",
                    error
                );
                return Err(DnsBackendError::RandomGenerationFailure);
            }
            let scid = quiche::ConnectionId::from_ref(&scid);

            let local_address = match socket.local_addr() {
                Ok(value) => value,
                Err(error) => {
                    error!("forward_packet: Failed to get local address! - {:?}", error);
                    return Err(DnsBackendError::InvalidAddress);
                }
            };

            let mut client_connection = match quiche::connect(
                server_name,
                &scid,
                local_address,
                self.server.resolved_address,
                &mut self.config,
            ) {
                Ok(value) => value,
                Err(error) => {
                    error!(
                        "forward_packet: Failed to create quiche connection! - {:?}",
                        error
                    );
                    return Err(DnsBackendError::SocketFailure);
                }
            };

            debug!(
                "forward_packet: Connecting to {:?} from {:} with scid {}",
                self.server.resolved_address,
                local_address,
                hex_dump(&scid),
            );

            let (write, send_info) = match client_connection.send(output_buffer) {
                Ok(value) => value,
                Err(error) => {
                    error!("forward_packet: Failed to write handshake! - {:?}", error);
                    return Err(DnsBackendError::SocketFailure);
                }
            };

            while let Err(error) = socket.send_to(&output_buffer[..write], send_info.to) {
                if error.kind() == std::io::ErrorKind::WouldBlock {
                    debug!("forward_packet: send() would block");
                    continue;
                }

                error!("forward_packet: Failed to send handshake! - {:?}", error);
                return Err(DnsBackendError::SocketFailure);
            }

            self.active_session = Some(DoH3ServerSession {
                socket: Some(socket),
                socket_registered: false,
                client_connection,
                local_address,
                http3_connection: None,
            });
        }
        return Ok(());
    }

    fn end_session(&mut self, sources_to_remove: &mut Vec<Box<dyn Source>>) {
        info!(
            "process_events: Ending session for {}",
            self.server.domain_name
        );
        if let Some(mut session) = self.active_session.take() {
            if let Some(socket) = session.socket.take() {
                sources_to_remove.push(Box::new(socket));
            } else {
                warn!(
                    "end_session: No socket to remove for {}",
                    self.server.domain_name
                );
            }
        }
        self.request_queue.clear();
        self.queued_packets.clear();
        self.sent_request_streams.clear();
    }
}

fn headers_to_strings(headers: &[quiche::h3::Header]) -> Vec<(String, String)> {
    headers
        .iter()
        .map(|h| {
            let name = String::from_utf8_lossy(h.name()).to_string();
            let value = String::from_utf8_lossy(h.value()).to_string();

            (name, value)
        })
        .collect()
}

struct DoH3Backend {
    connections: HashMap<String, DoH3ServerConnectionContainer>,
    input_buffer: [u8; DoH3Backend::INPUT_BUFFER_SIZE],
    output_buffer: [u8; DoH3Backend::OUTPUT_BUFFER_SIZE],
    unspecified_bind_address: SocketAddr,
}

impl DoH3Backend {
    const STREAM_TIMEOUT_SECONDS: u64 = 10;

    const INPUT_BUFFER_SIZE: usize = u16::MAX as usize;
    const OUTPUT_BUFFER_SIZE: usize = 1350;

    /// Creates a new DoH3Backend with the provided servers. These servers are validated individually by
    /// resolving their addresses. If none of the servers are valid, an error is returned.
    fn new(servers: &Vec<Arc<NativeDnsServer>>) -> Result<Self, DoH3BackendError> {
        let mut connections: HashMap<String, DoH3ServerConnectionContainer> = HashMap::new();
        for (index, server) in servers.iter().enumerate() {
            let server_name = match &server.address_type {
                NativeDnsServerType::DoH3(server_name) => server_name.clone(),
                NativeDnsServerType::Standard => {
                    error!(
                        "new: DoH3 backend was given a standard DNS server! This should never happen!"
                    );
                    continue;
                }
            };

            let address = server.address.clone();
            let resolved_socket_address: SocketAddr = if server.address.len() == 4 {
                std::net::SocketAddr::V4(SocketAddrV4::new(
                    Ipv4Addr::from(TryInto::<[u8; 4]>::try_into(address).unwrap()),
                    443,
                ))
            } else if server.address.len() == 16 {
                std::net::SocketAddr::V6(SocketAddrV6::new(
                    Ipv6Addr::from(TryInto::<[u8; 16]>::try_into(address).unwrap()),
                    443,
                    0,
                    0,
                ))
            } else {
                error!(
                    "new: DoH3 backend was given an invalid resolved address! This should never happen!"
                );
                continue;
            };

            info!("Created SocketAddr - {:?}", resolved_socket_address);

            let connection = match DoH3ServerConnectionContainer::new(
                DoH3Server {
                    domain_name: server_name.clone(),
                    resolved_address: resolved_socket_address,
                },
                Token(index as usize),
            ) {
                Ok(value) => value,
                Err(error) => {
                    error!("new: Failed to create DoH3ServerConnection! - {:?}", error);
                    continue;
                }
            };

            connections.insert(server_name, connection);
        }

        if connections.is_empty() {
            error!("new: No valid servers provided!");
            return Err(DoH3BackendError::ConfigurationFailure);
        }

        return Ok(Self {
            connections,
            input_buffer: [0; Self::INPUT_BUFFER_SIZE],
            output_buffer: [0; Self::OUTPUT_BUFFER_SIZE],
            unspecified_bind_address: SocketAddr::new(
                std::net::IpAddr::V6(Ipv6Addr::UNSPECIFIED),
                0,
            ),
        });
    }
}

fn hex_dump(buffer: &[u8]) -> String {
    let dump_strings: Vec<String> = buffer.iter().map(|b| format!("{b:02x}")).collect();
    dump_strings.join("")
}

impl DnsBackend for DoH3Backend {
    fn get_max_events_count(&self) -> usize {
        return self.connections.len() * 1024;
    }

    fn get_poll_timeout(&self) -> Option<Duration> {
        let mut timeout: Option<Duration> = None;
        for (_, connection) in &self.connections {
            if let Some(session) = &connection.active_session {
                for request in connection.queued_packets.iter() {
                    match Instant::now().checked_duration_since(request.send_info.at) {
                        Some(duration) => {
                            if let Some(existing_timeout) = timeout {
                                timeout = Some(duration.min(existing_timeout));
                            } else {
                                timeout = Some(duration);
                            }
                        }

                        None => return None,
                    }
                }

                match session.client_connection.timeout() {
                    Some(duration) => {
                        if !duration.is_zero() {
                            if let Some(existing_timeout) = timeout {
                                timeout = Some(duration.min(existing_timeout));
                            } else {
                                timeout = Some(duration);
                            }
                        }
                    }

                    None => return None,
                }
            }
        }
        return timeout;
    }

    fn register_sources(&mut self, poll: &mut Poll) -> usize {
        let mut registered_sources = 0;
        for (_, connection) in self.connections.iter_mut() {
            if let Some(session) = &mut connection.active_session {
                if session.socket_registered {
                    registered_sources += 1;
                    continue;
                }

                if let Some(socket) = &mut session.socket {
                    if let Err(error) =
                        poll.registry()
                            .register(socket, connection.token, Interest::READABLE)
                    {
                        if error.kind() == std::io::ErrorKind::AlreadyExists {
                            trace!("register_sources: Socket already registered! - {:?}", error);
                            registered_sources += 1;
                        } else {
                            error!("register_sources: Failed to register socket! - {:?}", error);
                        }
                    } else {
                        registered_sources += 1;
                    }
                    session.socket_registered = true;
                }
            }
        }
        return registered_sources;
    }

    fn forward_packet(
        &mut self,
        android_vpn_service: &Box<dyn AdVpnCallback>,
        packet: &[u8],
        request_packet: &[u8],
        destination_address: Vec<u8>,
        _: u16,
    ) -> Result<(), DnsBackendError> {
        let destination_server_string = match str::from_utf8(&destination_address) {
            Ok(value) => value,
            Err(error) => {
                error!(
                    "forward_packet: Failed to convert destination address to string! - {:?}",
                    error
                );
                return Err(DnsBackendError::InvalidAddress);
            }
        };

        let connection = match self.connections.get_mut(destination_server_string) {
            Some(value) => value,
            None => {
                error!(
                    "forward_packet: No connection found for server! - {:?}",
                    destination_server_string
                );
                return Err(DnsBackendError::InvalidAddress);
            }
        };

        connection.start_session(
            self.unspecified_bind_address,
            android_vpn_service,
            &mut self.output_buffer,
        )?;

        connection.request_queue.push_back(DoH3Request::new(
            &connection.server.domain_name,
            request_packet,
            packet,
        ));

        return Ok(());
    }

    fn process_events(
        &mut self,
        ad_vpn: &mut AdVpn,
        _events: Vec<&mio::event::Event>,
    ) -> Result<Vec<Box<dyn Source>>, DnsBackendError> {
        let mut sources_to_remove = Vec::<Box<dyn Source>>::new();
        'main: for (server_name, connection) in self.connections.iter_mut() {
            if connection.active_session.is_none() {
                continue;
            }

            connection
                .sent_request_streams
                .retain(|stream_id, request| {
                    if request.creation_time.elapsed().as_secs() > Self::STREAM_TIMEOUT_SECONDS {
                        debug!("process_event: Stream id {} timed out", stream_id);
                        false
                    } else {
                        true
                    }
                });
            trace!(
                "{} has {} requests in queue, {} packets in queue, and {} active requests",
                connection.server.domain_name,
                connection.request_queue.len(),
                connection.queued_packets.len(),
                connection.sent_request_streams.len()
            );

            // Read incoming packets until there is nothing more to read
            if let Some(session) = &mut connection.active_session {
                'read: loop {
                    // If the event loop reported no events, it means that the timeout
                    // has expired, so handle it without attempting to read packets. We
                    // will then proceed with the send loop.
                    if session.client_connection.is_timed_out() {
                        debug!("process_event: Connection timed out, closing...");
                        session.client_connection.on_timeout();
                        break 'read;
                    }

                    let (len, _) = if let Some(socket) = &session.socket {
                        match socket.recv_from(&mut self.input_buffer) {
                            Ok(value) => value,

                            Err(error) => {
                                // There are no more UDP packets to read, so end the read
                                // loop.
                                if error.kind() == std::io::ErrorKind::WouldBlock {
                                    debug!("process_events: recv() would block");
                                    break 'read;
                                }

                                error!("process_events: recv() failed: {:?}", error);
                                connection.end_session(&mut sources_to_remove);
                                continue 'main;
                            }
                        }
                    } else {
                        error!("process_events: No socket found for session!");
                        connection.end_session(&mut sources_to_remove);
                        continue 'main;
                    };

                    let recv_info = quiche::RecvInfo {
                        to: session.local_address,
                        from: connection.server.resolved_address,
                    };

                    // Process potentially coalesced packets.
                    if let Err(error) = session
                        .client_connection
                        .recv(&mut self.input_buffer[..len], recv_info)
                    {
                        error!("process_events: recv failed: {:?}", error);
                        continue 'read;
                    }
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to read packets"
                );
                continue 'main;
            }

            if let Some(session) = &connection.active_session {
                if session.client_connection.is_closed() {
                    info!(
                        "process_events: Client connection closed. Ending session for server - {}",
                        connection.server.domain_name
                    );
                    connection.end_session(&mut sources_to_remove);
                    continue 'main;
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to check if the client connection was closed"
                );
                continue 'main;
            }

            // Create a new HTTP/3 connection once the QUIC connection is established.
            if let Some(session) = &mut connection.active_session {
                if session.client_connection.is_established() && session.http3_connection.is_none()
                {
                    debug!("process_events: Creating HTTP/3 connection");
                    session.http3_connection = match quiche::h3::Connection::with_transport(
                        &mut session.client_connection,
                        &connection.h3_config,
                    ) {
                        Ok(value) => Some(value),
                        Err(error) => {
                            error!(
                                "process_events: Unable to create HTTP/3 connection, check the server's uni stream limit and window size - {:?}",
                                error,
                            );
                            None
                        }
                    };
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to create a new HTTP/3 connection"
                );
                continue 'main;
            }

            // Send HTTP requests once the QUIC connection is established, and until
            // all requests have been sent.
            if let Some(session) = &mut connection.active_session {
                if let Some(http3_connection) = &mut session.http3_connection {
                    'send: while let Some(request) = connection.request_queue.pop_front() {
                        match http3_connection.send_request(
                            &mut session.client_connection,
                            &request.payload,
                            true,
                        ) {
                            Ok(stream_id) => {
                                debug!("process_events: Sent request on stream id {}", stream_id);
                                connection.sent_request_streams.insert(stream_id, request);
                            }

                            Err(error) => {
                                match error {
                                    quiche::h3::Error::Done => trace!(
                                        "process_events: HTTP/3 connection (send) reported \"Done\""
                                    ),
                                    quiche::h3::Error::InternalError => {
                                        error!(
                                            "process_events: Detected internal error in HTTP/3 stack!"
                                        );
                                        connection.end_session(&mut sources_to_remove);
                                        continue 'main;
                                    }
                                    quiche::h3::Error::ExcessiveLoad => {
                                        warn!("process_events: Detected excessive load from peer!");
                                        connection.request_queue.push_front(request);
                                        break 'send;
                                    }
                                    quiche::h3::Error::IdError => {
                                        error!("process_events: Used bad ID!");
                                        connection.request_queue.push_front(request);
                                        break 'send;
                                    }
                                    quiche::h3::Error::StreamCreationError => {
                                        warn!("process_events: Failed to create stream");
                                        connection.request_queue.push_front(request);
                                        break 'send;
                                    }
                                    quiche::h3::Error::ClosedCriticalStream => {
                                        error!(
                                            "process_events: Closed a stream that was critical for the connection!"
                                        );
                                        connection.end_session(&mut sources_to_remove);
                                        continue 'main;
                                    }
                                    quiche::h3::Error::FrameUnexpected => {
                                        error!("process_events: Told to GOAWAY 😔");
                                        connection.end_session(&mut sources_to_remove);
                                        continue 'main;
                                    }
                                    quiche::h3::Error::TransportError(error) => {
                                        match error {
                                            quiche::Error::Done => continue 'send,
                                            quiche::Error::CryptoFail => {
                                                error!(
                                                    "process_events: Cryptographic operation failed!"
                                                );
                                                connection.end_session(&mut sources_to_remove);
                                                continue 'main;
                                            }
                                            quiche::Error::TlsFail => {
                                                error!("process_events: Failed TLS setup!");
                                                connection.end_session(&mut sources_to_remove);
                                                continue 'main;
                                            }
                                            quiche::Error::StreamLimit => {
                                                warn!("process_events: Hit stream limit!");
                                                connection.end_session(&mut sources_to_remove);
                                                continue 'main;
                                            }
                                            quiche::Error::KeyUpdate => {
                                                error!(
                                                    "process_events: Failed to update cryptographic key!"
                                                );
                                                connection.end_session(&mut sources_to_remove);
                                                continue 'main;
                                            }
                                            _ => error!(
                                                "process_events: Got transport error - {:?}",
                                                error
                                            ),
                                        };
                                    }
                                    quiche::h3::Error::StreamBlocked => {
                                        trace!(
                                            "process_events: QUIC connection does not have the capacity for this request. Try again later."
                                        );
                                        connection.request_queue.push_front(request);
                                        break 'send;
                                    }
                                    quiche::h3::Error::RequestRejected => warn!(
                                        "process_events: Server rejected request! - {:?}",
                                        request
                                    ),
                                    _ => error!("process_events: Request send failed: {:?}", error),
                                };
                            }
                        };
                    }
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to send HTTP requests"
                );
                continue 'main;
            }

            if let Some(session) = &mut connection.active_session {
                if let Some(http3_connection) = &mut session.http3_connection {
                    // Process HTTP/3 events.
                    'process: loop {
                        match http3_connection.poll(&mut session.client_connection) {
                            Ok((stream_id, quiche::h3::Event::Headers { list, .. })) => {
                                trace!(
                                    "process_events: Got response headers {:?} on stream id {}",
                                    headers_to_strings(&list),
                                    stream_id
                                );
                            }

                            Ok((stream_id, quiche::h3::Event::Data)) => {
                                while let Ok(read) = http3_connection.recv_body(
                                    &mut session.client_connection,
                                    stream_id,
                                    &mut self.input_buffer,
                                ) {
                                    trace!(
                                        "process_events: Got {} bytes of response data on stream {}",
                                        read, stream_id
                                    );

                                    match connection.sent_request_streams.get(&stream_id) {
                                        Some(request) => {
                                            ad_vpn.handle_dns_response(
                                                &request.request_packet,
                                                &self.input_buffer[..read],
                                            );
                                        }
                                        None => {
                                            error!(
                                                "process_events: No requests exist for a response!"
                                            );
                                        }
                                    };
                                }
                            }

                            Ok((stream_id, quiche::h3::Event::Finished)) => {
                                let request = match connection
                                    .sent_request_streams
                                    .remove(&stream_id)
                                {
                                    Some(v) => v,
                                    None => {
                                        error!(
                                            "process_events: Stream id not found in active streams"
                                        );
                                        continue 'process;
                                    }
                                };
                                info!(
                                    "process_events: Response received for stream {stream_id} in {:?}",
                                    request.creation_time.elapsed()
                                );
                            }

                            Ok((stream_id, quiche::h3::Event::Reset(error))) => {
                                if let Some(request) =
                                    connection.sent_request_streams.remove(&stream_id)
                                {
                                    error!(
                                        "process_events: Request {:?} was reset by peer with {}, closing...",
                                        request, error,
                                    );
                                }
                            }

                            Ok((_, quiche::h3::Event::PriorityUpdate)) => unreachable!(),

                            Ok((_, quiche::h3::Event::GoAway)) => {
                                info!("process_events: Told to GOAWAY 😔");
                                connection.end_session(&mut sources_to_remove);
                                continue 'main;
                            }

                            Err(quiche::h3::Error::Done) => break 'process,

                            Err(error) => {
                                error!("process_events: HTTP/3 processing failed: {:?}", error);
                                break 'process;
                            }
                        }
                    }
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to process HTTP/3 events"
                );
                continue 'main;
            }

            // Generate outgoing QUIC packets and send them on the UDP socket, until
            // quiche reports that there are no more packets to be sent.
            if let Some(session) = &mut connection.active_session {
                connection.queued_packets.retain(|packet| {
                    if let Some(duration) = packet.send_info.at.checked_duration_since(Instant::now()) {
                        trace!("process_events: Must wait an additional {}ms before sending", duration.as_millis());
                        return true;
                    }

                    if packet.send_info.at.elapsed().as_secs() > Self::STREAM_TIMEOUT_SECONDS {
                        trace!("process_events: Dropping queued packet due to timeout");
                        return false;
                    }

                    if let Some(socket) = &session.socket {
                        if let Err(error) =
                            socket.send_to(&packet.buffer, packet.send_info.to)
                        {
                            if error.kind() == std::io::ErrorKind::WouldBlock {
                                debug!("process_events: send() would block");
                                return true;
                            }

                            error!("process_events: send() on waiting packet failed: {:?}", error);
                        }
                    }

                    trace!("process_events: Dropping queued packet due to send failure");
                    return false;
                });

                'write: loop {
                    let (write, send_info) =
                        match session.client_connection.send(&mut self.output_buffer) {
                            Ok(v) => v,

                            Err(quiche::Error::Done) => {
                                trace!("process_events: Done writing");
                                break 'write;
                            }

                            Err(error) => {
                                error!("process_events: Send failed: {:?}", error);
                                session.client_connection.close(false, 0x1, b"fail").ok();
                                break 'write;
                            }
                        };

                    if let Some(duration) = send_info.at.checked_duration_since(Instant::now()) {
                        trace!("process_events: Waiting for {}ms before sending packet", duration.as_millis());
                        connection.queued_packets.push(QueuedDoH3Packet { send_info, buffer: self.output_buffer[..write].to_vec() });
                        continue 'write;
                    }
                    debug!("process_events: Sending packet - {:?}", send_info);

                    if let Some(socket) = &session.socket {
                        if let Err(error) =
                            socket.send_to(&self.output_buffer[..write], send_info.to)
                        {
                            if error.kind() == std::io::ErrorKind::WouldBlock {
                                debug!("process_events: send() would block");
                                break 'write;
                            }

                            error!("process_events: send() failed: {:?}", error);
                            connection.end_session(&mut sources_to_remove);
                            continue 'main;
                        }
                    }
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to write packets"
                );
                continue 'main;
            }

            if let Some(session) = &connection.active_session {
                if session.client_connection.is_closed() {
                    info!(
                        "process_events: Client connection closed. Ending session for server - {}",
                        connection.server.domain_name
                    );
                    connection.end_session(&mut sources_to_remove);
                    continue 'main;
                }
            } else {
                warn!(
                    "process_events: No active session found for {server_name} when attempting to check if the client connection was closed"
                );
                continue 'main;
            }
        }
        return Ok(sources_to_remove);
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
    /// 4. The controller's event file descriptor may be updated during a loop iteration which will unblock the poller and then we'll return from the loop.
    /// Alternatively, we may run into a problem during the loop where we'll return a [VpnError] which will appear as an exception in Kotlin.
    fn run(
        &mut self,
        android_vpn_callback: Box<dyn AdVpnCallback>,
        block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
        rule_database: Arc<RuleDatabase>,
    ) -> Result<VpnResult, VpnError> {
        let mut packet = vec![0u8; i16::MAX as usize];

        let (vpn_fd, dns_servers) = match android_vpn_callback.configure() {
            VpnConfigurationResult::NoNetwork => {
                error!("run: No network available");
                return Result::Err(VpnError::NoNetwork);
            }
            VpnConfigurationResult::BuilderFailure => {
                error!("run: Failed to configure VPN");
                return Result::Err(VpnError::ConfigurationFailure);
            }
            VpnConfigurationResult::InvalidDnsServer => {
                error!("run: No valid DNS servers found");
                return Result::Err(VpnError::InvalidDnsServer);
            }
            VpnConfigurationResult::Success(fd, servers) => (fd, servers),
        };

        let is_doh3 = dns_servers.iter().any(|server| match server.address_type {
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
                        panic!("run: Failed to build quiche config! This should never happen!")
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
                        match &server.address_type {
                            NativeDnsServerType::DoH3(server_name) => {
                                Some(server_name.clone().into_bytes())
                            }
                            NativeDnsServerType::Standard => None,
                        }
                    } else {
                        Some(server.address.clone())
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
                packet.as_mut_slice(),
            ) {
                Ok(result) => match result {
                    VpnResult::Continuing => continue,
                    _ => return Ok(result),
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
        debug!("do_one: Polling {} sources(s) with timeout {:?}", backend_sources + 2, timeout);
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

        match backend.process_events(self, events_to_process) {
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
            self.read_packet_from_device(vpn_file, backend, dns_packet_proxy, packet)?;
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
    socket_registered: bool,
    packet: Vec<u8>,
    creation_time: u128,
}

impl WaitingOnSocketPacket {
    fn new(socket: UdpSocket, packet: Vec<u8>) -> Self {
        Self {
            socket,
            socket_registered: false,
            packet,
            creation_time: get_epoch_millis(),
        }
    }

    fn age_seconds(&self) -> u128 {
        (get_epoch_millis() - self.creation_time) / 1000
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
            Err(error) => {
                error!(
                    "initialize: Failed to get write lock for data - {:?}",
                    error
                );
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
            Err(error) => {
                error!(
                    "is_blocked: Failed to get read lock for hosts - {:?}",
                    error
                );
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
            Err(error) => {
                error!(
                    "load_file: Error while reading {} after {} lines - {:?}",
                    &host.data, count, error
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
    block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
    rule_database: Arc<RuleDatabase>,
    upstream_dns_servers: Vec<Vec<u8>>,
    negative_cache_record: ResourceRecord<'a>,
}

impl<'a> DnsPacketProxy<'a> {
    const INVALID_HOSTNAME: &'static str = "dnsnet.dnsnet.invalid.";
    const NEGATIVE_CACHE_TTL_SECONDS: u32 = 5;

    fn new(
        android_vpn_callback: &'a Box<dyn AdVpnCallback>,
        block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
        rule_database: Arc<RuleDatabase>,
        upstream_dns_servers: Vec<Vec<u8>>,
    ) -> Self {
        let name = match Name::new(Self::INVALID_HOSTNAME) {
            Ok(value) => value,
            Err(error) => {
                panic!("Failed to parse our invalid hostname! - {:?}", error);
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

    /// Parses a packet, extracts a DNS request, and forwards it to the real DNS server if it's allowed
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
                debug!("handle_dns_request: IP packet did not contain UDP payload");
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
            Err(error) => {
                warn!(
                    "handle_dns_request: Discarding non-DNS or invalid packet - {:?}",
                    error
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

            if let Some(block_logger) = &self.block_logger_callback {
                block_logger.log(dns_query_name.clone(), true);
            }

            if let Err(error) = backend.forward_packet(
                &self.android_vpn_callback,
                udp_packet.payload(),
                packet_data,
                translated_destination_address,
                destination_port,
            ) {
                error!("handle_dns_request: Failed to forward packet - {:?}", error);
            }
        } else {
            info!("handle_dns_request: DNS Name {} blocked!", dns_query_name);

            if let Some(block_logger) = &self.block_logger_callback {
                block_logger.log(dns_query_name.clone(), false);
            }

            dns_packet.set_flags(PacketFlag::RESPONSE);
            *dns_packet.rcode_mut() = simple_dns::RCODE::NoError;
            dns_packet
                .additional_records
                .push(self.negative_cache_record.clone());

            let mut wire = Vec::<u8>::new();
            if let Err(error) = dns_packet.write_to(&mut wire) {
                error!("Failed to write DNS packet to wire! - {:?}", error);
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
