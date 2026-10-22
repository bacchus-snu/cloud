# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Per-account guest DNS of a router on a network shared by several accounts (stdlib unittest only)."""

import ipaddress
import json
import os
import shutil
import socket
import struct
import tempfile
import threading
import time
import unittest

import guest_dns

A, NS, PTR, TXT, AAAA, OPT, DS, ANY = 1, 2, 12, 16, 28, 41, 43, 255
NOERROR, FORMERR, NXDOMAIN, NOTIMP, REFUSED, BADVERS = 0, 1, 3, 4, 5, 16

DOMAIN = "vm.example.internal"

CMDLINE = {
    "type": "dhcpsrvr",
    "name": "r-7-VM",
    "domain": DOMAIN,
    "dns1": "10.9.9.9",
    "dns2": "10.9.9.10",
    "ip6dns1": "fd00:9::9",
    "eth0ip": "10.1.1.1",
    "eth0mask": "255.255.255.0",
    "eth0ip6": "fd00:1::1",
    "eth0ip6prelen": "64",
    "dnsperaccount": "true",
}


def entry(name, account, ipv4, ipv6=None, mac=None, remove=False, secondary=None):
    e = {
        "host_name": name,
        "mac_address": mac or "02:00:00:00:00:%02x" % int(ipv4.split(".")[-1]),
        "ipv4_address": ipv4,
        "ipv6_address": ipv6,
        "ipv6_duid": None,
        "dns_addresses": "10.1.1.1",
        "default_gateway": "10.1.1.254",
        "static_routes": None,
        "default_entry": True,
        "remove": remove,
        "lease_time": 0,
        "type": "dhcpentry",
    }
    if secondary:
        e["secondary_addresses"] = secondary
    if account is not None:
        e["account_id"] = account
    return e


ENTRIES = [
    entry("alpha", 11, "10.1.1.10", "fd00:1::10", secondary=["fd00:1::12", "10.1.1.12"]),
    entry("alpha2", 11, "10.1.1.11"),
    entry("beta", 22, "10.1.1.20", "fd00:1::20"),
    entry("gone", 11, "10.1.1.40", remove=True),
    entry("orphan", None, "10.1.1.30"),
    entry("public", 22, "11.0.0.5"),
]

DEVICES = [
    {"ip": "10.1.1.1/24", "dev": "eth0", "dnsmasq": True, "gateway": "10.1.1.254"},
    {"ip": "11.0.0.1/24", "dev": "eth0:0", "dnsmasq": True, "gateway": "11.0.0.254"},
    {"ip": "169.254.3.4/16", "dev": "eth1", "dnsmasq": False},
]


def config():
    conf = guest_dns.config_from_dhcp(CMDLINE, ENTRIES, DEVICES, False)
    # stand-in for the guest device so the link-local rules can be exercised anywhere
    conf["interfaces"] = ["lo"]
    return conf


def ip(text):
    return ipaddress.ip_address(text)


def encode_name(name):
    out = b""
    for label in name.strip(".").split("."):
        if label:
            out += bytes([len(label)]) + label.encode()
    return out + b"\x00"


def make_query(name, qtype, qid=0x1234, edns=None, version=0, opcode=0, qdcount=1, qclass=1):
    flags = (opcode << 11) | 0x0100
    question = encode_name(name) + struct.pack("!HH", qtype, qclass)
    additional = b""
    if edns is not None:
        additional = b"\x00" + struct.pack("!HHIH", OPT, edns, version << 16, 0)
    header = struct.pack("!6H", qid, flags, qdcount, 0, 0, 1 if edns is not None else 0)
    return header + question + additional


def read_name(msg, offset):
    labels = []
    end = None
    while True:
        n = msg[offset]
        if n & 0xC0 == 0xC0:
            if end is None:
                end = offset + 2
            offset = ((n & 0x3F) << 8) | msg[offset + 1]
            continue
        offset += 1
        if n == 0:
            break
        labels.append(msg[offset:offset + n].decode())
        offset += n
    return ".".join(labels), end if end is not None else offset


