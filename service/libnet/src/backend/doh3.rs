/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::os::fd::AsRawFd;
use std::str;
use std::{
    collections::{HashMap, VecDeque},
    net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6},
    time::{Duration, Instant},
};

use base64::{Engine, prelude::BASE64_STANDARD_NO_PAD};
use mio::{Interest, Poll, Token, event::Source, net::UdpSocket};
use quiche::h3::NameValue;
use quiche::{SendInfo, h3::Header};

use crate::backend::{DnsResponseHandler, DnsServer, DnsServerType, SocketProtector};

use log::{debug, error, info, trace, warn};

use super::{DnsBackend, DnsBackendError};

#[derive(Debug)]
pub enum DoH3BackendError {
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

        config.set_max_idle_timeout(DoH3Backend::CONNECTION_TIMEOUT_SECONDS * 1000);
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
        socket_protector: &Box<&dyn SocketProtector>,
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

            if !socket_protector.protect_fd(socket.as_raw_fd()) {
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

pub struct DoH3Backend {
    connections: HashMap<String, DoH3ServerConnectionContainer>,
    input_buffer: [u8; DoH3Backend::INPUT_BUFFER_SIZE],
    output_buffer: [u8; DoH3Backend::OUTPUT_BUFFER_SIZE],
    unspecified_bind_address: SocketAddr,
}

impl DoH3Backend {
    const CONNECTION_TIMEOUT_SECONDS: u64 = 30;
    const ACTIVE_REQUEST_TIMEOUT_SECONDS: u64 = 10;
    const PENDING_PACKET_TIMEOUT_SECONDS: u64 = 10;

    const INPUT_BUFFER_SIZE: usize = u16::MAX as usize;
    const OUTPUT_BUFFER_SIZE: usize = 1350;

    /// Creates a new DoH3Backend with the provided servers. These servers are validated individually by
    /// resolving their addresses. If none of the servers are valid, an error is returned.
    pub fn new(servers: &Vec<DnsServer>) -> Result<Self, DoH3BackendError> {
        let mut connections: HashMap<String, DoH3ServerConnectionContainer> = HashMap::new();
        for (index, server) in servers.iter().enumerate() {
            let server_name = match &server.address_type {
                DnsServerType::DoH3(server_name) => server_name.clone(),
                DnsServerType::Standard => {
                    error!(
                        "new: DoH3 backend was given a standard DNS server! This should never happen!"
                    );
                    continue;
                }
            };

            let address = server.address.clone();
            let resolved_socket_address: SocketAddr = if address.len() == 4 {
                std::net::SocketAddr::V4(SocketAddrV4::new(
                    Ipv4Addr::from(TryInto::<[u8; 4]>::try_into(address).unwrap()),
                    443,
                ))
            } else if address.len() == 16 {
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
                    if let Some(duration) =
                        Instant::now().checked_duration_since(request.send_info.at)
                    {
                        if let Some(existing_timeout) = timeout {
                            timeout = Some(duration.min(existing_timeout));
                        } else {
                            timeout = Some(duration);
                        }
                    }
                }

                if let Some(duration) = session.client_connection.timeout() {
                    if let Some(existing_timeout) = timeout {
                        timeout = Some(duration.min(existing_timeout));
                    } else {
                        timeout = Some(duration);
                    }
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
        socket_protector: &Box<&dyn SocketProtector>,
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
            socket_protector,
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
        response_handler: &mut Box<&mut dyn DnsResponseHandler>,
        _events: Vec<&mio::event::Event>,
    ) -> Result<Vec<Box<dyn Source>>, DnsBackendError> {
        let mut sources_to_remove = Vec::<Box<dyn Source>>::new();
        'main: for (server_name, connection) in self.connections.iter_mut() {
            if connection.active_session.is_none() {
                continue 'main;
            }

            connection
                .sent_request_streams
                .retain(|stream_id, request| {
                    if request.creation_time.elapsed().as_secs()
                        > Self::ACTIVE_REQUEST_TIMEOUT_SECONDS
                    {
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
                    if let Some(timeout) = session.client_connection.timeout() {
                        if timeout.is_zero() {
                            debug!(
                                "process_event: Connection to {} timed out, closing...",
                                connection.server.domain_name
                            );
                            session.client_connection.on_timeout();
                            break 'read;
                        }
                    }

                    let (len, _) = if let Some(socket) = &session.socket {
                        match socket.recv_from(&mut self.input_buffer) {
                            Ok(value) => value,

                            Err(error) => {
                                // There are no more UDP packets to read, so end the read
                                // loop.
                                if error.kind() == std::io::ErrorKind::WouldBlock {
                                    trace!("process_events: recv() would block");
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
                                            response_handler.handle(
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
                                debug!(
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

                            Ok((_prioritized_element_id, quiche::h3::Event::PriorityUpdate)) => {}

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
                    if let Some(duration) =
                        packet.send_info.at.checked_duration_since(Instant::now())
                    {
                        trace!(
                            "process_events: Must wait an additional {}ms before sending",
                            duration.as_millis()
                        );
                        return true;
                    }

                    if packet.send_info.at.elapsed().as_secs()
                        > Self::PENDING_PACKET_TIMEOUT_SECONDS
                    {
                        trace!("process_events: Dropping queued packet due to timeout");
                        return false;
                    }

                    if let Some(socket) = &session.socket {
                        if let Err(error) = socket.send_to(&packet.buffer, packet.send_info.to) {
                            if error.kind() == std::io::ErrorKind::WouldBlock {
                                debug!("process_events: send() would block");
                                return true;
                            }

                            error!(
                                "process_events: send() on waiting packet failed: {:?}",
                                error
                            );
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
                        trace!(
                            "process_events: Waiting for {}ms before sending packet",
                            duration.as_millis()
                        );
                        connection.queued_packets.push(QueuedDoH3Packet {
                            send_info,
                            buffer: self.output_buffer[..write].to_vec(),
                        });
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
