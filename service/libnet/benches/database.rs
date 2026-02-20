use std::{fs::File, io::Read, sync::Arc, time::Duration};

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

fn load_files(files: Vec<&str>) -> RuleDatabaseImpl {
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
    database
}

fn criterion_bench_load_data(c: &mut Criterion) {
    let mut load_group = c.benchmark_group("Load");
    load_group.measurement_time(Duration::from_secs(30));

    load_group.bench_function("oisd ABP Load", |b| {
        b.iter(|| load_files(vec!["./benches/test-data/oisd_big_abp.txt"]))
    });
    load_group.bench_function("hagezi Wildcard Load", |b| {
        b.iter(|| load_files(vec!["./benches/test-data/hagezi_ultimate_wildcard.txt"]))
    });
    load_group.bench_function("Stevenblack Hosts Load", |b| {
        b.iter(|| load_files(vec!["./benches/test-data/stevenblack_hosts.txt"]))
    });
    load_group.bench_function("All", |b| {
        b.iter(|| {
            load_files(vec![
                "./benches/test-data/oisd_big_abp.txt",
                "./benches/test-data/hagezi_ultimate_wildcard.txt",
                "./benches/test-data/stevenblack_hosts.txt",
            ])
        })
    });
    load_group.finish();

    let mut lookup_group = c.benchmark_group("Lookup");
    lookup_group.measurement_time(Duration::from_secs(30));
    lookup_group.bench_function("Is Blocked", |b| {
        b.iter(|| {
            let path = String::from("./benches/test-data/oisd_big_abp.txt");
            let database = load_files(vec![&path]);
            let mut file = BenchmarkFileHelper.get_file(path).unwrap();
            let mut buf = String::from("");
            file.read_to_string(&mut buf).unwrap();
            buf.lines().for_each(|line| {
                database.is_blocked(line);
            });
        });
    });
    lookup_group.finish();
}

criterion_group!(benches, criterion_bench_load_data);
criterion_main!(benches);
