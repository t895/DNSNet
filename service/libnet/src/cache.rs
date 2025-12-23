/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use bincode::{Decode, Encode, config};
use log::{debug, error};
use lru::LruCache;
use simple_dns::rdata::{A, AAAA, RData};
use simple_dns::{Name, Packet, ResourceRecord};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::num::NonZeroUsize;
use std::time::UNIX_EPOCH;
use std::{fs::File, time::SystemTime};

const DEFAULT_CONFIG: config::Configuration = config::standard();

const DEFAULT_DNS_AUTHORITY_TTL: u32 = 604800; // 1 Week

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

#[derive(Hash, PartialEq, Eq)]
pub struct DnsCacheKey {
    host_name: String,
    record_type: DnsRecordType,
}

impl DnsCacheKey {
    pub fn new(host_name: String, record_type: DnsRecordType) -> Self {
        Self {
            host_name,
            record_type,
        }
    }
}

#[derive(Clone, Copy)]
pub struct DnsCacheEntry {
    pub ip_record: IpAddr,
    creation_time: u64,
    original_time_to_live: u32,
}

impl DnsCacheEntry {
    fn new(ip_record: IpAddr, creation_time: u64, time_to_live: u32) -> Self {
        Self {
            ip_record,
            creation_time,
            original_time_to_live: time_to_live,
        }
    }

    fn expiry_time(&self) -> u64 {
        self.creation_time + (self.original_time_to_live as u64)
    }

    fn current_time_to_live(&self) -> u32 {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let elapsed_time = (now - self.creation_time) as u32;
        return if elapsed_time > self.original_time_to_live {
            0
        } else {
            self.original_time_to_live - elapsed_time
        };
    }
}

#[derive(Encode, Decode, Hash, PartialEq, Eq, Clone, Copy, Debug)]
pub enum DnsRecordType {
    A,
    AAAA,
}

pub struct DnsCache {
    cache: LruCache<DnsCacheKey, DnsCacheEntry>,
}

impl DnsCache {
    pub fn new() -> Self {
        Self {
            cache: LruCache::new(NonZeroUsize::new(1_000).unwrap()),
        }
    }

    pub fn get_packet(&mut self, packet: Packet<'_>) -> Option<Vec<u8>> {
        let host_name = packet.questions.first().unwrap().qname.to_string();
        let ipv4_key = DnsCacheKey::new(host_name.clone(), DnsRecordType::A);
        let ipv6_key = DnsCacheKey::new(host_name.clone(), DnsRecordType::AAAA);
        let entry = if let Some(entry) = self.cache.get(&ipv4_key) {
            entry
        } else if let Some(entry) = self.cache.get(&ipv6_key) {
            entry
        } else {
            return None;
        };

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        if entry.expiry_time() > now {
            match entry.ip_record {
                IpAddr::V4(_) => self.cache.pop(&ipv4_key),
                IpAddr::V6(_) => self.cache.pop(&ipv6_key),
            };
            return None;
        }

        let mut response_packet = Packet::new_reply(packet.id());
        let name = match Name::new(&host_name) {
            Ok(name) => name,
            Err(error) => {
                error!(
                    "get_packet: Failed to create Name for cached record! - {:?}",
                    error
                );
                return None;
            }
        };
        let rdata = match entry.ip_record {
            IpAddr::V4(ipv4_addr) => RData::A(A {
                address: ipv4_addr.to_bits(),
            }),
            IpAddr::V6(ipv6_addr) => RData::AAAA(AAAA {
                address: ipv6_addr.to_bits(),
            }),
        };
        response_packet.answers.push(ResourceRecord::new(
            name,
            simple_dns::CLASS::IN,
            entry.current_time_to_live() as u32,
            rdata,
        ));

        let mut out = Vec::<u8>::new();
        if let Err(error) = response_packet.write_to(&mut out) {
            error!(
                "get_packet: Failed to write cached record to vec! - {:?}",
                error
            );
            return None;
        }

        debug!("get_packet: Cache hit for {}", host_name);
        Some(out)
    }

    pub fn put_packet(&mut self, packet: &[u8]) {
        let packet = match Packet::parse(packet) {
            Ok(packet) => packet,
            Err(error) => {
                error!("put_packet: Failed to parse response packet! - {:?}", error);
                return;
            }
        };

        Self::check_records(&mut self.cache, &packet.answers);
        Self::check_records(&mut self.cache, &packet.name_servers);
        Self::check_records(&mut self.cache, &packet.additional_records);
    }

    fn check_records(
        cache: &mut LruCache<DnsCacheKey, DnsCacheEntry>,
        records: &Vec<ResourceRecord>,
    ) {
        if records.is_empty() {
            debug!("check_records: No records");
            return;
        }

        for record in records {
            if record.ttl == 0 {
                debug!(
                    "check_records: Not storing record with a ttl of 0 - {:?}",
                    record.name
                );
                return;
            }

            let host_name = record.name.to_string();
            let ipaddr = match &record.rdata {
                RData::A(a) => IpAddr::V4(Ipv4Addr::from_bits(a.address)),
                RData::AAAA(aaaa) => IpAddr::V6(Ipv6Addr::from_bits(aaaa.address)),
                _ => return,
            };
            let record_type = match ipaddr {
                IpAddr::V4(_) => DnsRecordType::A,
                IpAddr::V6(_) => DnsRecordType::AAAA,
            };
            let key = DnsCacheKey::new(host_name, record_type);
            let entry = DnsCacheEntry::new(ipaddr, now_secs(), record.ttl);
            cache.put(key, entry);
        }
    }

