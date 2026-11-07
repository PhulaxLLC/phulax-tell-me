package com.phonewatch.engine;
/*
    Derived from NetGuard (https://github.com/M66B/NetGuard), GPL-3.0-or-later.
    Copyright 2015-2026 by Marcel Bokhorst (M66B). Modified for PhoneWatch (personal use).
    Field names must match the native engine (app/src/main/cpp/netguard).
*/
public class Usage {
    public long Time;
    public int Version;
    public int Protocol;
    public String DAddr;
    public int DPort;
    public int Uid;
    public long Sent;
    public long Received;
    public Usage() { }
}
