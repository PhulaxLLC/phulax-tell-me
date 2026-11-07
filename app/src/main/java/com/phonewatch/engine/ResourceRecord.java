package com.phonewatch.engine;
/*
    Derived from NetGuard (https://github.com/M66B/NetGuard), GPL-3.0-or-later.
    Copyright 2015-2026 by Marcel Bokhorst (M66B). Modified for PhoneWatch (personal use).
    Field names must match the native engine (app/src/main/cpp/netguard).
*/
public class ResourceRecord {
    public long Time;
    public String QName;
    public String AName;
    public String Resource;
    public int TTL;
    public int uid;
    public ResourceRecord() { }
}
