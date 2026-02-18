use std::{
    fs::File, sync::{Arc, RwLock, atomic::AtomicBool, mpsc}, thread::{self, JoinHandle}, time::Duration
};

use ahash::{HashMap, HashMapExt};
use log::{error, info, warn};
use memmap2::Mmap;

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
#[derive(Clone)]
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

#[derive(PartialEq, PartialOrd, Debug, Clone, Copy)]
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

impl Into<FilterAction> for FilterState {
    fn into(self) -> FilterAction {
        match self {
            FilterState::DENY => FilterAction::Deny,
            _ => FilterAction::Allow,
        }
    }
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

pub trait RuleDatabase {
    /// Initializes the block list with the given filter files and single filters
    fn initialize(
        &self,
        file_helper: &Box<&dyn FileHelper>,
        filter_files: Vec<Filter>,
        single_filters: Vec<Filter>,
    ) -> Result<u64, RuleDatabaseError>;

    /// Blocks the current thread until the database has been reloaded or told to stop
    fn wait_on_init(&self);

    fn is_blocked(&self, host_name: &str) -> bool;
}

pub struct RuleDatabaseImpl {
    controller: Arc<RuleDatabaseController>,
    map: RwLock<HashMap<Vec<u8>, (FilterType, FilterAction)>>,
}

const BASE_FILE_ITEMS: usize = 200_000;

impl RuleDatabaseImpl {
    pub fn new(controller: Arc<RuleDatabaseController>) -> Self {
        RuleDatabaseImpl {
            controller,
            map: RwLock::new(HashMap::default()),
        }
    }

