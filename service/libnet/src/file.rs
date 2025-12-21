use std::fs::File;

pub trait FileHelper {
    fn get_file(&self, path: String) -> Option<File>;
}
