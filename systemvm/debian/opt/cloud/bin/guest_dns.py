#!/usr/bin/python3
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

"""DNS for the guests of a network shared by several accounts.

A guest's query for a name in the network's domain, or for the reverse name of
an address, is answered from the router's DHCP entries with the VMs of the
guest's own account only: any other VM's name does not exist for it. The
router's own names are answered to every guest. Other queries go to a local
caching dnsmasq that forwards them to the network's resolvers. Guests are told
apart by the source address of the query, which the network's anti-spoofing
rules (security groups) tie to the guest's NIC.
"""

import ipaddress
import json
import logging
import os
import secrets
import selectors
import socket
import struct
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor

CONFIG = "/var/cache/cloud/guest_dns.json"
RESOLV = "/var/cache/cloud/guest_dns.resolv"
SERVICE = "cloud-guest-dns"
CACHE_SERVICE = "cloud-guest-dns-cache"
CACHE = ("127.0.0.1", 10053)
PORT = 53
UDP_PAYLOAD = 1232
CACHE_TIMEOUT = 5.0
UPSTREAM_WORKERS = 32
MAX_RELAYED = 256
MAX_TCP_CLIENTS = 64
MAX_TCP_PER_CLIENT = 4
TCP_IDLE = 10.0
TCP_DEADLINE = 30.0
RELOAD_INTERVAL = 1.0

REPLY, FORWARD, DROP = "reply", "forward", "drop"

A, PTR, AAAA, OPT, ANY = 1, 12, 28, 41, 255
CLASS_IN = 1
LOCALHOST = (ipaddress.IPv4Address("127.0.0.1"), ipaddress.IPv6Address("::1"))
NOERROR, FORMERR, SERVFAIL, NXDOMAIN, NOTIMP, REFUSED, BADVERS = 0, 1, 2, 3, 4, 5, 16
QR, OPCODE, AA, TC, RD, RA, CD = 0x8000, 0x7800, 0x0400, 0x0200, 0x0100, 0x0080, 0x0010

IP_PKTINFO = getattr(socket, "IP_PKTINFO", 8)


def is_per_account(cmdline):
    return cmdline.get("dnsperaccount") == "true" and cmdline.get("useextdns", "false") != "true"


def _append(items, item):
    if item not in items:
        items.append(item)


def config_from_dhcp(cmdline, entries, devices, redundant):
    """The responder's map: the router's guest side from its cmdline and devices, the VMs from its DHCP entries."""
    listen, networks, interfaces = [], [], []
    for device in devices:
        if not device.get("dnsmasq"):
            continue
        address = ipaddress.ip_interface(device["ip"])
        _append(listen, str(address.ip))
        if redundant and device.get("gateway"):
            _append(listen, device["gateway"])
        _append(networks, str(address.network))
        _append(interfaces, device["dev"].split(":")[0])
    for name in interfaces:
        ip6 = cmdline.get(name + "ip6")
        if ip6:
            _append(listen, ip6)
            if cmdline.get(name + "ip6prelen"):
                _append(networks, str(ipaddress.ip_interface("%s/%s" % (ip6, cmdline[name + "ip6prelen"])).network))
    if redundant and cmdline.get("guestgw6"):
        _append(listen, cmdline["guestgw6"])

    hosts = []
    for entry in entries:
        if entry.get("remove") or not entry.get("host_name"):
            continue
        account = entry.get("account_id")
        hosts.append({
            "name": entry["host_name"],
            "account": None if account is None else str(account),
            "ipv4": entry.get("ipv4_address"),
            "ipv6": entry.get("ipv6_address"),
            "mac": entry.get("mac_address"),
            "secondary": sorted(entry.get("secondary_addresses") or []),
        })
    hosts.sort(key=lambda h: (h["name"], h["ipv4"] or "", h["ipv6"] or ""))

    return {
        "domain": cmdline.get("domain") or "cloudnine.internal",
        "router_names": [n for n in (cmdline.get("name"), "data-server") if n],
        "listen": listen,
        "interfaces": interfaces,
        "networks": networks,
        "hosts": hosts,
    }


