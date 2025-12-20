/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use log::{error, info, warn};
use mio::unix::pipe::{self, Receiver, Sender};
use std::io::Read;
use std::io::Write;
use std::os::fd::AsRawFd;

use crate::vpn::VpnResult;

/// Holds an event file descriptor and flag to meant to interrupt the VPN loop
///
/// Meant to be created on the Kotlin side and passed to the main Rust loop
pub struct VpnController {
    sender_pipe: Sender,
    receiver_pipe: Receiver,
    stop_result: Option<VpnResult>,
}

impl VpnController {
    pub fn new() -> Option<Self> {
        let (sender_pipe, receiver_pipe) = match pipe::new() {
            Ok(value) => value,
            Err(error) => {
                error!("new: Failed to create pipe! - {:?}", error);
                return None;
            },
        };
        Some(VpnController {
            sender_pipe,
            receiver_pipe,
            stop_result: None,
        })
    }

    /// Returns whether the VPN has been given a reason to stop. The main loop should stop if the result is [Some].
    /// If [None], it should be ignored.
    ///
    /// Once this function is called and the result is [Some], the result will be cleared and the next call will return [None].
    pub fn get_stop_result(&mut self) -> Option<VpnResult> {
        return match self.stop_result {
            Some(result) => {
                let result_clone = result.clone();
                self.stop_result = None;

                // Additionally clear the receiver
                let mut buf = vec![];
                if let Err(error) = self.receiver_pipe.read_to_end(&mut buf) {
                    error!("get_stop_result: Failed to read receiver data! - {:?}", error);
                }

                Some(result_clone)
            }
            None => None,
        };
    }

    /// Writes an int to the event file descriptor and sets the stop flag so we can interrupt epoll and stop the VPN
    pub fn stop(&mut self, result: VpnResult) {
        if result == VpnResult::Continuing {
            error!("stop: Cannot stop with VpnResult::Continuing");
            return;
        }

        info!("VpnController::stop");
        if let Some(result) = self.stop_result {
            warn!("stop: stop_result is already set! - {:?}", result);
        }
        self.stop_result = Some(result);
        if let Err(error) = self.sender_pipe.write(&vec![1]) {
            error!("stop: Failed to write to sender pipe! - {:?}", error);
        };
    }

    pub fn get_receiver_fd(&self) -> i32 {
        self.receiver_pipe.as_raw_fd()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stop() {
        let mut controller = VpnController::new().unwrap();
        controller.stop(VpnResult::Stopping);
        assert_eq!(controller.get_stop_result(), Some(VpnResult::Stopping));

        let mut buf = vec![];
        if let Ok(value) = controller.receiver_pipe.read_to_end(&mut buf) {
            panic!("Receiver pipe still had data! - size: {value}");
        }
    }
}
