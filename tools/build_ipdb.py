"""Build PhoneWatch's offline IP database (app/src/main/assets/data/ipdb.bin) from DB-IP Lite CSVs.

Data: DB-IP IP-to-City Lite + IP-to-ASN Lite, (c) DB-IP.com, CC BY 4.0 (https://db-ip.com).
Usage: python build_ipdb.py <dbip-city-lite.csv.gz> <dbip-asn-lite.csv.gz> <out.bin>

Precision kept to fit in the APK: IPv4 city ranges in full, IPv6 city by /32, ASN by IPv4 range / IPv6 /64.

Format (big endian):
  "PWIP" u32 version(2)
  strings: u32 n, u32 offsets[n+1], utf8 blob
  locs:    u32 n, n x (u32 country, u32 region, u32 city)   (string ids)
  ases:    u32 n, n x (u32 asn, u32 org)
  4 tables (city4, city6, asn4, asn6), each:
    u8 keyBits (32 or 64: how many top bits of the address form the key), u32 nEntries, u32 nBlocks,
    nBlocks x (u64 firstKey, u32 dataOffset), u32 dataLen, data
    data per block of 32 entries: entry0 = varint(value+1); entry i>0 = varint(key - prevKey), varint(value+1)
    (value+1 == 0 means "no data" for that range)
"""
import csv, gzip, ipaddress, struct, sys
from collections import Counter

BLOCK = 32


def varint(n, out):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def read_ranges(path, v6bits, val_fn):
    """-> {4: [(key, val)], 6: [(key, val)]} with gaps filled by None and equal neighbours merged."""
    out = {4: [], 6: []}
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        for r in csv.reader(f):
            a, b = ipaddress.ip_address(r[0]), ipaddress.ip_address(r[1])
            if a.version == 4:
                v, s, e = 4, int(a), int(b) + 1
            else:
                v, s, e = 6, int(a) >> (128 - v6bits), (int(b) >> (128 - v6bits)) + 1
            val = val_fn(r)
            lst = out[v]
            for k, x in ((s, val), (e, None)):
                if lst and lst[-1][0] == k:
                    if x is None and lst[-1][1] is not None:
                        continue  # keep the real value at a shared boundary
                    lst[-1] = (k, x)
                elif lst and lst[-1][0] > k:
                    continue  # overlapping/coarsened v6 range: first one wins
                else:
                    lst.append((k, x))
    for v in out:  # merge equal neighbours
        m = []
        for k, x in out[v]:
            if m and m[-1][1] == x:
                continue
            m.append((k, x))
        out[v] = m
    return out


def table(entries, key_bits, remap):
    blocks, data = [], bytearray()
    prev = 0
    for i, (k, x) in enumerate(entries):
        val = 0 if x is None else remap[x] + 1
        if i % BLOCK == 0:
            blocks.append((k, len(data)))
            varint(val, data)
        else:
            varint(k - prev, data)
            varint(val, data)
        prev = k
    b = bytearray(struct.pack(">BII", key_bits, len(entries), len(blocks)))
    for k, off in blocks:
        b += struct.pack(">QI", k, off)
    b += struct.pack(">I", len(data)) + data
    return b


def main(city_gz, asn_gz, out_path):
    city = read_ranges(city_gz, 32, lambda r: None if r[3] == "ZZ" else (r[3], r[4], r[5]))
    asn = read_ranges(asn_gz, 64, lambda r: (int(r[2]), r[3]))

    # most frequent values get the smallest ids (shorter varints)
    lc = Counter(x for v in city for _, x in city[v] if x is not None)
    ac = Counter(x for v in asn for _, x in asn[v] if x is not None)
    locs = [x for x, _ in lc.most_common()]
    ases = [x for x, _ in ac.most_common()]
    lmap = {x: i for i, x in enumerate(locs)}
    amap = {x: i for i, x in enumerate(ases)}

    strings, sidx = [], {}
    def sid(s):
        if s not in sidx:
            sidx[s] = len(strings); strings.append(s)
        return sidx[s]

    loc_rows = [(sid(c), sid(r), sid(t)) for c, r, t in locs]
    as_rows = [(n, sid(o)) for n, o in ases]

    b = bytearray(b"PWIP" + struct.pack(">I", 2))
    blobs = [s.encode("utf-8") for s in strings]
    offs, o = [0], 0
    for x in blobs:
        o += len(x); offs.append(o)
    b += struct.pack(">I", len(blobs)) + struct.pack(">%dI" % len(offs), *offs) + b"".join(blobs)
    b += struct.pack(">I", len(loc_rows)) + b"".join(struct.pack(">III", *x) for x in loc_rows)
    b += struct.pack(">I", len(as_rows)) + b"".join(struct.pack(">II", *x) for x in as_rows)
    b += table(city[4], 32, lmap) + table(city[6], 32, lmap) + table(asn[4], 32, amap) + table(asn[6], 64, amap)
    open(out_path, "wb").write(b)
    print("locs", len(locs), "ases", len(ases), "city4", len(city[4]), "city6", len(city[6]),
          "asn4", len(asn[4]), "asn6", len(asn[6]), "size MB %.1f" % (len(b) / 1e6))


if __name__ == "__main__":
    main(*sys.argv[1:4])