def upstreams(cmdline):
    """The network's resolvers, which the caching dnsmasq forwards to."""
    return [cmdline[k] for k in ("dns1", "dns2", "ip6dns1", "ip6dns2") if cmdline.get(k)]


def write_config(path, conf):
    """Replaces the map atomically; returns whether it changed."""
    return _write(path, json.dumps(conf, indent=1, sort_keys=True) + "\n")


def write_resolv(path, servers):
    """Replaces the caching dnsmasq's resolver list atomically; returns whether it changed."""
    return _write(path, "".join("nameserver %s\n" % server for server in servers))


def _write(path, text):
    try:
        with open(path) as f:
            if f.read() == text:
                return False
    except OSError:
        pass
    fd, temporary = tempfile.mkstemp(prefix=".guest_dns.", dir=os.path.dirname(path) or ".")
    try:
        with os.fdopen(fd, "w") as f:
            f.write(text)
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise
    return True


class FormatError(Exception):
    pass


def read_name(msg, offset):
    """Returns the name's labels and the offset after it; compression pointers must point backwards."""
    labels = []
    length = 1
    end = None
    while True:
        if offset >= len(msg):
            raise FormatError()
        size = msg[offset]
        if size & 0xC0 == 0xC0:
            if offset + 1 >= len(msg):
                raise FormatError()
            target = ((size & 0x3F) << 8) | msg[offset + 1]
            if target >= offset:
                raise FormatError()
            if end is None:
                end = offset + 2
            offset = target
            continue
        if size & 0xC0:
            raise FormatError()
        offset += 1
        if size == 0:
            return labels, offset if end is None else end
        length += size + 1
        if length > 255 or offset + size > len(msg):
            raise FormatError()
        labels.append(msg[offset:offset + size])
        offset += size


def encode_name(name):
    out = b""
    for label in name.strip(".").split("."):
        if label:
            out += bytes([len(label)]) + label.encode("ascii")
    return out + b"\x00"


def _header(qid, flags, qdcount, ancount, arcount):
    return struct.pack("!6H", qid, flags, qdcount, ancount, 0, arcount)


def _bare_reply(qid, flags, rcode):
    return _header(qid, QR | RA | (flags & (OPCODE | RD | CD)) | rcode, 0, 0, 0)


def _record(rtype, rdata):
    return b"\xc0\x0c" + struct.pack("!HHIH", rtype, CLASS_IN, 0, len(rdata)) + rdata


class Query(object):

    def __init__(self, msg):
        self.id, self.flags, _, ancount, nscount, arcount = struct.unpack("!6H", msg[:12])
        self.labels, offset = read_name(msg, 12)
        if offset + 4 > len(msg):
            raise FormatError()
        self.qtype, self.qclass = struct.unpack("!HH", msg[offset:offset + 4])
        self.question = msg[12:offset + 4]
        self.key = [label.lower() for label in self.labels]
        self.payload = None
        self.version = 0
        position = offset + 4
        for index in range(ancount + nscount + arcount):
            _, position = read_name(msg, position)
            if position + 10 > len(msg):
                raise FormatError()
            rtype, rclass, ttl, rdlength = struct.unpack("!HHIH", msg[position:position + 10])
            position += 10 + rdlength
            if position > len(msg):
                raise FormatError()
            if rtype == OPT:
                if index < ancount + nscount or self.payload is not None:
                    raise FormatError()
                self.payload = max(512, rclass)
                self.version = (ttl >> 16) & 0xFF

    def reply(self, rcode, records, tcp, authoritative=True):
        flags = QR | RA | (self.flags & (OPCODE | RD | CD)) | (rcode & 0xF) | (AA if authoritative else 0)
        opt = b""
        if self.payload is not None:
            opt = b"\x00" + struct.pack("!HHIH", OPT, UDP_PAYLOAD, (rcode >> 4) << 24, 0)
        answers = b"".join(records)
        msg = _header(self.id, flags, 1, len(records), 1 if opt else 0) + self.question + answers + opt
        limit = 65535 if tcp else (self.payload or 512)
        if len(msg) > limit:
            msg = _header(self.id, flags | TC, 1, 0, 1 if opt else 0) + self.question + opt
        return msg

    def matches(self, answer, qid):
        if len(answer) < 12:
            return False
        rid, flags, qdcount = struct.unpack("!3H", answer[:6])
        if rid != qid or not flags & QR:
            return False
        if qdcount == 0:
            return flags & 0xF != NOERROR
        try:
            labels, offset = read_name(answer, 12)
            qtype, qclass = struct.unpack("!HH", answer[offset:offset + 4])
        except (FormatError, struct.error):
            return False
        return [label.lower() for label in labels] == self.key and (qtype, qclass) == (self.qtype, self.qclass)


