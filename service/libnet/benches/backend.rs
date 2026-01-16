use std::time::Duration;

use criterion::{Criterion, criterion_group, criterion_main};
use log::error;
use mio::{Events, Poll};
use net::backend::{DnsBackend, DnsResponseHandler, DnsServer, SocketProtector, doh3::DoH3Backend};

fn criterion_bench_resolve(c: &mut Criterion) {
    simple_logger::SimpleLogger::new()
        .env()
        .with_level(log::LevelFilter::Debug)
        .init()
        .unwrap();
    let mut group = c.benchmark_group("Resolve");
    group.measurement_time(Duration::from_secs(30));

    group.bench_function("DoH3", |b| {
        b.iter(|| {
            let servers = vec![DnsServer::DoH3(
                vec![8, 8, 8, 8],
                String::from("dns.google"),
                None,
            )];
            let mut backend = match DoH3Backend::new(&servers) {
                Ok(value) => value,
                Err(error) => {
                    error!("Failed to initialize DOH3 backend! - {:?}", error);
                    return;
                }
            };
            let mut poll = match Poll::new() {
                Ok(value) => value,
                Err(error) => {
                    error!("Failed to initialize poll! - {:?}", error);
                    return;
                }
            };
            let mut events = Events::with_capacity(backend.get_max_events_count());

            struct DummySocketProtector;
            impl SocketProtector for DummySocketProtector {
                fn protect_fd(&self, _: i32) -> bool {
                    true
                }
            }
            let socket_protector = Box::from(&DummySocketProtector as &dyn SocketProtector);
            let dns_payload: Vec<u8> = vec![
                76, 63, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 4, 100, 97, 116, 97, 6, 97, 109, 97, 122,
                111, 110, 3, 99, 111, 109, 0, 0, 1, 0, 1,
            ];
            let original_request_packet: Vec<u8> = vec![
                69, 0, 0, 61, 235, 75, 64, 0, 64, 17, 203, 96, 192, 168, 1, 200, 8, 8, 8, 8, 158,
                62, 0, 53, 0, 41, 33, 223, 76, 63, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 4, 100, 97, 116,
                97, 6, 97, 109, 97, 122, 111, 110, 3, 99, 111, 109, 0, 0, 1, 0, 1,
            ];
            let destination_address: Vec<u8> = String::from("dns.google").as_bytes().to_vec();
            if let Err(error) = backend.forward_packet(
                &socket_protector,
                &dns_payload,
                &original_request_packet,
                destination_address,
                0,
            ) {
                error!("Failed to forward packet! - {:?}", error);
                return;
            };
            backend.register_sources(&mut poll);

            if let Err(error) = poll.poll(&mut events, backend.get_poll_timeout()) {
                error!("Poll failed! - {:?}", error);
                return;
            };

            struct DummyResponseHandler;
            impl DnsResponseHandler for DummyResponseHandler {
                fn handle(&mut self, _: &[u8], _: &[u8]) {}
            }
            let mut dummy_handler = DummyResponseHandler;
            let mut response_handler = Box::from(&mut dummy_handler as &mut dyn DnsResponseHandler);
            if let Err(error) = backend.process_events(&mut response_handler, vec![]) {
                error!("Failed to process backend events! - {:?}", error);
                return;
            };
        })
    });

    group.finish();
}

criterion_group!(benches, criterion_bench_resolve);
criterion_main!(benches);
