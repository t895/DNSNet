/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::sync::Arc;

use net::database::{Filter, FilterState, RuleDatabase, RuleDatabaseController, RuleDatabaseError};

use crate::AndroidFileHelper;

/// Holds a few flags to tell the [RuleDatabase] what to do from the Kotlin side
#[derive(uniffi::Object)]
pub struct RuleDatabaseControllerBinding {
    rule_database_controller: Arc<RuleDatabaseController>,
}

#[uniffi::export]
impl RuleDatabaseControllerBinding {
    #[uniffi::constructor]
    fn new() -> Self {
        RuleDatabaseControllerBinding {
            rule_database_controller: Arc::new(RuleDatabaseController::new()),
        }
    }

    fn is_initialized(&self) -> bool {
        self.rule_database_controller.is_initialized()
    }

    fn set_should_stop(&self, value: bool) {
        self.rule_database_controller.set_should_stop(value);
    }
}

/// Represents the state of a filter in the block list (Mirrors the version in Kotlin)
#[derive(uniffi::Enum, PartialEq, PartialOrd, Debug, Clone)]
pub enum FilterStateBinding {
    IGNORE,
    DENY,
    ALLOW,
}

impl Into<FilterState> for FilterStateBinding {
    fn into(self) -> FilterState {
        match self {
            FilterStateBinding::IGNORE => FilterState::IGNORE,
            FilterStateBinding::DENY => FilterState::DENY,
            FilterStateBinding::ALLOW => FilterState::ALLOW,
        }
    }
}

/// Represents a filter in the block list (Mirrors the version in Kotlin)
#[derive(uniffi::Record, Debug, Clone)]
pub struct FilterBinding {
    title: String,
    data: String,
    state: FilterStateBinding,
}

impl Into<Filter> for &FilterBinding {
    fn into(self) -> Filter {
        Filter {
            title: self.title.clone(),
            data: self.data.clone(),
            state: self.state.clone().into(),
        }
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
enum RuleDatabaseErrorBinding {
    #[error("Bad filter format")]
    BadFilterFormat,

    #[error("Interrupted by VpnController")]
    Interrupted,

    #[error("Failed to acquire lock on filter structures")]
    LockError,
}

impl Into<RuleDatabaseErrorBinding> for RuleDatabaseError {
    fn into(self) -> RuleDatabaseErrorBinding {
        match self {
            RuleDatabaseError::BadFilterFormat => RuleDatabaseErrorBinding::BadFilterFormat,
            RuleDatabaseError::Interrupted => RuleDatabaseErrorBinding::Interrupted,
            RuleDatabaseError::LockError => RuleDatabaseErrorBinding::LockError,
        }
    }
}

/// Holds the block list and manages the loading of the block list
#[derive(uniffi::Object)]
pub struct RuleDatabaseBinding {
    rule_database: RuleDatabase,
}

#[uniffi::export]
impl RuleDatabaseBinding {
    #[uniffi::constructor]
    fn new(controller: Arc<RuleDatabaseControllerBinding>) -> Self {
        RuleDatabaseBinding {
            rule_database: RuleDatabase::new(controller.rule_database_controller.clone()),
        }
    }

    /// Initializes the block list with the given filter files and single filters
    fn initialize(
        &self,
        android_file_helper: Box<dyn AndroidFileHelper>,
        filter_files: Vec<FilterBinding>,
        single_filters: Vec<FilterBinding>,
    ) -> Result<(), RuleDatabaseErrorBinding> {
        return match self.rule_database.initialize(
            android_file_helper,
            filter_files.iter().map(|filter| filter.into()).collect(),
            single_filters.iter().map(|filter| filter.into()).collect(),
        ) {
            Ok(_) => Ok(()),
            Err(error) => Err(error.into()),
        };
    }

    /// Blocks the current thread until the database has been reloaded or told to stop
    fn wait_on_init(&self) {
        self.rule_database.wait_on_init();
    }

    /// Checks if a host name is blocked
    pub fn is_blocked(&self, host_name: &str) -> bool {
        self.rule_database.is_blocked(host_name)
    }
}