def _address(text):
    if not text:
        return None
    try:
        return ipaddress.ip_address(text.split("%")[0])
    except ValueError:
        return None


def _label(name):
    """A name as one lower-case DNS label, or None when it is not one."""
    try:
        label = name.lower().encode("ascii")
    except (AttributeError, UnicodeError):
        return None
    if not label or len(label) > 63 or b"." in label:
        return None
    return label


def _link_local(mac):
    try:
        octets = bytes(int(part, 16) for part in mac.split(":"))
    except (AttributeError, ValueError):
        return None
    if len(octets) != 6:
        return None
    return ipaddress.IPv6Address(b"\xfe\x80" + b"\x00" * 6 + bytes([octets[0] ^ 0x02]) + octets[1:3] + b"\xff\xfe" + octets[3:])


def _reverse_address(key):
    if len(key) == 6 and key[4:] == [b"in-addr", b"arpa"]:
        if all(label.isdigit() and len(label) <= 3 and int(label) <= 255 for label in key[:4]):
            return ipaddress.IPv4Address(bytes(int(label) for label in reversed(key[:4])))
    elif len(key) == 34 and key[32:] == [b"ip6", b"arpa"]:
        digits = b"".join(reversed(key[:32]))
        if all(len(label) == 1 for label in key[:32]) and all(c in b"0123456789abcdef" for c in digits):
            return ipaddress.IPv6Address(int(digits, 16))
    return None


