package com.phonewatch.engine;
/*
    Derived from NetGuard (https://github.com/M66B/NetGuard), GPL-3.0-or-later.
    Copyright 2015-2026 by Marcel Bokhorst (M66B). Modified for PhoneWatch (personal use).
    Field names must match the native engine (app/src/main/cpp/netguard).
*/
public class Packet {
    public long time;
    public int version;
    public int protocol;
    public String flags;
    public String saddr;
    public int sport;
    public String daddr;
    public int dport;
    public String data;
    public int uid;
    public boolean allowed;
    public Packet() { }
}
