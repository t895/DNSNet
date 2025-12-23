/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use core::str;
use std::sync::Arc;

use simple_dns::{Name, PacketFlag, ResourceRecord, rdata::RData};

use crate::{
    BlockLoggerCallback, RuleDatabaseBinding, Vpn, VpnCallback, VpnError,
    backend::{DnsBackend, DnsBackendError},
    cache::DnsCacheBinding,
    packet::GenericIpPacket,
};

/// Handler for DNS packets that accepts or blocks them based on our [RuleDatabase]
pub struct DnsPacketProxy<'a> {
    android_vpn_callback: &'a Box<dyn VpnCallback>,
    block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
    rule_database: Arc<RuleDatabaseBinding>,
    upstream_dns_servers: Vec<Vec<u8>>,
    negative_cache_record: ResourceRecord<'a>,
}

impl<'a> DnsPacketProxy<'a> {
    const INVALID_HOST_NAME: &'static str = "dnsnet.dnsnet.invalid.";
    const NEGATIVE_CACHE_TTL_SECONDS: u32 = 5;

    pub fn new(
        android_vpn_callback: &'a Box<dyn VpnCallback>,
        block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
        rule_database: Arc<RuleDatabaseBinding>,
        upstream_dns_servers: Vec<Vec<u8>>,
    ) -> Self {
        let name = match Name::new(Self::INVALID_HOST_NAME) {
            Ok(value) => value,
            Err(error) => {
                panic!("Failed to parse our invalid host name! - {:?}", error);
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
    pub fn handle_dns_request(
        &mut self,
        ad_vpn: &mut Vpn,
        backend: &mut Box<dyn DnsBackend>,
        dns_cache: Arc<DnsCacheBinding>,
        packet_data: &[u8],
    ) -> Result<(), VpnError> {
        let packet = match GenericIpPacket::from_ip_packet(packet_data) {
            Some(value) => value,
            None => {
                warn!(
                    "handle_dns_request: Failed to parse packet data - {:?}",
                    packet_data
                );
                return Ok(());
            }
        };

        let udp_packet = match packet.get_udp_packet() {
            Some(value) => value,
            None => {
                debug!("handle_dns_request: IP packet did not contain UDP payload");
                return Ok(());
            }
        };

        let destination_address = match packet.get_destination_address() {
            Some(value) => value,
            None => {
                warn!(
                    "handle_dns_request: Failed to get destination address for packet - {:?}",
                    packet
                );
                return Ok(());
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
                    return Ok(());
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
                return Ok(());
            }
        };

        if dns_packet.questions.is_empty() {
            warn!(
                "handle_dns_request: Discarding DNS packet with no questions - {:?}",
                dns_packet
            );
            return Ok(());
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
                dns_query_name,
                str::from_utf8(&translated_destination_address),
            );

            if let Some(block_logger) = &self.block_logger_callback {
                block_logger.log(dns_query_name.clone(), true);
            }

            // if let Some(cached_response) = dns_cache.get_packet(dns_packet) {
            //     ad_vpn.handle_dns_response(Some(dns_cache), packet_data, &cached_response);
            //     return Ok(());
            // }

            if let Err(error) = backend.forward_packet(
                &self.android_vpn_callback,
                udp_packet.payload(),
                packet_data,
                translated_destination_address,
                destination_port,
            ) {
                error!("handle_dns_request: Failed to forward packet - {:?}", error);
                match error {
                    DnsBackendError::SocketFailure => return Err(VpnError::SocketFailure),
                    _ => return Ok(()),
                }
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
                return Ok(());
            }

            ad_vpn.handle_dns_response(None, packet_data, &wire);
        }
        return Ok(());
    }

    /// Translates the destination address using our upstream servers as configured by the VpnThread
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