class View(object):
    """One immutable snapshot of the map, indexed for lookups."""

    def __init__(self, conf):
        domain = conf.get("domain", "").strip(".").lower()
        self.domain = [label.encode("ascii") for label in domain.split(".")] if domain else []
        self.domain_name = domain
        self.router_names = [name for name in conf.get("router_names", []) if _label(name)]
        self.router_keys = set(_label(name) for name in self.router_names)
        listen = [_address(a) for a in conf.get("listen", [])]
        self.listen = set(a for a in listen if a)
        self.router_ipv4 = next((a for a in listen if a and a.version == 4), None)
        self.router_ipv6 = next((a for a in listen if a and a.version == 6 and not a.is_link_local), None)
        self.networks = []
        for network in conf.get("networks", []):
            try:
                self.networks.append(ipaddress.ip_network(network, strict=False))
            except ValueError:
                pass
        self.ifindexes = set()
        for name in conf.get("interfaces", []):
            try:
                self.ifindexes.add(socket.if_nametoindex(name))
            except OSError:
                pass
        self.accounts = {}
        self.names = {}
        self.reverse = {}
        hosts = [h for h in conf.get("hosts", []) if h.get("account") is not None and _label(h.get("name"))]
        # a NIC's own addresses outrank another NIC's secondary ones
        for host in hosts:
            for address in host.get("secondary") or []:
                if _address(address):
                    self.accounts[_address(address)] = host["account"]
        for host in hosts:
            account, name = host["account"], host["name"]
            ipv4, ipv6 = _address(host.get("ipv4")), _address(host.get("ipv6"))
            for client in (ipv4, ipv6, _link_local(host.get("mac"))):
                if client:
                    self.accounts[client] = account
            self.names.setdefault((account, _label(name)), []).append((ipv4, ipv6))
            for address in (ipv4, ipv6):
                if address:
                    self.reverse[address] = (account, name)

    def accepts(self, client, server, ifindex):
        if server.version == 6 and server.is_link_local:
            return ifindex in self.ifindexes and (client.is_link_local or self._on_network(client))
        if server not in self.listen:
            return False
        if client.version == 6 and client.is_link_local:
            return ifindex in self.ifindexes
        return self._on_network(client)

    def _on_network(self, client):
        return any(client in network for network in self.networks)

    def _fqdn(self, name):
        return name + "." + self.domain_name if self.domain_name else name

    def lookup(self, query, client, server):
        """Returns (rcode, records) for a name answered here, or None for one to relay."""
        key = query.key
        address = _reverse_address(key)
        size = len(self.domain)
        if address is None and not (size and key[-size:] == self.domain):
            if len(key) == 1 and query.qclass in (CLASS_IN, ANY):
                return self._lookup_plain(key[0], query.qtype, client, server)
            return None
        if query.qclass not in (CLASS_IN, ANY):
            return REFUSED, []
        if address is not None:
            return self._lookup_reverse(address, query.qtype, self.accounts.get(client))
        relative = key[:-size]
        if not relative:
            return NOERROR, []
        if len(relative) > 1:
            return NXDOMAIN, []
        return self._lookup_name(relative[0], query.qtype, client, server) or (NXDOMAIN, [])

    def _lookup_plain(self, label, qtype, client, server):
        # Like dnsmasq's domain-needed: a plain name never gets an address upstream, while
        # other types are relayed. The choice depends only on qtype and the client's own
        # names, so another account's name is handled exactly like a missing one.
        if label == b"localhost":
            return NOERROR, self._addresses([LOCALHOST], qtype)
        answer = self._lookup_name(label, qtype, client, server)
        if answer is not None:
            return answer
        return (NXDOMAIN, []) if qtype in (A, AAAA, ANY) else None

    def _lookup_name(self, label, qtype, client, server):
        if label in self.router_keys:
            ipv4 = server if server.version == 4 else self.router_ipv4
            ipv6 = server if server.version == 6 and not server.is_link_local else self.router_ipv6
            return NOERROR, self._addresses([(ipv4, ipv6)], qtype)
        # the source address is the guest's identity; security groups' anti-spoofing makes it one
        account = self.accounts.get(client)
        hosts = self.names.get((account, label)) if account is not None else None
        if hosts is None:
            return None
        return NOERROR, self._addresses(hosts, qtype)

    def _addresses(self, hosts, qtype):
        records = []
        for ipv4, ipv6 in hosts:
            if ipv4 and qtype in (A, ANY):
                records.append(_record(A, ipv4.packed))
            if ipv6 and qtype in (AAAA, ANY):
                records.append(_record(AAAA, ipv6.packed))
        return records

    def _lookup_reverse(self, address, qtype, account):
        name = None
        if address in self.listen and self.router_names:
            name = self.router_names[0]
        else:
            owner = self.reverse.get(address)
            if owner and account is not None and owner[0] == account:
                name = owner[1]
        if name is not None:
            return NOERROR, [_record(PTR, encode_name(self._fqdn(name)))] if qtype in (PTR, ANY) else []
        if not address.is_global:
            return NXDOMAIN, []
        return None


def handle(view, msg, client, server, tcp):
    """Returns (REPLY, message), (FORWARD, query) or (DROP, None)."""
    if len(msg) < 12:
        return DROP, None
    qid, flags, qdcount = struct.unpack("!3H", msg[:6])
    if flags & QR:
        return DROP, None
    if flags & OPCODE:
        return REPLY, _bare_reply(qid, flags, NOTIMP)
    if qdcount != 1:
        return REPLY, _bare_reply(qid, flags, FORMERR)
    try:
        query = Query(msg)
    except FormatError:
        return REPLY, _bare_reply(qid, flags, FORMERR)
    if query.version != 0:
        return REPLY, query.reply(BADVERS, [], tcp, False)
    answer = view.lookup(query, client, server)
    if answer is None:
        return FORWARD, query
    return REPLY, query.reply(answer[0], answer[1], tcp)


