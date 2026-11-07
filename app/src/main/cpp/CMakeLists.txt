# Packet engine from NetGuard (GPL-3.0, Copyright 2015-2026 Marcel Bokhorst / M66B),
# adapted for PhoneWatch. See netguard/*.c headers.
cmake_minimum_required(VERSION 3.22.1)
project(netguard C)
add_library(netguard SHARED
        netguard/netguard.c netguard/session.c netguard/ip.c netguard/tcp.c
        netguard/udp.c netguard/icmp.c netguard/tls.c netguard/dns.c
        netguard/dhcp.c netguard/pcap.c netguard/util.c)
include_directories(netguard/)
find_library(log-lib log)
target_link_libraries(netguard ${log-lib})
target_link_options(netguard PRIVATE "-Wl,-z,max-page-size=16384")

# Wireless-debugging pairing (SPAKE2 over TLS) for the built-in helper. From Shizuku (RikkaApps, Apache-2.0), uses BoringSSL.
enable_language(CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(boringssl REQUIRED CONFIG)
add_library(adbpair SHARED adb/adb_pairing.cpp)
target_link_libraries(adbpair ${log-lib} boringssl::crypto_static)
target_link_options(adbpair PRIVATE "-Wl,-z,max-page-size=16384")