    /// Initializes the block list with the given filter files and single filters
    pub fn initialize(
        &self,
        file_helper: &Box<&dyn FileHelper>,
        filter_files: Vec<Filter>,
        single_filters: Vec<Filter>,
    ) -> Result<u64, RuleDatabaseError> {
        if self.controller.is_reloading() {
            info!("initialize: Already reloading, skipping");
            return Ok(0);
        }
        if self.controller.get_should_stop() {
            info!("initialize: Told to stop, skipping");
            return Ok(0);
        }
        info!(
            "initialize: Loading block list with {} filters and {} exceptions",
            filter_files.len(),
            single_filters.len()
        );

        let mut map = HashMap::<Vec<u8>, (FilterType, FilterAction)>::with_capacity(filter_files.len() * BASE_FILE_ITEMS);

        let mut sorted_filter_files = filter_files
            .iter()
            .filter(|item| item.state != FilterState::IGNORE)
            .collect::<Vec<&Filter>>();
        sorted_filter_files.dedup_by(|a, b| a.data.eq(&b.data));
        sorted_filter_files.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        let (sender, receiver) = mpsc::channel::<Result<Vec::<(Vec<u8>, (FilterType, FilterAction))>, RuleDatabaseError>>();
        let mut handles = Vec::<JoinHandle<()>>::new();
        for item in sorted_filter_files.iter() {
            let data = item.data.clone();
            let file = file_helper.get_file(data.clone());
            let filter_action: FilterAction = item.state.into();
            let controller = self.controller.clone();
            let sender = sender.clone();
            let thread_handle = thread::spawn(move || {
                let mut result_vec = Vec::<(Vec<u8>, (FilterType, FilterAction))>::with_capacity(BASE_FILE_ITEMS);
                match file {
                    Some(file) => {
                        if let Err(database_error) = load_item(file, controller, &mut result_vec, filter_action) {
                            if let RuleDatabaseError::Interrupted = database_error {
                                let _ = sender.send(Err(database_error));
                            }
                        }
                    },
                    None => {
                        info!("initialize: Failed to load data {} as file. Loading as single filter.", data);
                        add_line(&mut result_vec, &filter_action, data.as_bytes());
                    },
                }
                let _ = sender.send(Ok(result_vec));
            });
            handles.push(thread_handle);
        }

        let mut received_results = 0;
        while received_results < handles.len() {
            match receiver.recv_timeout(Duration::from_secs(1)) {
                Ok(result) => match result {
                    Ok(result_vec) => {
                        map.extend(result_vec);
                        received_results += 1;
                    },
                    Err(error) => return Err(error),
                },
                Err(error) => {
                    warn!("initialize: Receiver timed out! - {error:?}");
                    return Err(RuleDatabaseError::Interrupted);
                },
            };
        }

        let mut sorted_single_filters = single_filters
            .iter()
            .filter(|item| item.state != FilterState::IGNORE)
            .collect::<Vec<&Filter>>();
        sorted_single_filters.sort_by(|a, b| a.state.partial_cmp(&b.state).unwrap());

        for single_filter in sorted_single_filters {
            let mut result = Vec::new();
            add_line(&mut result, &(single_filter.state.into()), single_filter.data.as_bytes());
            map.extend(result);
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

        map.shrink_to_fit();
        *filter_guard = map;

        info!(
            "initialize: Loaded {} value(s) into the block list",
            filter_guard.len()
        );
        self.controller.set_reloading(false);
        self.controller.set_initialized();
        return Ok(filter_guard.len() as u64);
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

        if let Some(value) = map.get(host_name.as_bytes()) {
            return match value.1 {
                FilterAction::Deny => true,
                FilterAction::Allow => false,
            };
        } else {
            let mut sub_host_name = host_name;
            for _ in host_name.split('.') {
                sub_host_name = match sub_host_name.split_once('.') {
                    Some(value) => value.1,
                    None => break,
                };
                if !sub_host_name.contains('.') {
                    break;
                }
                if let Some(value) = map.get(sub_host_name.as_bytes()) {
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

impl RuleDatabase for RuleDatabaseImpl {
    fn initialize(
        &self,
        file_helper: &Box<&dyn FileHelper>,
        filter_files: Vec<Filter>,
        single_filters: Vec<Filter>,
    ) -> Result<u64, RuleDatabaseError> {
        self.initialize(file_helper, filter_files, single_filters)
    }

    fn wait_on_init(&self) {
        self.wait_on_init();
    }

    fn is_blocked(&self, host_name: &str) -> bool {
        self.is_blocked(host_name)
    }
}

const IPV4_LOOPBACK: &'static [u8] = b"127.0.0.1 ";
const IPV6_LOOPBACK: &'static [u8] = b"::1 ";
const NO_ROUTE: &'static [u8] = b"0.0.0.0 ";
const WILDCARD: &'static [u8] = b"*.";
const ABP_START: &'static [u8] = b"||";
const ABP_END: &'static [u8] = b"^";
const ABP_SPECIAL: &'static [u8] = b"##";
const COMMENT: &'static [u8] = b"#";
const NEWLINE: u8 = b'\n';

/// Parses a single line in a filter file and adds it to the map if it's valid
fn add_line(
    vec: &mut Vec::<(Vec<u8>, (FilterType, FilterAction))>,
    filter_action: &FilterAction,
    line: &[u8],
) {
    let start_of_line: usize;
    let mut end_of_line = line.len();
    let mut filter_type = FilterType::Wildcard;
    if line.starts_with(ABP_START) && line.ends_with(ABP_END) {
        // AdBlock Plus style filter files use ## for extra functionality that we don't support
        let mut line_window = line.windows(ABP_SPECIAL.len());
        while let Some(value) = line_window.next() {
            if value == ABP_SPECIAL {
                return;
            }
        }
        start_of_line = 2;
        end_of_line -= 1;
    } else if line.starts_with(WILDCARD) {
        start_of_line = 2;
    } else if line.starts_with(IPV4_LOOPBACK) {
        start_of_line = IPV4_LOOPBACK.len();
        filter_type = FilterType::HostName;
    } else if line.starts_with(IPV6_LOOPBACK) {
        start_of_line = IPV6_LOOPBACK.len();
        filter_type = FilterType::HostName;
    } else if line.starts_with(NO_ROUTE) {
        start_of_line = NO_ROUTE.len();
        filter_type = FilterType::HostName;
    } else {
        return;
    }

    let host = &line[start_of_line..end_of_line];
    vec.push((host.to_owned(), (filter_type, filter_action.clone())));
}

/// Loads a generic host (file or single host) and adds them to the block list
fn load_item(
    file: File,
    controller: Arc<RuleDatabaseController>,
    vec: &mut Vec::<(Vec<u8>, (FilterType, FilterAction))>,
    filter_action: FilterAction,
) -> Result<(), RuleDatabaseError> {
    match unsafe { Mmap::map(&file) } {
        Ok(file) => {
            if let Err(error) = load_file(controller, vec, filter_action, &file) {
                if let RuleDatabaseError::Interrupted = error {
                    return Err(error);
                }
            }
        },
        Err(error) => {
            error!("load_item: Failed to open file! - {error:?}");
            return Err(RuleDatabaseError::BadFilterFormat);
        },
    };
    return Ok(());
}

/// Loads a file of filters and adds them to the block list
fn load_file(
    controller: Arc<RuleDatabaseController>,
    vec: &mut Vec::<(Vec<u8>, (FilterType, FilterAction))>,
    filter_action: FilterAction,
    file: &[u8],
) -> Result<(), RuleDatabaseError> {
    let lines = file.split(|char| *char == NEWLINE);
    for line in lines {
        if controller.get_should_stop() {
            return Err(RuleDatabaseError::Interrupted);
        }

        let _ = add_line(vec, &filter_action, line);
    }
    return Ok(());
}

#[cfg(test)]
mod tests {
    use std::fs::File;

    use super::*;

    struct DummyFileHelper;

    impl FileHelper for DummyFileHelper {
        fn get_file(&self, _: String) -> Option<File> {
            None
        }
    }

    #[test]
    fn test_rules() {
        let database = RuleDatabaseImpl::new(Arc::new(RuleDatabaseController::new()));

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

        let file_helper: Box<&dyn FileHelper> = Box::from(&DummyFileHelper as &dyn FileHelper);
        if let Err(error) = database.initialize(&file_helper, vec![], single_filters) {
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