def parse(msg):
    qid, flags, qd, an, ns, ar = struct.unpack("!6H", msg[:12])
    offset = 12
    question = None
    for _ in range(qd):
        qname, offset = read_name(msg, offset)
        qtype, qclass = struct.unpack("!HH", msg[offset:offset + 4])
        question = (qname, qtype, qclass)
        offset += 4
    records = []
    for _ in range(an + ns + ar):
        rname, offset = read_name(msg, offset)
        rtype, rclass, ttl, rdlen = struct.unpack("!HHIH", msg[offset:offset + 10])
        offset += 10
        rdata = msg[offset:offset + rdlen]
        if rtype == A:
            value = str(ipaddress.IPv4Address(rdata))
        elif rtype == AAAA:
            value = str(ipaddress.IPv6Address(rdata))
        elif rtype == PTR:
            value = read_name(msg, offset)[0]
        else:
            value = rdata
        records.append((rname, rtype, rclass, ttl, value))
        offset += rdlen
    answers = records[:an]
    additional = records[an + ns:]
    rcode = flags & 0xF
    opt = [r for r in additional if r[1] == OPT]
    if opt:
        rcode |= (opt[0][3] >> 24) << 4
    return {
        "id": qid,
        "qr": bool(flags & 0x8000),
        "aa": bool(flags & 0x0400),
        "tc": bool(flags & 0x0200),
        "rd": bool(flags & 0x0100),
        "ra": bool(flags & 0x0080),
        "rcode": rcode,
        "question": question,
        "answers": [(r[0], r[1], r[4]) for r in answers],
        "ttls": [r[3] for r in answers],
        "opt": opt[0] if opt else None,
        "size": len(msg),
    }


class TestGuestDnsConfig(unittest.TestCase):
    """The router writes the per-account map from its DHCP entries."""

    def test_per_account_only_when_the_router_serves_dns_on_a_network_shared_by_accounts(self):
        self.assertTrue(guest_dns.is_per_account(CMDLINE))
        self.assertFalse(guest_dns.is_per_account(dict(CMDLINE, useextdns="true")))
        without = dict(CMDLINE)
        del without["dnsperaccount"]
        self.assertFalse(guest_dns.is_per_account(without))

    def test_map_carries_each_entrys_account_and_leaves_out_removed_entries(self):
        conf = guest_dns.config_from_dhcp(CMDLINE, ENTRIES, DEVICES, False)
        hosts = {h["name"]: h for h in conf["hosts"]}
        self.assertEqual(["alpha", "alpha2", "beta", "orphan", "public"], sorted(hosts))
        self.assertEqual("11", hosts["alpha"]["account"])
        self.assertEqual("22", hosts["beta"]["account"])
        self.assertIsNone(hosts["orphan"]["account"])
        self.assertEqual("10.1.1.10", hosts["alpha"]["ipv4"])
        self.assertEqual("fd00:1::10", hosts["alpha"]["ipv6"])
        self.assertEqual("02:00:00:00:00:0a", hosts["alpha"]["mac"])
        self.assertEqual(["10.1.1.12", "fd00:1::12"], hosts["alpha"]["secondary"])
        self.assertEqual([], hosts["beta"]["secondary"])

    def test_map_describes_the_routers_guest_side_and_its_resolvers(self):
        conf = guest_dns.config_from_dhcp(CMDLINE, ENTRIES, DEVICES, False)
        self.assertEqual(DOMAIN, conf["domain"])
        self.assertEqual(["r-7-VM", "data-server"], conf["router_names"])
        self.assertEqual(["10.1.1.1", "11.0.0.1", "fd00:1::1"], conf["listen"])
        self.assertEqual(["eth0"], conf["interfaces"])
        self.assertEqual(["10.1.1.0/24", "11.0.0.0/24", "fd00:1::/64"], conf["networks"])
        self.assertNotIn("upstream", conf, "the responder never asks the network's resolvers itself")

    def test_the_cache_forwards_to_the_networks_resolvers(self):
        self.assertEqual(["10.9.9.9", "10.9.9.10", "fd00:9::9"], guest_dns.upstreams(CMDLINE))
        tmp = tempfile.mkdtemp()
        try:
            path = os.path.join(tmp, "guest_dns.resolv")
            self.assertTrue(guest_dns.write_resolv(path, guest_dns.upstreams(CMDLINE)))
            self.assertFalse(guest_dns.write_resolv(path, guest_dns.upstreams(CMDLINE)))
            with open(path) as f:
                self.assertEqual("nameserver 10.9.9.9\nnameserver 10.9.9.10\nnameserver fd00:9::9\n", f.read())
        finally:
            shutil.rmtree(tmp)
        self.assertEqual(("127.0.0.1", 10053), guest_dns.CACHE)

    def test_redundant_routers_also_answer_on_the_guest_gateway(self):
        conf = guest_dns.config_from_dhcp(CMDLINE, ENTRIES, DEVICES[:1], True)
        self.assertEqual(["10.1.1.1", "10.1.1.254", "fd00:1::1"], conf["listen"])

    def test_write_reports_a_change_only_when_the_map_changes(self):
        tmp = tempfile.mkdtemp()
        try:
            path = os.path.join(tmp, "guest_dns.json")
            conf = guest_dns.config_from_dhcp(CMDLINE, ENTRIES, DEVICES, False)
            self.assertTrue(guest_dns.write_config(path, conf))
            self.assertFalse(guest_dns.write_config(path, conf))
            with open(path) as f:
                self.assertEqual(conf, json.load(f))
            conf["hosts"] = conf["hosts"][:1]
            self.assertTrue(guest_dns.write_config(path, conf))
            self.assertEqual(["guest_dns.json"], os.listdir(tmp))
        finally:
            shutil.rmtree(tmp)


