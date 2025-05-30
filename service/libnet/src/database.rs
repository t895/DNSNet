/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::{
    collections::HashMap,
    fs::File,
    io::{self, BufRead},
    os::fd::FromRawFd,
    sync::{Arc, RwLock, atomic::AtomicBool},
    thread,
    time::Duration,
};

use crate::AndroidFileHelper;

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

/// Represents the state of a filter in the block list (Mirrors the version in Kotlin)
#[derive(uniffi::Enum, PartialEq, PartialOrd, Debug)]
pub enum NativeFilterState {
    IGNORE,
    DENY,
    ALLOW,
}

/// Represents a filter in the block list (Mirrors the version in Kotlin)
#[derive(uniffi::Record, Debug)]
pub struct NativeFilter {
    title: String,
    data: String,
    state: NativeFilterState,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
enum RuleDatabaseError {
    #[error("Bad filter format")]
    BadFilterFormat,

    #[error("Interrupted by VpnController")]
    Interrupted,

    #[error("Failed to acquire lock on filter structures")]
    LockError,
}

/// Whether a single filter should be denied or allowed
enum FilterAction {
    Deny,
    Allow,
}

/// Whether a filter is a wildcard or a host name in the [RuleDatabase]
#[derive(PartialEq)]
enum FilterType {
    HostName,
    Wildcard,
}

/// Holds the block list and manages the loading of the block list
#[derive(uniffi::Object)]
pub struct RuleDatabase {
    controller: Arc<RuleDatabaseController>,
    map: RwLock<HashMap<String, (FilterType, FilterAction)>>,
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

    /// Initializes the block list with the given filter files and single filters
    fn initialize(
        &self,
        android_file_helper: Box<dyn AndroidFileHelper>,
        filter_files: Vec<NativeFilter>,
        single_filters: Vec<NativeFilter>,
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
            "initialize: Loading block list with {} filters and {} exceptions",
            filter_files.len(),
            single_filters.len()
        );

        let mut map = HashMap::<String, (FilterType, FilterAction)>::new();

        let mut sorted_filter_files = filter_files
            .iter()
            .filter(|item| item.state != NativeFilterState::IGNORE)
            .collect::<Vec<&NativeFilter>>();
        sorted_filter_files.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for item in sorted_filter_files.iter() {
            if let Err(database_error) =
                load_item(&android_file_helper, &self.controller, &mut map, item)
            {
                if let RuleDatabaseError::Interrupted = database_error {
                    return Err(database_error);
                }
            }
        }

        let mut sorted_single_filters = single_filters
            .iter()
            .filter(|item| item.state != NativeFilterState::IGNORE)
            .collect::<Vec<&NativeFilter>>();
        sorted_single_filters.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for single_filter in sorted_single_filters {
            if let Err(error) = add_filter(
                &self.controller,
                &mut map,
                &single_filter.state,
                single_filter.data.clone(),
            ) {
                if let RuleDatabaseError::Interrupted = error {
                    return Err(error);
                }
            }
        }

        let mut filter_guard = match self.map.write() {
            Ok(value) => value,
            Err(error) => {
                error!(
                    "initialize: Failed to get write lock for data - {:?}",
                    error
                );
                return Err(RuleDatabaseError::LockError);
            }
        };

        *filter_guard = map;

