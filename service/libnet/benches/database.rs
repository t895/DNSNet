use std::{fs::File, sync::Arc, time::Duration};

use criterion::{Criterion, criterion_group, criterion_main};
use net::{
    database::{Filter, FilterState, RuleDatabaseController, RuleDatabaseImpl},
    file::FileHelper,
};

struct BenchmarkFileHelper;

impl FileHelper for BenchmarkFileHelper {
    fn get_file(&self, path: String) -> Option<std::fs::File> {
        match File::open(path) {
            Ok(file) => Some(file),
            Err(_) => panic!("Given bad path!"),
        }
    }
}

fn load_files(files: Vec<&str>) {
    let database = RuleDatabaseImpl::new(Arc::new(RuleDatabaseController::new()));
    let file_helper: Box<&dyn FileHelper> = Box::from(&BenchmarkFileHelper as &dyn FileHelper);
    if let Err(_) = database.initialize(
        &file_helper,
        files
            .iter()
            .map(|path| Filter {
                title: String::from(""),
                data: path.to_string(),
                state: FilterState::DENY,
            })
            .collect(),
        vec![],
    ) {
        panic!("Failed to initialize database");
    }
}

fn criterion_bench_load_data(c: &mut Criterion) {
    let mut group = c.benchmark_group("Load");
    group.measurement_time(Duration::from_secs(30));

    group.bench_function("oisd ABP Load", |b| {
        b.iter(|| load_files(vec!["./benches/test-data/oisd_big_abp.txt"]))
    });
    group.bench_function("hagezi Wildcard Load", |b| {
        b.iter(|| load_files(vec!["./benches/test-data/hagezi_ultimate_wildcard.txt"]))
    });
    group.bench_function("Stevenblack Hosts Load", |b| {
        b.iter(|| load_files(vec!["./benches/test-data/stevenblack_hosts.txt"]))
    });
    group.bench_function("All", |b| {
        b.iter(|| {
            load_files(vec![
                "./benches/test-data/oisd_big_abp.txt",
                "./benches/test-data/hagezi_ultimate_wildcard.txt",
                "./benches/test-data/stevenblack_hosts.txt",
            ])
        })
    });

    group.finish();
}

criterion_group!(benches, criterion_bench_load_data);
criterion_main!(benches);
