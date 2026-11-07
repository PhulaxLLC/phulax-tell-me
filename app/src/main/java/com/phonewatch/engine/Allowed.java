package com.phonewatch.engine;
/*
    Derived from NetGuard (https://github.com/M66B/NetGuard), GPL-3.0-or-later.
    Copyright 2015-2026 by Marcel Bokhorst (M66B). Modified for PhoneWatch (personal use).
    Field names must match the native engine (app/src/main/cpp/netguard).
*/
public class Allowed {
    public String raddr;
    public int rport;
    public Allowed() { this.raddr = null; this.rport = 0; }
    public Allowed(String raddr, int rport) { this.raddr = raddr; this.rport = rport; }
}