def _bound(sock, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise socket.timeout("connection deadline passed")
    sock.settimeout(min(TCP_IDLE, remaining))


def _receive_exactly(sock, size, deadline=None):
    data = b""
    while len(data) < size:
        if deadline is not None:
            _bound(sock, deadline)
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("connection closed")
        data += chunk
    return data


def _ask(server, message, qid, query, tcp):
    family = socket.AF_INET6 if ipaddress.ip_address(server[0]).version == 6 else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM if tcp else socket.SOCK_DGRAM) as sock:
        sock.settimeout(CACHE_TIMEOUT)
        sock.connect(server)
        if tcp:
            sock.sendall(struct.pack("!H", len(message)) + message)
            answer = _receive_exactly(sock, struct.unpack("!H", _receive_exactly(sock, 2))[0])
            return answer if query.matches(answer, qid) else None
        sock.send(message)
        deadline = time.monotonic() + CACHE_TIMEOUT
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            sock.settimeout(remaining)
            answer = sock.recv(65535)
            if query.matches(answer, qid):
                return answer


def relay(query, msg, tcp, server=CACHE):
    """Asks the caching dnsmasq, which forwards to the network's resolvers; None when it gives no answer."""
    qid = secrets.randbits(16)
    try:
        answer = _ask(server, struct.pack("!H", qid) + msg[2:], qid, query, tcp)
    except (OSError, ValueError):
        return None
    return None if answer is None else msg[:2] + answer[2:]


def _destination(ancillary):
    for level, kind, data in ancillary:
        if level == socket.IPPROTO_IP and kind == IP_PKTINFO and len(data) >= 12:
            return ipaddress.IPv4Address(data[8:12]), struct.unpack("=i", data[:4])[0]
        if level == socket.IPPROTO_IPV6 and kind == socket.IPV6_PKTINFO and len(data) >= 20:
            return ipaddress.IPv6Address(data[:16]), struct.unpack("=I", data[16:20])[0]
    return None, 0