    pub fn put_answer(&mut self, host_name: &str, address: &[u8]) {
        let host_name = host_name.to_string();
        let (key, entry) = if address.len() == 4 {
            let sized_address: &[u8; 4] = address.try_into().unwrap();
            let bits = u32::from_be_bytes(*sized_address);
            let key = DnsCacheKey::new(host_name, DnsRecordType::A);
            let entry = DnsCacheEntry::new(
                IpAddr::V4(Ipv4Addr::from_bits(bits)),
                now_secs(),
                DEFAULT_DNS_AUTHORITY_TTL,
            );
            (key, entry)
        } else if address.len() == 16 {
            let sized_address: &[u8; 16] = address.try_into().unwrap();
            let bits = u128::from_be_bytes(*sized_address);
            let key = DnsCacheKey::new(host_name, DnsRecordType::AAAA);
            let entry = DnsCacheEntry::new(
                IpAddr::V6(Ipv6Addr::from(bits)),
                now_secs(),
                DEFAULT_DNS_AUTHORITY_TTL,
            );
            (key, entry)
        } else {
            error!(
                "put_answer: Got invalid answer! - {} -> {:?}",
                host_name, address
            );
            return;
        };
        self.cache.put(key, entry);
    }

    pub fn to_disk_cache(&self) -> SerializableDnsCache {
        let mut new_cache = SerializableDnsCache {
            cache: Vec::with_capacity(self.cache.len()),
        };
        for (key, value) in self.cache.iter() {
            new_cache.cache.push(SerializableDnsCacheEntry {
                host_name: key.host_name.clone(),
                record_type: key.record_type.clone(),
                ip_record: value.ip_record,
                creation_time: value.creation_time,
                time_to_live: value.original_time_to_live,
            })
        }
        new_cache
    }

    pub fn get(&mut self, host_name: &str) -> Option<DnsCacheEntry> {
        let ipv4_cache_key = DnsCacheKey::new(host_name.to_string(), DnsRecordType::A);
        if let Some(record) = self.cache.get(&ipv4_cache_key) {
            debug!("get: Cache hit for {}", host_name);
            return Some(record.clone());
        }
        let ipv6_cache_key = DnsCacheKey::new(host_name.to_string(), DnsRecordType::AAAA);
        if let Some(record) = self.cache.get(&ipv6_cache_key) {
            debug!("get: Cache hit for {}", host_name);
            return Some(record.clone());
        }
        return None;
    }

    #[allow(dead_code)]
    fn print_cache(
        cache: &mut std::sync::RwLockWriteGuard<'_, LruCache<DnsCacheKey, DnsCacheEntry>>,
    ) {
        debug!("--- START CACHE ---");
        for (key, value) in (*cache).iter() {
            debug!(
                "{}, {:?} ->  {}, {}, {}",
                key.host_name,
                key.record_type,
                value.ip_record,
                value.creation_time,
                value.original_time_to_live
            );
        }
        debug!("--- END CACHE ---");
    }
}

impl From<SerializableDnsCache> for DnsCache {
    fn from(value: SerializableDnsCache) -> Self {
        value.to_live_cache()
    }
}

#[derive(Encode, Decode)]
struct SerializableDnsCacheEntry {
    host_name: String,
    record_type: DnsRecordType,
    ip_record: IpAddr,
    creation_time: u64,
    time_to_live: u32,
}

#[derive(Encode, Decode)]
pub struct SerializableDnsCache {
    cache: Vec<SerializableDnsCacheEntry>,
}

impl SerializableDnsCache {
    pub fn new() -> Self {
        Self { cache: Vec::new() }
    }

    pub fn to_live_cache(self) -> DnsCache {
        let mut new_cache = DnsCache::new();
        for entry in self.cache {
            debug!(
                "to_live_cache: Got cache entry - {}, {:?} -> {}, {}, {}",
                entry.host_name,
                entry.record_type,
                entry.ip_record,
                entry.creation_time,
                entry.time_to_live
            );
            new_cache.cache.put(
                DnsCacheKey {
                    host_name: entry.host_name,
                    record_type: entry.record_type,
                },
                DnsCacheEntry {
                    ip_record: entry.ip_record,
                    creation_time: entry.creation_time,
                    original_time_to_live: entry.time_to_live,
                },
            );
        }
        new_cache
    }

    pub fn write_to(self, file: &mut File) {
        if let Err(error) = bincode::encode_into_std_write(&self, file, DEFAULT_CONFIG) {
            error!(
                "SerializableDnsCache::write_to: Failed to create writer! - {:?}",
                error
            );
        };
    }
}

impl From<&mut File> for SerializableDnsCache {
    fn from(value: &mut File) -> Self {
        match bincode::decode_from_std_read(value, DEFAULT_CONFIG) {
            Ok(result) => result,
            Err(error) => {
                error!(
                    "SerializableDnsCache::from: Failed to deserialize cache from file! - {:?}",
                    error
                );
                SerializableDnsCache::new()
            }
        }
    }
}
