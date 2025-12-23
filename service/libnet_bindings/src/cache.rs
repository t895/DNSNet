/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::sync::RwLock;

use net::cache::{DnsCache, DnsCacheEntry, SerializableDnsCache};
use simple_dns::Packet;

#[derive(uniffi::Object)]
pub struct DnsCacheBinding {
    cache: RwLock<DnsCache>,
}

impl DnsCacheBinding {
    pub fn new() -> Self {
        Self {
            cache: RwLock::new(DnsCache::new()),
        }
    }

    pub fn get_packet(&self, packet: Packet<'_>) -> Option<Vec<u8>> {
        match self.cache.write() {
            Ok(mut cache) => cache.get_packet(packet),
            Err(error) => {
                error!(
                    "get_packet: Failed to acquire write lock to cache! - {:?}",
                    error
                );
                return None;
            }
        }
    }

    pub fn put_packet(&self, packet: &[u8]) {
        match self.cache.write() {
            Ok(mut cache) => cache.put_packet(packet),
            Err(error) => {
                error!(
                    "put_packet: Failed to acquire write lock to cache! - {:?}",
                    error
                );
            }
        }
    }

    pub fn put_answer(&self, host_name: &str, address: &[u8]) {
        match self.cache.write() {
            Ok(mut cache) => cache.put_answer(host_name, address),
            Err(error) => {
                error!(
                    "put_answer: Failed to acquire write lock to cache! - {:?}",
                    error
                );
            }
        }
    }

    pub fn to_disk_cache(&self) -> SerializableDnsCache {
        match self.cache.read() {
            Ok(cache) => cache.to_disk_cache(),
            Err(error) => {
                error!(
                    "to_disk_cache: Failed to acquire write lock to cache! - {:?}",
                    error
                );
                SerializableDnsCache::new()
            }
        }
    }

    pub fn get(&self, host_name: &str) -> Option<DnsCacheEntry> {
        match self.cache.write() {
            Ok(mut cache) => cache.get(host_name),
            Err(error) => {
                error!(
                    "to_disk_cache: Failed to acquire write lock to cache! - {:?}",
                    error
                );
                None
            }
        }
    }
}

impl From<SerializableDnsCache> for DnsCacheBinding {
    fn from(value: SerializableDnsCache) -> Self {
        DnsCacheBinding {
            cache: RwLock::new(value.to_live_cache()),
        }
    }
}
