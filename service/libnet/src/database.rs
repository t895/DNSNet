use std::{
    collections::HashMap,
    fs::File,
    io::{self, BufRead},
    sync::{Arc, RwLock, atomic::AtomicBool},
    thread,
    time::Duration,
};

use log::{debug, error, info, warn};

use crate::file::FileHelper;

/// Holds a few flags to tell the [RuleDatabase] what to do
pub struct RuleDatabaseController {
    initialized: AtomicBool,
    reloading: AtomicBool,
    should_stop: AtomicBool,
}

impl RuleDatabaseController {
    pub fn new() -> Self {
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
    pub fn is_initialized(&self) -> bool {
        return self.initialized.load(std::sync::atomic::Ordering::Relaxed);
    }

    fn get_should_stop(&self) -> bool {
        return self.should_stop.load(std::sync::atomic::Ordering::Relaxed);
    }

    /// Tells the database that this controller is attached to that it should stop reloading
    ///
    /// This is reset to false when the database is told to initialize
    pub fn set_should_stop(&self, value: bool) {
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

#[derive(PartialEq, PartialOrd, Debug)]
pub enum FilterState {
    IGNORE,
    DENY,
    ALLOW,
}

#[derive(Debug)]
pub struct Filter {
    pub title: String,
    pub data: String,
    pub state: FilterState,
}

#[derive(Debug, thiserror::Error)]
pub enum RuleDatabaseError {
    #[error("Bad filter format")]
    BadFilterFormat,

    #[error("Interrupted by VpnController")]
    Interrupted,

    #[error("Failed to acquire lock on filter structures")]
    LockError,
}

pub struct RuleDatabase {
    controller: Arc<RuleDatabaseController>,
    map: RwLock<HashMap<String, (FilterType, FilterAction), ahash::RandomState>>,
}

impl RuleDatabase {
    pub fn new(controller: Arc<RuleDatabaseController>) -> Self {
        RuleDatabase {
            controller,
            map: RwLock::new(HashMap::default()),
        }
    }

    /// Initializes the block list with the given filter files and single filters
    pub fn initialize(
        &self,
        file_helper: impl FileHelper,
        filter_files: Vec<Filter>,
        single_filters: Vec<Filter>,
    ) -> Result<(), RuleDatabaseError> {
        if self.controller.is_reloading() {
            info!("initialize: Already reloading, skipping");
            return Ok(());
        }
        if self.controller.get_should_stop() {
            info!("initialize: Told to stop, skipping");
            return Ok(());
        }
        info!(
            "initialize: Loading block list with {} filters and {} exceptions",
            filter_files.len(),
            single_filters.len()
        );

        let mut map = HashMap::<String, (FilterType, FilterAction), ahash::RandomState>::default();

        let mut sorted_filter_files = filter_files
            .iter()
            .filter(|item| item.state != FilterState::IGNORE)
            .collect::<Vec<&Filter>>();
        sorted_filter_files.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for item in sorted_filter_files.iter() {
            if let Err(database_error) = load_item(&file_helper, &self.controller, &mut map, item) {
                if let RuleDatabaseError::Interrupted = database_error {
                    return Err(database_error);
                }
            }
        }

        let mut sorted_single_filters = single_filters
            .iter()
            .filter(|item| item.state != FilterState::IGNORE)
            .collect::<Vec<&Filter>>();
        sorted_single_filters.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for single_filter in sorted_single_filters {
            if let Err(error) = add_filter(
                &self.controller,
                &mut map,
                &single_filter.state,
                &single_filter.data,
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
    pub fn wait_on_init(&self) {
        loop {
            if self.controller.is_initialized() && !self.controller.is_reloading() {
                break;
            }
            if self.controller.get_should_stop() {
                break;
            }
            thread::sleep(Duration::from_millis(10));
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
fn parse_line(line: &str) -> Option<&str> {
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

    let host = (&line[start_of_filter..end_of_line]).trim();
    if host.is_empty() || host.contains(char::is_whitespace) {
        return None;
    }

    return Some(host);
}

/// Loads a generic host (file or single host) and adds them to the block list
fn load_item(
    file_helper: &impl FileHelper,
    controller: &RuleDatabaseController,
    map: &mut HashMap<String, (FilterType, FilterAction), ahash::RandomState>,
    host: &Filter,
) -> Result<(), RuleDatabaseError> {
    if host.state == FilterState::IGNORE {
        return Err(RuleDatabaseError::Interrupted);
    }

    match file_helper.get_file(host.data.clone()) {
        Some(file) => {
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
            if let Err(error) = add_filter(controller, map, &host.state, &host.data) {
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
    controller: &RuleDatabaseController,
    map: &mut HashMap<String, (FilterType, FilterAction), ahash::RandomState>,
    state: &FilterState,
    line: &str,
) -> Result<(), RuleDatabaseError> {
    if controller.get_should_stop() {
        return Err(RuleDatabaseError::Interrupted);
    }

    match line.get(..2) {
        Some(first_two_chars) => {
            // Star pseudo-wildcard style e.g. *.example.com
            if first_two_chars.chars().nth(0).unwrap() == '*' {
                // Ignore the *. at the start of a pseudo-wildcard filter
                return match line.get(2..line.len()) {
                    Some(value) => {
                        match state {
                            FilterState::IGNORE => {}
                            FilterState::DENY => {
                                map.insert(
                                    value.to_owned(),
                                    (FilterType::Wildcard, FilterAction::Deny),
                                );
                            }
                            FilterState::ALLOW => {
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
                match line.chars().last() {
                    Some(last_char) => {
                        if last_char == '^' {
                            return match line.get(2..line.len() - 1) {
                                Some(value) => {
                                    match state {
                                        FilterState::IGNORE => {}
                                        FilterState::DENY => {
                                            map.insert(
                                                value.to_owned(),
                                                (FilterType::Wildcard, FilterAction::Deny),
                                            );
                                        }
                                        FilterState::ALLOW => {
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
    if !line
        .chars()
        .all(|c| c.is_alphanumeric() || c == '.' || c == '-')
    {
        return Err(RuleDatabaseError::BadFilterFormat);
    }

    // Plain host name e.g. example.com
    match state {
        FilterState::IGNORE => {}
        FilterState::DENY => {
            map.insert(line.to_owned(), (FilterType::HostName, FilterAction::Deny));
        }
        FilterState::ALLOW => {
            map.insert(line.to_owned(), (FilterType::HostName, FilterAction::Allow));
        }
    };
    return Ok(());
}

/// Loads a file of filters and adds them to the block list
fn load_file(
    controller: &RuleDatabaseController,
    map: &mut HashMap<String, (FilterType, FilterAction), ahash::RandomState>,
    filter: &Filter,
    lines: io::Lines<io::BufReader<File>>,
) -> Result<(), RuleDatabaseError> {
    let mut count = 0;
    for line in lines {
        match line {
            Ok(value) => {
                if let Some(line) = parse_line(value.as_str()) {
                    if let Err(error) = add_filter(controller, map, &filter.state, &line) {
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

#[cfg(test)]
mod tests {
    use super::*;

    struct DummyFileHelper;

    impl FileHelper for DummyFileHelper {
        fn get_file(&self, _: String) -> Option<File> {
            None
        }
    }

    #[test]
    fn test_rules() {
        let database = RuleDatabase::new(Arc::new(RuleDatabaseController::new()));

        let single_filters = vec![
            // Single host denied test
            Filter {
                title: String::from(""),
                data: String::from("singlehostdenied.com"),
                state: FilterState::DENY,
            },
            // Single host allowed test
            Filter {
                title: String::from(""),
                data: String::from("singlehostallowed.com"),
                state: FilterState::DENY,
            },
            Filter {
                title: String::from(""),
                data: String::from("singlehostallowed.com"),
                state: FilterState::ALLOW,
            },
            // Single star wildcard denied test
            Filter {
                title: String::from(""),
                data: String::from("*.starwildcard.denied.com"),
                state: FilterState::DENY,
            },
            // Single ABP wildcard denied test
            Filter {
                title: String::from(""),
                data: String::from("||abpwildcard.denied.com^"),
                state: FilterState::DENY,
            },
            // Wildcard exclusion test
            Filter {
                title: String::from(""),
                data: String::from("*.wildcard.exclusion.com"),
                state: FilterState::DENY,
            },
            Filter {
                title: String::from(""),
                data: String::from("*.spacer.spacer.wildcard.exclusion.com"),
                state: FilterState::ALLOW,
            },
            // Wildcard exclusion test reversed
            Filter {
                title: String::from(""),
                data: String::from("*.wildcardreversed.exclusionreversed.com"),
                state: FilterState::ALLOW,
            },
            Filter {
                title: String::from(""),
                data: String::from("*.block.block.wildcardreversed.exclusionreversed.com"),
                state: FilterState::DENY,
            },
        ];

        if let Err(error) = database.initialize(DummyFileHelper, vec![], single_filters) {
            panic!("Failed to initialize database! - {:?}", error)
        }

        // Single host name denied
        assert!(database.is_blocked("singlehostdenied.com"));

        // Single host name allowed
        assert!(!database.is_blocked("singlehostallowed.com"));

        // Single star wildcard allowed
        assert!(database.is_blocked("starwildcard.denied.com"));
        assert!(database.is_blocked("one.starwildcard.denied.com"));
        assert!(database.is_blocked("one.two.starwildcard.denied.com"));
        assert!(!database.is_blocked("denied.com"));

        // Single ABP wildcard allowed
        assert!(database.is_blocked("abpwildcard.denied.com"));
        assert!(database.is_blocked("one.abpwildcard.denied.com"));
        assert!(database.is_blocked("one.two.abpwildcard.denied.com"));

        // Wildcard exclusion allowed
        assert!(database.is_blocked("wildcard.exclusion.com"));
        assert!(!database.is_blocked("spacer.spacer.wildcard.exclusion.com"));
        assert!(!database.is_blocked("spacer.spacer.spacer.wildcard.exclusion.com"));
        assert!(database.is_blocked("spacer.wildcard.exclusion.com"));

        // Wildcard exclusion reversed allowed
        assert!(!database.is_blocked("wildcardreversed.exclusionreversed.com"));
        assert!(database.is_blocked("block.block.wildcardreversed.exclusionreversed.com"));
        assert!(database.is_blocked("block.block.block.wildcardreversed.exclusionreversed.com"));
        assert!(!database.is_blocked("block.wildcardreversed.exclusionreversed.com"));
    }
}