        info!(
            "initialize: Loaded {} value(s) into the block list",
            filter_guard.len()
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

    /// Checks if a host name is blocked
    pub fn is_blocked(&self, host_name: &str) -> bool {
        let map = match self.map.read() {
            Ok(value) => value,
            Err(error) => {
                error!(
                    "is_blocked: Failed to get read lock for filters - {:?}",
                    error
                );
                return false;
            }
        };

        if map.is_empty() {
            return false;
        }

        if let Some(value) = map.get(host_name) {
            return match value.1 {
                FilterAction::Deny => true,
                FilterAction::Allow => false,
            };
        } else {
            let mut sub_host_name = host_name;
            for split in host_name.split('.') {
                sub_host_name = match sub_host_name.split_once(&(split.to_owned() + ".")) {
                    Some(value) => value.1,
                    None => break,
                };
                if !sub_host_name.contains('.') {
                    break;
                }
                if let Some(value) = map.get(sub_host_name) {
                    if value.0 == FilterType::HostName {
                        continue;
                    }

                    return match value.1 {
                        FilterAction::Deny => true,
                        FilterAction::Allow => false,
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

/// Parses a single line in a filter file and returns the filter if it's valid
fn parse_line(line: &str) -> Option<String> {
    if line.trim().is_empty() {
        return None;
    }

    // AdBlock Plus style filter files use ## for extra functionality that we don't support
    if line.contains("##") {
        return None;
    }

    let end_of_line = match line.find('#') {
        Some(index) => index,
        None => line.len(),
    };

    let mut start_of_filter = 0;

    if let Some(index) = line.find(IPV4_LOOPBACK) {
        start_of_filter += index + IPV4_LOOPBACK.len();
    }

    if start_of_filter == 0 {
        if let Some(index) = line.find(IPV6_LOOPBACK) {
            start_of_filter += index + IPV6_LOOPBACK.len();
        }
    }

    if start_of_filter == 0 {
        if let Some(index) = line.find(NO_ROUTE) {
            start_of_filter += index + NO_ROUTE.len();
        }
    }

    if start_of_filter >= end_of_line {
        return None;
    }

    let host = (&line[start_of_filter..end_of_line]).trim().to_lowercase();
    if host.is_empty() || host.contains(char::is_whitespace) {
        return None;
    }

    return Some(host);
}

/// Loads a generic host (file or single host) and adds them to the block list
fn load_item(
    android_file_helper: &Box<dyn AndroidFileHelper>,
    controller: &Arc<RuleDatabaseController>,
    map: &mut HashMap<String, (FilterType, FilterAction)>,
    host: &NativeFilter,
) -> Result<(), RuleDatabaseError> {
    if host.state == NativeFilterState::IGNORE {
        return Err(RuleDatabaseError::Interrupted);
    }

    match android_file_helper.get_filter_file_fd(host.data.clone()) {
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
            if let Err(error) = add_filter(controller, map, &host.state, host.data.clone()) {
                if let RuleDatabaseError::Interrupted = error {
                    return Err(error);
                }
            }
        }
    };
    return Ok(());
}

/// Adds a single filter to the block list
fn add_filter(
    controller: &Arc<RuleDatabaseController>,
    map: &mut HashMap<String, (FilterType, FilterAction)>,
    state: &NativeFilterState,
    data: String,
) -> Result<(), RuleDatabaseError> {
    if controller.get_should_stop() {
        return Err(RuleDatabaseError::Interrupted);
    }

    match data.get(..2) {
        Some(first_two_chars) => {
            // Star pseudo-wildcard style e.g. *.example.com
            if first_two_chars.chars().nth(0).unwrap() == '*' {
                // Ignore the *. at the start of a pseudo-wildcard filter
                return match data.get(2..data.len()) {
                    Some(value) => {
                        match state {
                            NativeFilterState::IGNORE => {}
                            NativeFilterState::DENY => {
                                map.insert(
                                    value.to_owned(),
                                    (FilterType::Wildcard, FilterAction::Deny),
                                );
                            }
                            NativeFilterState::ALLOW => {
                                map.insert(
                                    value.to_owned(),
                                    (FilterType::Wildcard, FilterAction::Allow),
                                );
                            }
                        };
                        Ok(())
                    }
                    None => Err(RuleDatabaseError::BadFilterFormat),
                };
            } else if first_two_chars == "||" {
                // AdBlock Plus style pseudo-wildcard e.g. ||example.com^
                match data.chars().last() {
                    Some(last_char) => {
                        if last_char == '^' {
                            return match data.get(2..data.len() - 1) {
                                Some(value) => {
                                    match state {
                                        NativeFilterState::IGNORE => {}
                                        NativeFilterState::DENY => {
                                            map.insert(
                                                value.to_owned(),
                                                (FilterType::Wildcard, FilterAction::Deny),
                                            );
                                        }
                                        NativeFilterState::ALLOW => {
                                            map.insert(
                                                value.to_owned(),
                                                (FilterType::Wildcard, FilterAction::Allow),
                                            );
                                        }
                                    };
                                    Ok(())
                                }
                                None => Err(RuleDatabaseError::BadFilterFormat),
                            };
                        }
                    }
                    None => return Err(RuleDatabaseError::BadFilterFormat),
                };
                return Err(RuleDatabaseError::BadFilterFormat);
            }
        }
        None => return Err(RuleDatabaseError::BadFilterFormat),
    };

    // Reject invalid characters in host name
    if !data
        .chars()
        .all(|c| c.is_alphanumeric() || c == '.' || c == '-')
    {
        return Err(RuleDatabaseError::BadFilterFormat);
    }

    // Plain host name e.g. example.com
    match state {
        NativeFilterState::IGNORE => {}
        NativeFilterState::DENY => {
            map.insert(data, (FilterType::HostName, FilterAction::Deny));
        }
        NativeFilterState::ALLOW => {
            map.insert(data, (FilterType::HostName, FilterAction::Allow));
        }
    };
    return Ok(());
}

/// Loads a file of filters and adds them to the block list
fn load_file(
    controller: &Arc<RuleDatabaseController>,
    map: &mut HashMap<String, (FilterType, FilterAction)>,
    filter: &NativeFilter,
    lines: io::Lines<io::BufReader<File>>,
) -> Result<(), RuleDatabaseError> {
    let mut count = 0;
    for line in lines {
        match line {
            Ok(value) => {
                if let Some(data) = parse_line(value.as_str()) {
                    if let Err(error) = add_filter(controller, map, &filter.state, data) {
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
                    &filter.data, count, error
                );
                return Err(RuleDatabaseError::BadFilterFormat);
            }
        }
    }
    debug!("load_file: Loaded {} filters from {}", count, &filter.data);
    return Ok(());
}
