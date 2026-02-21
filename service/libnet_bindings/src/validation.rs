/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::{
    io::{Read, Write},
    net::IpAddr,
    str::FromStr,
    sync::{Arc, RwLock},
    thread,
    time::Duration,
};

use log::{debug, error, info, trace, warn};
use mio::{
    Events, Interest, Poll, Token,
    unix::{SourceFd, pipe},
};

use crate::{VpnResultBinding, cache::DnsCacheBinding, vpn::VpnControllerBinding};

#[derive(uniffi::Enum, Clone)]
pub enum NativeDnsServer {
    DoH3(Vec<u8>, String, Option<String>),
    Standard(Vec<u8>),
}

#[derive(uniffi::Object)]
pub struct NativeDnsServerContainer {
    pub server: NativeDnsServer,
}

#[uniffi::export]
impl NativeDnsServerContainer {
    #[uniffi::constructor]
    pub fn new(server: NativeDnsServer) -> Self {
        Self { server }
    }

    pub fn get_address(&self) -> Vec<u8> {
        match &self.server {
            NativeDnsServer::DoH3(address, _, _) => address,
            NativeDnsServer::Standard(address) => address,
        }.clone()
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum ValidateDnsError {
    #[error("Failed to resolve host names")]
    ResolveFailure,

    #[error("Failed to parse IPv4/IPv6 address")]
    ParseFailure,

    #[error("Received failure from VpnController")]
    ControllerFailure,
}

#[derive(uniffi::Enum)]
pub enum ValidateDnsResult {
    Success(Vec<Arc<NativeDnsServerContainer>>),
    Interrupted(VpnResultBinding),
}

struct DnsRequester {
    host_name: String,
    path: Option<String>,
    resolved_address: Option<Vec<u8>>,
    result_id: u8,
}

impl DnsRequester {
    fn new(
        host_name: String,
        path: Option<String>,
        pipe: Arc<RwLock<pipe::Sender>>,
        result_id: u8,
    ) -> Self {
        let requester = DnsRequester {
            host_name: host_name.to_string(),
            path,
            resolved_address: None,
            result_id,
        };

        thread::spawn(move || {
            let failure_buffer = vec![0; 8];
            let url = match url::Url::parse(format!("https://{host_name}").as_str()) {
                Ok(value) => value,
                Err(error) => {
                    error!("new: Failed to parse URL! - {:?}", error);
                    if let Ok(mut sender) = pipe.write() {
                        let _ = sender.write(&failure_buffer);
                    }
                    return;
                }
            };

            let socket_addresses = match url.socket_addrs(|| Some(53)) {
                Ok(value) => value,
                Err(error) => {
                    error!(
                        "DnsRequester::new: Failed to resolve socket addresses! - {:?}",
                        error
                    );
                    if let Ok(mut sender) = pipe.write() {
                        let _ = sender.write(&failure_buffer);
                    }
                    return;
                }
            };
            if socket_addresses.is_empty() {
                error!("DnsRequester::new: Received no socket addresses!");
                if let Ok(mut sender) = pipe.write() {
                    let _ = sender.write(&failure_buffer);
                }
                return;
            }

            let result_address = socket_addresses.first().unwrap().ip();
            debug!(
                "DnsRequester::new: Got result address - {:?}",
                result_address
            );
            let mut output_buffer = match result_address {
                IpAddr::V4(ipv4_addr) => ipv4_addr.octets().to_vec(),
                IpAddr::V6(ipv6_addr) => ipv6_addr.octets().to_vec(),
            };
            output_buffer.insert(0, result_id);

            match result_address {
                IpAddr::V4(_) => output_buffer.insert(0, 2 + 4),
                IpAddr::V6(_) => output_buffer.insert(0, 2 + 16),
            }

            if let Ok(mut sender) = pipe.write() {
                if let Err(error) = sender.write(&output_buffer) {
                    error!(
                        "DnsRequester::new: Failed to write result to buffer! - {:?}",
                        error
                    );
                }
            }
        });

        return requester;
    }
}

#[uniffi::export]
pub fn validate_dns_servers(
    vpn_controller: Arc<VpnControllerBinding>,
    dns_cache: Arc<DnsCacheBinding>,
    ipv6_support: bool,
    is_doh3: bool,
    user_servers: Vec<String>,
) -> Result<ValidateDnsResult, ValidateDnsError> {
    let mut validated_servers = Vec::<Arc<NativeDnsServerContainer>>::new();
    let mut dns_requesters = Vec::<DnsRequester>::new();
    let (sender_pipe, mut receiver_pipe) = match pipe::new() {
        Ok(value) => value,
        Err(error) => {
            error!("validate_dns_servers: Failed to create pipe! - {:?}", error);
            return Err(ValidateDnsError::ResolveFailure);
        }
    };
    let sender_holder = Arc::new(RwLock::new(sender_pipe));
    for (index, unvalidated_server) in user_servers.iter().enumerate() {
        if !is_doh3 {
            match IpAddr::from_str(&unvalidated_server) {
                Ok(value) => {
                    match value {
                        IpAddr::V4(ipv4_addr) => {
                            debug!("validate_dns_server: Validated {}", ipv4_addr);
                            validated_servers.push(Arc::new(NativeDnsServerContainer::new(
                                NativeDnsServer::Standard(ipv4_addr.octets().to_vec()),
                            )));
                        }
                        IpAddr::V6(ipv6_addr) => {
                            if ipv6_support {
                                debug!("validate_dns_server: Validated {}", ipv6_addr);
                                validated_servers.push(Arc::new(NativeDnsServerContainer::new(
                                    NativeDnsServer::Standard(ipv6_addr.octets().to_vec()),
                                )));
                            }
                        }
                    }
                }
                Err(error) => debug!(
                    "validate_dns_servers: Could not parse {unvalidated_server} - {:?}",
                    error
                ),
            };
            continue;
        }

        let mut stripped_server = unvalidated_server
            .strip_prefix("https://")
            .unwrap_or(&unvalidated_server);

        if stripped_server.is_empty() {
            error!(
                "validate_dns_servers: Rejecting invalid DoH3 server name - {unvalidated_server}"
            );
            continue;
        }

        let path = match stripped_server.find("/") {
            Some(index) => {
                let path = stripped_server[index..].to_string();
                stripped_server = stripped_server.strip_suffix(&path).unwrap_or(stripped_server);
                if path == "/" || path == "/dns-query" {
                    None
                } else {
                    Some(path)
                }
            },
            None => None,
        };

        if let Some(entry) = dns_cache.get(stripped_server) {
            match entry.ip_record {
                IpAddr::V4(ipv4_addr) => {
                    validated_servers.push(Arc::new(NativeDnsServerContainer::new(
                        NativeDnsServer::DoH3(
                            ipv4_addr.octets().to_vec(),
                            stripped_server.to_string(),
                            path,
                        ),
                    )));
                }
                IpAddr::V6(ipv6_addr) => {
                    validated_servers.push(Arc::new(NativeDnsServerContainer::new(
                        NativeDnsServer::DoH3(
                            ipv6_addr.octets().to_vec(),
                            stripped_server.to_string(),
                            path,
                        ),
                    )));
                }
            }
            continue;
        }

        let dns_requester = DnsRequester::new(
            stripped_server.to_owned(),
            path,
            sender_holder.clone(),
            index as u8,
        );

        dns_requesters.push(dns_requester);
    }

    if dns_requesters.is_empty() {
        if validated_servers.len() > 0 {
            return Ok(ValidateDnsResult::Success(validated_servers));
        } else {
            return Err(ValidateDnsError::ParseFailure);
        }
    }

    let mut poll = match Poll::new() {
        Ok(value) => value,
        Err(error) => {
            error!(
                "validate_dns_servers: Failed to create poller! - {:?}",
                error
            );
            return Err(ValidateDnsError::ResolveFailure);
        }
    };
    let mut events = Events::with_capacity(dns_requesters.len());

    if let Err(error) = poll
        .registry()
        .register(&mut receiver_pipe, Token(0), Interest::READABLE)
    {
        error!(
            "validate_dns_servers: Failed to register socket to poller! - {:?}",
            error
        );
        return Err(ValidateDnsError::ResolveFailure);
    };

    let event_fd = match vpn_controller.get_event_fd() {
        Some(fd) => fd,
        None => {
            error!("run: Failed to get event fd from controller!");
            return Result::Err(ValidateDnsError::ControllerFailure);
        }
    };

    if let Err(error) = poll.registry().register(
        &mut SourceFd(&event_fd),
        Token(usize::MAX),
        Interest::READABLE,
    ) {
        error!(
            "validate_dns_servers: Failed to register vpn controller to poller! - {:?}",
            error
        );
        return Err(ValidateDnsError::ResolveFailure);
    };

    let mut responses = 0;
    let mut input_buffer = vec![0u8; 512];
    'requesters: while dns_requesters
        .iter()
        .any(|dns_requester| dns_requester.resolved_address.is_none())
        && responses != dns_requesters.len()
    {
        if let Err(error) = poll.poll(&mut events, Some(Duration::from_secs(5))) {
            error!("validate_dns_servers: Poller failed! - {:?}", error);
            return Err(ValidateDnsError::ResolveFailure);
        }

        if events.is_empty() {
            warn!("validate_dns_servers: Timed out while waiting for DNS result!");
            break 'requesters;
        }

        for event in events.iter() {
            if event.token().0 == usize::MAX {
                info!("validate_dns_servers: VPN controller interrupted the DNS poller");
                let stop_result = if let Some(result) = vpn_controller.get_stop_result() {
                    result
                } else {
                    VpnResultBinding::Reconnecting
                };
                return Ok(ValidateDnsResult::Interrupted(stop_result));
            }

            'read: loop {
                let read: usize = match receiver_pipe.read(&mut input_buffer) {
                    Ok(value) => value,
                    Err(error) => {
                        if error.kind() == std::io::ErrorKind::WouldBlock {
                            trace!("validate_dns_servers: read() would block");
                            break 'read;
                        }
                        error!(
                            "validate_dns_servers: Failed to read from pipe! - {:?}",
                            error
                        );
                        break 'read;
                    }
                };
                responses += 1;

                let result_slice = &input_buffer[..read];
                if read == 8 && result_slice.into_iter().all(|&byte| byte == 0) {
                    error!("validate_dns_servers: Got invalid result from pipe");
                    continue 'read;
                }

                if read == 0 {
                    error!("validate_dns_servers: Got invalid result from pipe");
                    continue 'read;
                }

                let mut current_index = 0;
                'process: loop {
                    let result_size = match result_slice.get(current_index) {
                        Some(value) => *value as usize,
                        None => {
                            error!("validate_dns_servers: Could not get result_size!");
                            continue 'read;
                        }
                    };

                    let current_result_slice =
                        match result_slice.get(current_index..current_index + result_size) {
                            Some(value) => value,
                            None => {
                                error!("validate_dns_servers: Could not get current_result_size!");
                                continue 'read;
                            }
                        };

                    let result_id = match current_result_slice.get(1) {
                        Some(value) => *value,
                        None => {
                            error!("validate_dns_servers: Could not get result_id!");
                            continue 'read;
                        }
                    };

                    let dns_requester = match dns_requesters
                        .iter_mut()
                        .find(|dns_requester| dns_requester.result_id == result_id)
                    {
                        Some(value) => value,
                        None => {
                            error!(
                                "validate_dns_servers: Could not find requester for result id {result_id}"
                            );
                            continue 'read;
                        }
                    };
                    dns_requester.resolved_address = Some(current_result_slice[2..].to_vec());

                    current_index += result_size;
                    if current_index > result_slice.len() - 1 {
                        break 'process;
                    }
                }
            }
        }
    }

    for dns_requester in dns_requesters.iter_mut() {
        if let Some(address) = dns_requester.resolved_address.take() {
            dns_cache.put_answer(&dns_requester.host_name, &address);
            validated_servers.push(Arc::new(NativeDnsServerContainer::new(
                NativeDnsServer::DoH3(
                    address,
                    dns_requester.host_name.clone(),
                    dns_requester.path.clone(),
                ),
            )));
        } else {
            trace!(
                "validate_dns_servers: No resolved address for {}",
                dns_requester.host_name
            );
        }
    }

    return Ok(ValidateDnsResult::Success(validated_servers));
}
