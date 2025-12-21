/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

mod backend;
mod cache;
mod database;
mod packet;
mod proxy;
mod validation;
mod vpn;

use std::{
    fs::File,
    net::{Ipv6Addr, SocketAddr, SocketAddrV6},
    os::fd::FromRawFd,
    str::FromStr,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use android_logger::Config;
use database::RuleDatabaseBinding;
use log::LevelFilter;
use mio::net::UdpSocket;
use net::file::FileHelper;
use vpn::{Vpn, VpnConfigurationResult, VpnError, VpnResultBinding};

use crate::{cache::DnsCache, vpn::VpnControllerBinding};

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
    ad_vpn_callback: Box<dyn VpnCallback>,
    block_logger_callback: Option<Box<dyn BlockLoggerCallback>>,
    vpn_controller: Arc<VpnControllerBinding>,
    rule_database: Arc<RuleDatabaseBinding>,
    android_file_helper: Box<dyn AndroidFileHelper>,
) -> Result<VpnResultBinding, VpnError> {
    let mut vpn = Vpn::new(vpn_controller);
    let result = vpn.run(
        ad_vpn_callback,
        block_logger_callback,
        rule_database,
        android_file_helper,
    );
    info!("run_vpn_native: Stopped");
    return result;
}

#[uniffi::export]
pub fn network_has_ipv6_support() -> bool {
    let socket = match UdpSocket::bind(SocketAddr::new(
        std::net::IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        0,
    )) {
        Ok(value) => value,
        Err(error) => {
            error!("has_ipv6_support: Failed to create socket! - {:?}", error);
            return false;
        }
    };

    let target_socket_address = SocketAddr::V6(SocketAddrV6::new(
        Ipv6Addr::from_str("2001:2::").unwrap(),
        53,
        0,
        0,
    ));
    if let Err(error) = socket.send_to(&mut vec![1; 1], target_socket_address) {
        debug!("has_ipv6_support: Error during IPv6 test - {:?}", error);
        return false;
    }

    return true;
}

/// Convenience function to get the [Duration] since the Unix epoch
fn get_epoch() -> Duration {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap()
}

/// Callback interface to be implemented by a Kotlin class and then passed into the main loop
#[uniffi::export(callback_interface)]
pub trait VpnCallback: Send + Sync {
    fn configure(
        &self,
        vpn_controller: Arc<VpnControllerBinding>,
        dns_cache: Arc<DnsCache>,
    ) -> VpnConfigurationResult;

    fn protect_raw_socket_fd(&self, socket_fd: i32) -> bool;

    fn update_status(&self, native_status: i32);
}

/// Callback interface for accessing our filter files from the Android system
#[uniffi::export(callback_interface)]
pub trait AndroidFileHelper {
    fn get_fd(&self, path: String) -> Option<i32>;
    fn get_dns_cache_file_fd(&self) -> Option<i32>;
}

impl FileHelper for Box<dyn AndroidFileHelper> {
    fn get_file(&self, path: String) -> Option<File> {
        let fd = self.get_fd(path)?;
        return Some(unsafe { File::from_raw_fd(fd) });
    }
}

/// Callback interface for logging connections that we've blocked for the block logger
#[uniffi::export(callback_interface)]
pub trait BlockLoggerCallback: Send + Sync {
    fn log(&self, connection_name: String, allowed: bool);
}
