/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use std::sync::Arc;

use net::{
    database::{
        Filter, FilterState, RuleDatabase, RuleDatabaseController, RuleDatabaseError,
        RuleDatabaseImpl,
    },
    file::FileHelper,
};

use crate::FileHelperBinding;

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

impl FilterBinding {
    pub fn new(title: String, data: String, state: FilterStateBinding) -> Self {
        Self { title, data, state }
    }
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
pub enum RuleDatabaseErrorBinding {
    #[error("Bad filter format")]
    BadFilterFormat,

    #[error("Interrupted by VpnController")]
    Interrupted,

    #[error("Failed to acquire lock on filter structures")]
    LockError,
}

impl From<RuleDatabaseErrorBinding> for RuleDatabaseError {
    fn from(value: RuleDatabaseErrorBinding) -> Self {
        match value {
            RuleDatabaseErrorBinding::BadFilterFormat => RuleDatabaseError::BadFilterFormat,
            RuleDatabaseErrorBinding::Interrupted => RuleDatabaseError::Interrupted,
            RuleDatabaseErrorBinding::LockError => RuleDatabaseError::LockError,
        }
    }
}

impl From<RuleDatabaseError> for RuleDatabaseErrorBinding {
    fn from(value: RuleDatabaseError) -> Self {
        match value {
            RuleDatabaseError::BadFilterFormat => RuleDatabaseErrorBinding::BadFilterFormat,
            RuleDatabaseError::Interrupted => RuleDatabaseErrorBinding::Interrupted,
            RuleDatabaseError::LockError => RuleDatabaseErrorBinding::LockError,
        }
    }
}

/// Holds the block list and manages the loading of the block list
#[derive(uniffi::Object)]
pub struct RuleDatabaseBinding {
    rule_database: RuleDatabaseImpl,
}

#[uniffi::export]
impl RuleDatabaseBinding {
    #[uniffi::constructor]
    fn new(controller: Arc<RuleDatabaseControllerBinding>) -> Self {
        RuleDatabaseBinding {
            rule_database: RuleDatabaseImpl::new(controller.rule_database_controller.clone()),
        }
    }

    pub fn initialize(
        &self,
        file_helper: &Box<dyn FileHelperBinding>,
        filter_files: Vec<FilterBinding>,
        single_filters: Vec<FilterBinding>,
    ) -> Result<(), RuleDatabaseErrorBinding> {
        let file_helper = Box::from(&file_helper as &dyn FileHelper);
        self.rule_database
            .initialize(
                &file_helper,
                filter_files.iter().map(|filter| filter.into()).collect(),
                single_filters.iter().map(|filter| filter.into()).collect(),
            )
            .map_err(From::from)
    }

    pub fn wait_on_init(&self) {
        self.rule_database.wait_on_init();
    }

    pub fn is_blocked(&self, host_name: &str) -> bool {
        self.rule_database.is_blocked(host_name)
    }
}

impl RuleDatabase for RuleDatabaseBinding {
    fn initialize(
        &self,
        file_helper: &Box<&dyn FileHelper>,
        filter_files: Vec<Filter>,
        single_filters: Vec<Filter>,
    ) -> Result<(), RuleDatabaseError> {
        self.rule_database
            .initialize(file_helper, filter_files, single_filters)
    }

    fn wait_on_init(&self) {
        self.rule_database.wait_on_init();
    }

    fn is_blocked(&self, host_name: &str) -> bool {
        self.rule_database.is_blocked(host_name)
    }
}
