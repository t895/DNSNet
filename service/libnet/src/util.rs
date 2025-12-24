use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Convenience function to get the [Duration] since the Unix epoch
pub fn get_epoch() -> Duration {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap()
}
