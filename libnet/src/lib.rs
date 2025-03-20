use std::{
    collections::{HashMap, VecDeque},
    fs::File,
    io::{self, BufRead, Read, Write},
    mem::{self, MaybeUninit},
    net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6},
    os::fd::{AsRawFd, FromRawFd, RawFd},
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
use polling::{Event, Events, Poller};
use simple_dns::{Name, PacketFlag, ResourceRecord, rdata::RData};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

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
    upstream_dns_servers: Vec<Vec<u8>>,
    vpn_fd: i32,
    vpn_controller: Arc<VpnController>,
    rule_database: Arc<RuleDatabase>,
) -> Result<VpnResult, VpnError> {
    let mut vpn = AdVpn::new(vpn_fd, vpn_controller);
    let result = vpn.run(
        ad_vpn_callback,
        block_logger_callback,
        rule_database,
        upstream_dns_servers,
    );
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
    match udp_builder.write(&mut result, &udp_payload) {
        Ok(_) => {}
        Err(e) => {
            error!("build_packet: Failed to build packet! - {:?}", e);
            return None;
        }
    };
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

    match generic_request_packet.get_ipv4_header() {
        Some(header) => {
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
        None => {}
    };

    match generic_request_packet.get_ipv6_header() {
        Some(header) => {
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
        None => {}
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

    #[error("Failed to set up polling for a socket file descriptor")]
    SocketPollFailure,

    #[error("Failed to write to the tunnel file descriptor")]
    TunnelWriteFailure,

    #[error("Failed to read from the tunnel file descriptor")]
    TunnelReadFailure,

    #[error("Watchdog timed out")]
    Timeout,
}

/// Callback interface to be implemented by a Kotlin class and then passed into the main loop
#[uniffi::export(callback_interface)]
pub trait AdVpnCallback: Send + Sync {
    fn protect_raw_socket_fd(&self, socket_fd: i32) -> bool;

    fn notify(&self, native_status: i32);
}

/// Main struct that holds the state of the VPN and runs the main loop
struct AdVpn {
    vpn_file: File,
    vpn_controller: Arc<VpnController>,
    device_writes: VecDeque<Vec<u8>>,
    wosp_list: WospList,
    ipv6_unspecified: SocketAddrV6,
}

impl AdVpn {
    /// Key for the VPN event in the poller
    const VPN_EVENT_KEY: usize = usize::MAX - 1;

    const DNS_RESPONSE_PACKET_SIZE: usize = 1024;

    fn new(vpn_fd: RawFd, vpn_controller: Arc<VpnController>) -> Self {
        let vpn_file = unsafe { File::from_raw_fd(vpn_fd) };

        AdVpn {
            vpn_file,
            vpn_controller,
            device_writes: VecDeque::new(),
            wosp_list: WospList::new(),
            ipv6_unspecified: SocketAddrV6::new(Ipv6Addr::UNSPECIFIED, 0, 0, 0),
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
        upstream_dns_servers: Vec<Vec<u8>>,
    ) -> Result<VpnResult, VpnError> {
        let mut packet = vec![0u8; i16::MAX as usize];

        let mut dns_packet_proxy = DnsPacketProxy::new(
            &android_vpn_callback,
            block_logger_callback,
            rule_database,
            upstream_dns_servers,
        );

        let poller = match Poller::new() {
            Ok(value) => value,
            Err(e) => {
                error!("do_one: Failed to create poller! - {:?}", e);
                return Result::Err(VpnError::TunnelPollFailure);
            }
        };
        unsafe {
            match poller.add(
                self.vpn_controller.event_fd,
                Event::readable(usize::MAX - 2),
            ) {
                Ok(_) => {}
                Err(e) => {
                    error!("run: Failed to register signal descriptor! - {:?}", e);
                    return Result::Err(VpnError::TunnelPollFailure);
                }
            };
        }
        let mut events = Events::new();

        android_vpn_callback.notify(VpnStatus::Running as i32);
        loop {
            match self.do_one(
                &poller,
                &mut events,
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
        poller: &Poller,
        events: &mut Events,
        dns_packet_proxy: &mut DnsPacketProxy,
        packet: &mut [u8],
    ) -> Result<VpnResult, VpnError> {
        unsafe {
            match poller.add_with_mode(
                self.vpn_file.as_raw_fd(),
                Event::new(Self::VPN_EVENT_KEY, true, !self.device_writes.is_empty())
                    .with_priority(),
                polling::PollMode::Edge,
            ) {
                Ok(_) => {}
                Err(e) => {
                    error!("do_one: Failed to add VPN descriptor to poller! - {:?}", e);
                    return Result::Err(VpnError::TunnelPollFailure);
                }
            };
        }

        let mut waiting_sockets = 1;
        let mut bad_sockets = Vec::<usize>::new();
        for (index, wosp) in self.wosp_list.list.iter().enumerate() {
            unsafe {
                match poller.add(&wosp.socket, Event::readable(wosp.time as usize)) {
                    Ok(_) => waiting_sockets += 1,
                    Err(e) => {
                        if e.kind() == std::io::ErrorKind::AlreadyExists {
                            waiting_sockets += 1;
                        } else {
                            warn!(
                                "do_one: Failed to add socket {:?} to poller! - {:?}",
                                wosp, e
                            );
                            bad_sockets.push(index);
                        }
                    }
                };
            }
        }
        for index in bad_sockets {
            self.wosp_list.list.remove(index);
        }

        debug!("do_one: Polling {} socket(s)", waiting_sockets);
        match poller.wait(events, None) {
            Ok(events_length) => info!("do_one: Found {} event(s)", events_length),
            Err(e) => {
                debug!("do_one: Poll timed out - {:?}", e);
                return Result::Err(VpnError::Timeout);
            }
        };

        match self.vpn_controller.get_stop_result() {
            Some(result) => {
                info!("do_one: Told to stop");
                return Ok(result);
            }
            None => {}
        }

        // Need to do this before reading from the device, otherwise a new insertion there could
        // invalidate one of the sockets we want to read from either due to size or time out
        // constraints
        let mut read_from_device = false;
        let mut write_to_device = false;
        let mut wosps_to_process = Vec::<WaitingOnSocketPacket>::new();
        for event in events.iter() {
            debug!("do_one: Got event {:?}", event);
            if event.key == Self::VPN_EVENT_KEY {
                read_from_device = event.readable;
                write_to_device = event.writable;
            } else {
                let index = self
                    .wosp_list
                    .list
                    .iter()
                    .position(|value| (value.time as usize) == event.key)
                    .unwrap_or(usize::MAX);
                match self.wosp_list.list.remove(index) {
                    Some(wosp) => {
                        debug!("do_one: Read from DNS socket: {:?}", wosp.socket);
                        match poller.delete(&wosp.socket) {
                            Ok(_) => {}
                            Err(e) => {
                                warn!("do_one: Failed to remove socket from poller! - {:?}", e)
                            }
                        };
                        wosps_to_process.push(wosp);
                    }
                    None => {
                        error!(
                            "do_one: Got event for wosp that doesn't exist in list! This should never happen."
                        );
                        return Result::Err(VpnError::SocketPollFailure);
                    }
                };
            }
        }
        events.clear();

        for wosp in wosps_to_process {
            self.handle_raw_dns_response(dns_packet_proxy, wosp);
        }

        if write_to_device {
            self.write_to_device()?;
        }

        if read_from_device {
            self.read_packet_from_device(dns_packet_proxy, packet)?;
        }

        match poller.delete(&self.vpn_file) {
            Ok(_) => {}
            Err(e) => {
                error!("do_one: Failed to remove VPN FD from poller! - {:?}", e);
                return Result::Err(VpnError::TunnelPollFailure);
            }
        };

        return Result::Ok(VpnResult::Continuing);
    }

    /// Writes a packet to the tunnel from the device_writes queue
    fn write_to_device(&mut self) -> Result<(), VpnError> {
        let device_write = match self.device_writes.pop_front() {
            Some(value) => value,
            None => {
                error!("write_to_device: device_writes is empty! This should be impossible");
                return Result::Err(VpnError::TunnelWriteFailure);
            }
        };

        match self.vpn_file.write(&device_write) {
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
        dns_packet_proxy: &mut DnsPacketProxy,
        packet: &mut [u8],
    ) -> Result<(), VpnError> {
        let length = match self.vpn_file.read(packet) {
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

        dns_packet_proxy.handle_dns_request(self, packet);

        return Result::Ok(());
    }

    /// Receives a raw DNS response from a socket and then passes it to the [DnsPacketProxy] to be handled
    fn handle_raw_dns_response(
        &mut self,
        dns_packet_proxy: &DnsPacketProxy,
        wosp: WaitingOnSocketPacket,
    ) {
        let mut response_payload =
            vec![MaybeUninit::<u8>::uninit(); Self::DNS_RESPONSE_PACKET_SIZE];

        match wosp.socket.recv(response_payload.as_mut_slice()) {
            Ok(_) => {
                let initialized_response_payload =
                    unsafe { mem::transmute::<_, Vec<u8>>(response_payload) };
                dns_packet_proxy.handle_dns_response(
                    self,
                    &wosp.packet,
                    &initialized_response_payload,
                );
            }
            Err(e) => {
                warn!(
                    "handle_raw_dns_response: Failed to receive response packet from DNS socket! - {:?}",
                    e
                );
                return;
            }
        };
    }

    /// Forwards a packet to the real DNS server
    fn forward_packet(
        &mut self,
        android_vpn_service: &Box<dyn AdVpnCallback>,
        packet: &[u8],
        request_packet: &[u8],
        destination_address: SocketAddr,
    ) -> bool {
        let socket = match Socket::new_raw(Domain::IPV6, Type::DGRAM, Some(Protocol::UDP)) {
            Ok(value) => value,
            Err(e) => {
                error!("forward_packet: Failed to create socket! - {:?}", e);
                return false;
            }
        };

        match socket.set_nonblocking(true) {
            Ok(_) => {}
            Err(e) => {
                error!(
                    "forward_packet: Failed to set socket to non-blocking! - {:?}",
                    e
                );
                return false;
            }
        }

        // Packets to be sent to the real DNS server will need to be protected from the VPN
        if !android_vpn_service.protect_raw_socket_fd(socket.as_raw_fd()) {
            error!("forward_packet: Failed for protect socket fd!");
            return false;
        }

        let bind_address = SockAddr::from(self.ipv6_unspecified);
        match socket.bind(&bind_address) {
            Ok(_) => debug!("forward_packet: Successfully bound socket - {:?}", socket),
            Err(e) => error!("forward_packet: Failed to bind socket! - {:?}", e),
        };

        let destination_sockaddr = SockAddr::from(destination_address);
        match socket.send_to(packet, &destination_sockaddr) {
            Ok(_) => {
                self.wosp_list
                    .add(WaitingOnSocketPacket::new(socket, request_packet.to_vec()));
                return true;
            }
            Err(e) => {
                warn!("forward_packet: Failed to send message - {:?}", e);
                if e.raw_os_error().is_some() {
                    return Self::eval_socket_error(e.raw_os_error().unwrap());
                }
            }
        }

        return true;
    }

    /// Evaluates whether a socket error is fatal or not
    fn eval_socket_error(error_code: i32) -> bool {
        error!("eval_socket_error: Cannot send message");
        return error_code != libc::ENETUNREACH || error_code != libc::EPERM;
    }

    /// Adds a packet to the device_writes queue
    fn queue_device_write(&mut self, packet: Vec<u8>) {
        self.device_writes.push_back(packet)
    }
}

/// Struct that holds a socket that we're waiting on and it's associated packet.
/// Additionally holds the time that we started waiting on it to see if we need to drop it.
#[derive(Debug)]
struct WaitingOnSocketPacket {
    socket: Socket,
    packet: Vec<u8>,
    time: u128,
}

impl WaitingOnSocketPacket {
    fn new(socket: Socket, packet: Vec<u8>) -> Self {
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
    fn get_host_fd(&self, host: String, mode: String) -> Option<i32>;
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
            return Ok(());
        }
        if self
            .controller
            .should_stop
            .fetch_or(false, std::sync::atomic::Ordering::Relaxed)
        {
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
            match load_item(
                &android_file_helper,
                &self.controller,
                &mut map,
                item,
            ) {
                Ok(_) => {}
                Err(error) => match error {
                    RuleDatabaseError::BadHostFormat => {}
                    RuleDatabaseError::Interrupted => return Err(error),
                    RuleDatabaseError::LockError => {}
                },
            };
        }

        let mut sorted_host_exceptions = host_exceptions
            .iter()
            .filter(|item| item.state != NativeHostState::IGNORE)
            .collect::<Vec<&NativeHost>>();
        sorted_host_exceptions.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for exception in sorted_host_exceptions {
            match add_host(
                &self.controller,
                &mut map,
                &exception.state,
                exception.data.clone(),
            ) {
                Ok(_) => {}
                Err(error) => match error {
                    RuleDatabaseError::BadHostFormat => {}
                    RuleDatabaseError::Interrupted => return Err(error),
                    RuleDatabaseError::LockError => {}
                },
            };
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
            if self.controller.is_initialized() {
                break;
            }
            if !self.controller.is_reloading() {
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

    let mut end_of_line = match line.find('#') {
        Some(index) => index,
        None => line.len(),
    };

    let mut start_of_host = 0;

    match line.find(IPV4_LOOPBACK) {
        Some(index) => {
            start_of_host += index + IPV4_LOOPBACK.len();
        }
        None => {}
    };

    if start_of_host == 0 {
        match line.find(IPV6_LOOPBACK) {
            Some(index) => {
                start_of_host += index + IPV6_LOOPBACK.len();
            }
            None => {}
        }
    }

    if start_of_host == 0 {
        match line.find(NO_ROUTE) {
            Some(index) => {
                start_of_host += index + NO_ROUTE.len();
            }
            None => {}
        }
    }

    if start_of_host >= end_of_line {
        return None;
    }

    while start_of_host < end_of_line && line.chars().nth(start_of_host).unwrap().is_whitespace() {
        start_of_host += 1;
    }

    while start_of_host > end_of_line && line.chars().nth(end_of_line - 1).unwrap().is_whitespace()
    {
        end_of_line -= 1;
    }

    let host = (&line[start_of_host..end_of_line]).to_lowercase();
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

    match android_file_helper.get_host_fd(host.data.clone(), String::from("r")) {
        Some(value) => {
            let file = unsafe { File::from_raw_fd(value) };
            let lines: io::Lines<io::BufReader<File>> = io::BufReader::new(file).lines();
            match load_file(controller, map, &host, lines) {
                Ok(_) => {}
                Err(error) => match error {
                    RuleDatabaseError::BadHostFormat => {}
                    RuleDatabaseError::Interrupted => return Err(error),
                    RuleDatabaseError::LockError => {}
                },
            };
        }
        None => {
            warn!(
                "Failed to open {}. Attempting to add as single host.",
                host.data
            );
            match add_host(controller, map, &host.state, host.data.clone()) {
                Ok(_) => {}
                Err(error) => match error {
                    RuleDatabaseError::BadHostFormat => {}
                    RuleDatabaseError::Interrupted => return Err(error),
                    RuleDatabaseError::LockError => {}
                },
            };
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
                                map.insert(value.to_owned(), (HostnameType::Wildcard, HostnameAction::Deny));
                            }
                            NativeHostState::ALLOW => {
                                map.insert(value.to_owned(), (HostnameType::Wildcard, HostnameAction::Allow));
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
                                            map.insert(value.to_owned(), (HostnameType::Wildcard, HostnameAction::Deny));
                                        }
                                        NativeHostState::ALLOW => {
                                            map.insert(value.to_owned(), (HostnameType::Wildcard, HostnameAction::Allow));
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
                let data = parse_line(value.as_str());
                if data.is_some() {
                    match add_host(controller, map, &host.state, data.unwrap()) {
                        Ok(_) => {}
                        Err(error) => {
                            match error {
                                RuleDatabaseError::BadHostFormat => {}
                                RuleDatabaseError::Interrupted => return Err(error),
                                RuleDatabaseError::LockError => {}
                            };
                        }
                    };
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

    /// Handles a DNS response and forwards it to the tunnel with the translated destination
    fn handle_dns_response(
        &self,
        ad_vpn: &mut AdVpn,
        request_packet: &[u8],
        response_payload: &[u8],
    ) {
        match build_response_packet(request_packet, response_payload) {
            Some(packet) => ad_vpn.queue_device_write(packet),
            None => return,
        };
    }

    /// Parses a DNS request and forwards it to the real DNS server if it's allowed
    fn handle_dns_request(&mut self, ad_vpn: &mut AdVpn, packet_data: &[u8]) {
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

                ad_vpn.forward_packet(
                    &self.android_vpn_callback,
                    udp_packet.payload(),
                    packet_data,
                    std::net::SocketAddr::V4(destination_socket_address),
                );
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

                ad_vpn.forward_packet(
                    &self.android_vpn_callback,
                    udp_packet.payload(),
                    packet_data,
                    std::net::SocketAddr::V6(destination_socket_address),
                );
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
            match dns_packet.write_to(&mut wire) {
                Ok(_) => {}
                Err(e) => {
                    error!("Failed to write DNS packet to wire! - {:?}", e);
                    return;
                }
            };

            self.handle_dns_response(ad_vpn, packet_data, &wire);
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
