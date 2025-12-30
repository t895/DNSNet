pub trait BlockLogger {
    fn log(&self, connection_name: String, allowed: bool);
}