class TestGuestDnsAnswers(unittest.TestCase):
    """A client sees the names of its own account's VMs only."""

    ROUTER = ip("10.1.1.1")
    ROUTER6 = ip("fd00:1::1")
    ALPHA = ip("10.1.1.10")
    ALPHA6 = ip("fd00:1::10")
    BETA = ip("10.1.1.20")
    STRANGER = ip("10.1.1.99")

    def setUp(self):
        self.view = guest_dns.View(config())

    def ask(self, client, name, qtype, server=None, **kw):
        action, data = guest_dns.handle(self.view, make_query(name, qtype, **kw), client, server or self.ROUTER, False)
        self.assertEqual(guest_dns.REPLY, action)
        return parse(data)

    def asks_upstream(self, client, name, qtype):
        action, _ = guest_dns.handle(self.view, make_query(name, qtype), client, self.ROUTER, False)
        return action == guest_dns.FORWARD

    def test_own_account_name_resolves(self):
        r = self.ask(self.ALPHA, "alpha2." + DOMAIN, A)
        self.assertEqual(NOERROR, r["rcode"])
        self.assertEqual([("alpha2." + DOMAIN, A, "10.1.1.11")], r["answers"])
        self.assertTrue(r["qr"] and r["aa"] and r["ra"] and r["rd"])
        self.assertEqual(0x1234, r["id"])
        self.assertEqual([0], r["ttls"])

    def test_other_accounts_name_does_not_exist_for_a_and_aaaa_and_any(self):
        def seen(r):
            return {k: v for k, v in r.items() if k not in ("question", "size")}
        for qtype in (A, AAAA, ANY, TXT):
            r = self.ask(self.ALPHA, "beta." + DOMAIN, qtype)
            self.assertEqual(NXDOMAIN, r["rcode"], qtype)
            self.assertEqual([], r["answers"])
            self.assertEqual(seen(r), seen(self.ask(self.ALPHA, "nosuchvm." + DOMAIN, qtype)))

    def test_other_accounts_reverse_name_does_not_exist(self):
        r = self.ask(self.ALPHA, "20.1.1.10.in-addr.arpa", PTR)
        self.assertEqual(NXDOMAIN, r["rcode"])
        r = self.ask(self.BETA, "20.1.1.10.in-addr.arpa", PTR)
        self.assertEqual([("20.1.1.10.in-addr.arpa", PTR, "beta." + DOMAIN)], r["answers"])
        name6 = ip("fd00:1::20").reverse_pointer
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, name6, PTR)["rcode"])
        self.assertEqual([(name6, PTR, "beta." + DOMAIN)], self.ask(self.BETA, name6, PTR)["answers"])

    def test_global_address_not_owned_by_the_client_goes_upstream_like_an_unused_one(self):
        self.assertTrue(self.asks_upstream(self.ALPHA, "5.0.0.11.in-addr.arpa", PTR))
        self.assertTrue(self.asks_upstream(self.ALPHA, "6.0.0.11.in-addr.arpa", PTR))
        r = self.ask(self.BETA, "5.0.0.11.in-addr.arpa", PTR)
        self.assertEqual([("5.0.0.11.in-addr.arpa", PTR, "public." + DOMAIN)], r["answers"])

    def test_own_name_without_the_asked_type_has_no_data(self):
        r = self.ask(self.ALPHA, "alpha2." + DOMAIN, AAAA)
        self.assertEqual((NOERROR, []), (r["rcode"], r["answers"]))
        r = self.ask(self.ALPHA, "alpha." + DOMAIN, ANY)
        self.assertEqual({("alpha." + DOMAIN, A, "10.1.1.10"), ("alpha." + DOMAIN, AAAA, "fd00:1::10")}, set(r["answers"]))

    def test_names_are_matched_without_case_and_the_question_keeps_its_case(self):
        r = self.ask(self.ALPHA, "AlPhA.VM.Example.Internal", A)
        self.assertEqual([("AlPhA.VM.Example.Internal", A, "10.1.1.10")], r["answers"])
        self.assertEqual(("AlPhA.VM.Example.Internal", A, 1), r["question"])

    def test_a_single_label_name_is_decided_here_only_when_it_can_name_something_here(self):
        self.assertEqual([("alpha", A, "10.1.1.10")], self.ask(self.ALPHA, "alpha", A)["answers"])
        self.assertEqual((NOERROR, []), (self.ask(self.ALPHA, "alpha", TXT)["rcode"], self.ask(self.ALPHA, "alpha", TXT)["answers"]))
        self.assertEqual([("data-server", A, "10.1.1.1")], self.ask(self.STRANGER, "data-server", A)["answers"])

        def seen(r):
            return {k: v for k, v in r.items() if k not in ("question", "size")}
        for qtype in (A, AAAA, ANY):
            self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "beta", qtype)["rcode"])
            self.assertEqual(seen(self.ask(self.ALPHA, "beta", qtype)), seen(self.ask(self.ALPHA, "nosuchhost", qtype)))
        for name in ("beta", "nosuchhost"):
            self.assertTrue(self.asks_upstream(self.ALPHA, name, TXT), name)
        for qtype in (NS, DS, TXT):
            self.assertTrue(self.asks_upstream(self.ALPHA, "com", qtype), qtype)
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "com", A)["rcode"])

    def test_localhost_is_the_guests_own(self):
        self.assertEqual([("localhost", A, "127.0.0.1")], self.ask(self.STRANGER, "localhost", A)["answers"])
        self.assertEqual([("localhost", AAAA, "::1")], self.ask(self.STRANGER, "localhost", AAAA)["answers"])

    def test_the_domain_is_answered_here_and_never_goes_upstream(self):
        r = self.ask(self.ALPHA, DOMAIN, A)
        self.assertEqual((NOERROR, []), (r["rcode"], r["answers"]))
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "x.alpha." + DOMAIN, A)["rcode"])
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "nosuchvm." + DOMAIN, A)["rcode"])

    def test_the_routers_names_resolve_for_every_client(self):
        for client in (self.ALPHA, self.BETA, self.STRANGER):
            r = self.ask(client, "data-server", A)
            self.assertEqual([("data-server", A, "10.1.1.1")], r["answers"])
            r = self.ask(client, "r-7-vm." + DOMAIN, A)
            self.assertEqual([("r-7-vm." + DOMAIN, A, "10.1.1.1")], r["answers"])
        r = self.ask(self.ALPHA, "data-server", A, server=ip("11.0.0.1"))
        self.assertEqual([("data-server", A, "11.0.0.1")], r["answers"])
        r = self.ask(self.ALPHA, "data-server", AAAA)
        self.assertEqual([("data-server", AAAA, "fd00:1::1")], r["answers"])
        r = self.ask(self.STRANGER, "1.1.1.10.in-addr.arpa", PTR)
        self.assertEqual([("1.1.1.10.in-addr.arpa", PTR, "r-7-VM." + DOMAIN)], r["answers"])

    def test_a_client_outside_the_map_sees_no_vm(self):
        for name in ("alpha", "beta", "orphan"):
            self.assertEqual(NXDOMAIN, self.ask(self.STRANGER, name + "." + DOMAIN, A)["rcode"])

    def test_an_entry_without_an_account_is_visible_to_nobody_and_sees_nothing(self):
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "orphan." + DOMAIN, A)["rcode"])
        orphan = ip("10.1.1.30")
        self.assertEqual(NXDOMAIN, self.ask(orphan, "orphan." + DOMAIN, A)["rcode"])
        self.assertEqual(NXDOMAIN, self.ask(orphan, "alpha." + DOMAIN, A)["rcode"])

    def test_ipv6_clients_are_identified_by_their_address_and_eui64_link_local(self):
        r = self.ask(self.ALPHA6, "alpha." + DOMAIN, AAAA, server=self.ROUTER6)
        self.assertEqual([("alpha." + DOMAIN, AAAA, "fd00:1::10")], r["answers"])
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA6, "beta." + DOMAIN, AAAA, server=self.ROUTER6)["rcode"])
        link_local = ip("fe80::ff:fe00:a")
        r = self.ask(link_local, "alpha2." + DOMAIN, A, server=self.ROUTER6)
        self.assertEqual([("alpha2." + DOMAIN, A, "10.1.1.11")], r["answers"])
        r = self.ask(self.ALPHA6, "data-server", A, server=self.ROUTER6)
        self.assertEqual([("data-server", A, "10.1.1.1")], r["answers"])

    def test_a_secondary_address_is_its_vms_and_a_nics_own_address_comes_first(self):
        for client in ("10.1.1.12", "fd00:1::12"):
            r = self.ask(ip(client), "alpha2." + DOMAIN, A, server=self.ROUTER if "." in client else self.ROUTER6)
            self.assertEqual([("alpha2." + DOMAIN, A, "10.1.1.11")], r["answers"], client)
        conf = config()
        for host in conf["hosts"]:
            if host["name"] == "beta":
                host["secondary"] = ["10.1.1.10"]
        self.view = guest_dns.View(conf)
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "beta." + DOMAIN, A)["rcode"])

    def test_other_names_go_upstream(self):
        self.assertTrue(self.asks_upstream(self.ALPHA, "www.example.org", A))
        self.assertTrue(self.asks_upstream(self.STRANGER, "example.internal", A))
        self.assertTrue(self.asks_upstream(self.ALPHA, "", A))

    def test_non_global_addresses_are_never_asked_upstream(self):
        r = self.ask(self.ALPHA, "99.1.1.10.in-addr.arpa", PTR)
        self.assertEqual(NXDOMAIN, r["rcode"])
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, ip("fd00:5::1").reverse_pointer, PTR)["rcode"])

    def test_malformed_queries(self):
        handle = guest_dns.handle
        self.assertEqual((guest_dns.DROP, None), handle(self.view, b"\x12\x34\x01", self.ALPHA, self.ROUTER, False))
        response = bytearray(make_query("alpha." + DOMAIN, A))
        response[2] |= 0x80
        self.assertEqual((guest_dns.DROP, None), handle(self.view, bytes(response), self.ALPHA, self.ROUTER, False))
        self.assertEqual(FORMERR, self.ask(self.ALPHA, "alpha." + DOMAIN, A, qdcount=2)["rcode"])
        self.assertEqual(NOTIMP, self.ask(self.ALPHA, "alpha." + DOMAIN, A, opcode=2)["rcode"])
        loop = struct.pack("!6H", 7, 0x0100, 1, 0, 0, 0) + b"\xc0\x0c" + struct.pack("!HH", A, 1)
        action, data = handle(self.view, loop, self.ALPHA, self.ROUTER, False)
        self.assertEqual((guest_dns.REPLY, FORMERR, 7), (action, parse(data)["rcode"], parse(data)["id"]))
        cut = make_query("alpha." + DOMAIN, A)[:-3]
        self.assertEqual(FORMERR, parse(handle(self.view, cut, self.ALPHA, self.ROUTER, False)[1])["rcode"])
        long_label = struct.pack("!6H", 8, 0, 1, 0, 0, 0) + b"\x40" + b"a" * 64 + b"\x00" + struct.pack("!HH", A, 1)
        self.assertEqual(FORMERR, parse(handle(self.view, long_label, self.ALPHA, self.ROUTER, False)[1])["rcode"])
        self.assertEqual(REFUSED, self.ask(self.ALPHA, "alpha." + DOMAIN, A, qclass=3)["rcode"])

    def test_edns_is_echoed_and_unknown_versions_are_refused(self):
        r = self.ask(self.ALPHA, "alpha." + DOMAIN, A, edns=4096)
        self.assertEqual([("alpha." + DOMAIN, A, "10.1.1.10")], r["answers"])
        self.assertEqual(guest_dns.UDP_PAYLOAD, r["opt"][2])
        r = self.ask(self.ALPHA, "alpha." + DOMAIN, A, edns=4096, version=1)
        self.assertEqual((BADVERS, []), (r["rcode"], r["answers"]))
        self.assertIsNone(self.ask(self.ALPHA, "alpha." + DOMAIN, A)["opt"])

    def test_answers_larger_than_the_client_takes_over_udp_are_truncated(self):
        conf = config()
        conf["hosts"] = [{"name": "many", "account": "11", "ipv4": "10.1.2.%d" % i, "ipv6": None, "mac": None} for i in range(1, 41)]
        conf["hosts"].append({"name": "alpha", "account": "11", "ipv4": "10.1.1.10", "ipv6": None, "mac": None})
        self.view = guest_dns.View(conf)
        r = self.ask(self.ALPHA, "many." + DOMAIN, A)
        self.assertTrue(r["tc"])
        self.assertEqual([], r["answers"])
        self.assertLessEqual(r["size"], 512)
        r = self.ask(self.ALPHA, "many." + DOMAIN, A, edns=1232)
        self.assertFalse(r["tc"])
        self.assertEqual(40, len(r["answers"]))
        action, data = guest_dns.handle(self.view, make_query("many." + DOMAIN, A), self.ALPHA, self.ROUTER, True)
        self.assertEqual(40, len(parse(data)["answers"]))

    def test_an_unusable_entry_leaves_the_others_answered(self):
        conf = config()
        conf["hosts"] = conf["hosts"] + [
            {"name": "dotted.name", "account": "11", "ipv4": "10.1.1.50", "ipv6": None, "mac": None},
            {"name": "tést", "account": "11", "ipv4": "10.1.1.51", "ipv6": None, "mac": "zz"},
            {"name": None, "account": "11", "ipv4": "not-an-address", "ipv6": None, "mac": None},
        ]
        conf["networks"] = conf["networks"] + ["not-a-network"]
        self.view = guest_dns.View(conf)
        self.assertEqual([("alpha." + DOMAIN, A, "10.1.1.10")], self.ask(self.ALPHA, "alpha." + DOMAIN, A)["answers"])
        self.assertEqual(NXDOMAIN, self.ask(self.ALPHA, "dotted.name." + DOMAIN, A)["rcode"])

    def test_only_guest_clients_asking_the_routers_guest_addresses_are_served(self):
        lo = socket.if_nametoindex("lo")
        accepts = self.view.accepts
        self.assertTrue(accepts(self.ALPHA, self.ROUTER, 0))
        self.assertTrue(accepts(ip("11.0.0.77"), ip("11.0.0.1"), 0))
        self.assertTrue(accepts(self.ALPHA6, self.ROUTER6, 0))
        self.assertFalse(accepts(self.ALPHA, ip("169.254.3.4"), 0))
        self.assertFalse(accepts(ip("192.0.2.1"), self.ROUTER, 0))
        self.assertFalse(accepts(ip("2001:db8::1"), self.ROUTER6, 0))
        self.assertTrue(accepts(ip("fe80::1"), ip("fe80::2"), lo))
        self.assertFalse(accepts(ip("fe80::1"), ip("fe80::2"), lo + 1000))