class Server(object):

    def __init__(self, path):
        self.path = path
        self.signature = None
        self.view = View({})
        self.next_reload = 0.0
        self.relayed = threading.BoundedSemaphore(MAX_RELAYED)
        self.tcp_clients = threading.BoundedSemaphore(MAX_TCP_CLIENTS)
        self.tcp_lock = threading.Lock()
        self.tcp_held = {}
        self.workers = ThreadPoolExecutor(max_workers=UPSTREAM_WORKERS)

    def reload(self):
        now = time.monotonic()
        if now < self.next_reload:
            return
        self.next_reload = now + RELOAD_INTERVAL
        try:
            stat = os.stat(self.path)
        except OSError:
            return
        signature = (stat.st_ino, stat.st_size, stat.st_mtime_ns)
        if signature == self.signature:
            return
        try:
            with open(self.path) as f:
                view = View(json.load(f))
        except (OSError, ValueError, TypeError, AttributeError) as e:
            logging.warning("Keeping the previous names: cannot load %s: %s", self.path, e)
            return
        self.signature = signature
        self.view = view
        logging.info("Loaded %d names of %d accounts for %s", len(view.names), len(set(a for a, _ in view.names)), view.domain_name)

    def listeners(self):
        sockets = []
        for family, wildcard in ((socket.AF_INET, "0.0.0.0"), (socket.AF_INET6, "::")):
            try:
                udp = socket.socket(family, socket.SOCK_DGRAM)
                tcp = socket.socket(family, socket.SOCK_STREAM)
                tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                if family == socket.AF_INET6:
                    for sock in (udp, tcp):
                        sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                    udp.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_RECVPKTINFO, 1)
                else:
                    udp.setsockopt(socket.IPPROTO_IP, IP_PKTINFO, 1)
                udp.bind((wildcard, PORT))
                tcp.bind((wildcard, PORT))
                tcp.listen(64)
            except OSError as e:
                if family == socket.AF_INET:
                    raise
                logging.warning("Not serving IPv6 guests: %s", e)
                continue
            udp.setblocking(False)
            tcp.setblocking(False)
            sockets += [(udp, self.receive), (tcp, self.accept)]
        return sockets

    def serve(self):
        self.reload()
        selector = selectors.DefaultSelector()
        for sock, callback in self.listeners():
            selector.register(sock, selectors.EVENT_READ, callback)
        while True:
            for key, _ in selector.select(timeout=RELOAD_INTERVAL):
                key.data(key.fileobj)
            self.reload()

    def receive(self, sock):
        for _ in range(64):
            try:
                msg, ancillary, _, peer = sock.recvmsg(65535, socket.CMSG_SPACE(64))
            except (BlockingIOError, InterruptedError):
                return
            except OSError as e:
                logging.debug("receive failed: %s", e)
                return
            server, ifindex = _destination(ancillary)
            client = _address(peer[0])
            view = self.view
            if server is None or client is None or not view.accepts(client, server, ifindex):
                continue
            action, payload = handle(view, msg, client, server, False)
            if action == REPLY:
                self.send(sock, payload, peer, server, ifindex)
            elif action == FORWARD and self.relayed.acquire(blocking=False):
                self.workers.submit(self.relay_udp, sock, payload, msg, peer, server, ifindex)

    def send(self, sock, payload, peer, server, ifindex):
        if server.version == 4:
            ancillary = [(socket.IPPROTO_IP, IP_PKTINFO, struct.pack("=i4s4s", 0, server.packed, b"\x00" * 4))]
        else:
            ancillary = [(socket.IPPROTO_IPV6, socket.IPV6_PKTINFO, struct.pack("=16sI", server.packed, ifindex if server.is_link_local else 0))]
        try:
            sock.sendmsg([payload], ancillary, 0, peer)
        except OSError as e:
            logging.debug("send to %s failed: %s", peer[0], e)

    def relay_udp(self, sock, query, msg, peer, server, ifindex):
        try:
            answer = relay(query, msg, False) or query.reply(SERVFAIL, [], False, False)
            self.send(sock, answer, peer, server, ifindex)
        except Exception:
            logging.exception("relaying a query failed")
        finally:
            self.relayed.release()

    def accept(self, listener):
        try:
            conn, peer = listener.accept()
        except OSError:
            return
        if not self.hold_tcp(peer[0]):
            conn.close()
            return
        threading.Thread(target=self.serve_tcp, args=(conn, peer), daemon=True).start()

    def hold_tcp(self, client):
        with self.tcp_lock:
            held = self.tcp_held.get(client, 0)
            if held >= MAX_TCP_PER_CLIENT or not self.tcp_clients.acquire(blocking=False):
                return False
            self.tcp_held[client] = held + 1
            return True

    def release_tcp(self, client):
        with self.tcp_lock:
            held = self.tcp_held.pop(client) - 1
            if held:
                self.tcp_held[client] = held
            self.tcp_clients.release()

    def serve_tcp(self, conn, peer):
        deadline = time.monotonic() + TCP_DEADLINE
        try:
            local = conn.getsockname()
            client, server = _address(peer[0]), _address(local[0])
            ifindex = local[3] if len(local) > 3 else 0
            if client is None or server is None or not self.view.accepts(client, server, ifindex):
                return
            while True:
                try:
                    length = struct.unpack("!H", _receive_exactly(conn, 2, deadline))[0]
                except ConnectionError:
                    return
                msg = _receive_exactly(conn, length, deadline)
                view = self.view
                action, payload = handle(view, msg, client, server, True)
                if action == DROP:
                    return
                if action == FORWARD:
                    payload = relay(payload, msg, True) or payload.reply(SERVFAIL, [], True, False)
                _bound(conn, deadline)
                conn.sendall(struct.pack("!H", len(payload)) + payload)
        except OSError:
            pass
        finally:
            conn.close()
            self.release_tcp(peer[0])


def main(argv):
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    Server(argv[1] if len(argv) > 1 else CONFIG).serve()


if __name__ == "__main__":
    main(sys.argv)
