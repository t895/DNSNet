/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

 #![feature(portable_simd)]

pub mod backend;
pub mod cache;
pub mod controller;
pub mod database;
pub mod file;
pub mod log;
pub mod packet;
pub mod proxy;
mod util;
pub mod vpn;