if __name__ == '__main__':
    unittest.main()


class TestGuestDnsTcp(unittest.TestCase):
    """The TCP side bounds what one guest can hold."""

    def setUp(self):
        conf = config()
        conf["listen"] = ["127.0.0.1"]
        conf["networks"] = ["127.0.0.0/8"]
        self.server = guest_dns.Server(os.devnull)
        self.server.view = guest_dns.View(conf)
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(16)
        self.clients = []
        self.saved = guest_dns.TCP_DEADLINE

    def tearDown(self):
        guest_dns.TCP_DEADLINE = self.saved
        for client in self.clients:
            client.close()
        self.listener.close()

    def connect(self):
        client = socket.create_connection(self.listener.getsockname(), timeout=5)
        self.clients.append(client)
        self.server.accept(self.listener)
        return client

    def closed_within(self, client, seconds):
        client.settimeout(seconds)
        try:
            return client.recv(1) == b""
        except socket.timeout:
            return False
        except ConnectionError:
            return True

    def test_a_connection_ends_at_its_deadline_even_while_the_guest_keeps_sending(self):
        guest_dns.TCP_DEADLINE = 0.5
        client = self.connect()
        client.sendall(b"\x00\x64")
        stop = threading.Event()

        def drip():
            while not stop.is_set():
                try:
                    client.send(b"\x00")
                except OSError:
                    return
                time.sleep(0.1)
        threading.Thread(target=drip, daemon=True).start()
        try:
            self.assertTrue(self.closed_within(client, 3), "the connection outlived its deadline")
        finally:
            stop.set()

    def test_one_guest_holds_a_bounded_number_of_connections(self):
        held = [self.connect() for _ in range(guest_dns.MAX_TCP_PER_CLIENT)]
        extra = self.connect()
        self.assertTrue(self.closed_within(extra, 2), "a guest got more connections than its share")
        for client in held:
            self.assertFalse(self.closed_within(client, 0.2))
