/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::collections::VecDeque;
use std::os::fd::AsRawFd;
use std::{
    net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6},
    time::Duration,
};

use mio::{Interest, Poll, Token, event::Source, net::UdpSocket};

use crate::backend::DnsBackendError;
use crate::backend::{DnsResponseHandler, SocketProtector};
use crate::util::get_epoch;

use log::{debug, error, warn};

use super::DnsBackend;

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
            creation_time: get_epoch().as_millis(),
        }
    }

    fn age_seconds(&self) -> u128 {
        (get_epoch().as_millis() - self.creation_time) / 1000
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

pub struct StandardDnsBackend {
    wosp_list: WospList,
    response_packet: Vec<u8>,
    unspecified_bind_address: SocketAddr,
}

impl StandardDnsBackend {
    const DNS_RESPONSE_PACKET_SIZE: usize = 1024;

    pub fn new() -> Self {
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
        socket_protector: &Box<&dyn SocketProtector>,
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
        if !socket_protector.protect_fd(socket.as_raw_fd()) {
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
                Err(DnsBackendError::SocketFailure)
            }
        };
    }

    fn process_events(
        &mut self,
        response_handler: &mut Box<&mut dyn DnsResponseHandler>,
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
                            response_handler
                                .handle(&wosp.packet, &mut self.response_packet[..size]);
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
