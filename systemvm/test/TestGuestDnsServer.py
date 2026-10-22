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

"""guest_dns.py serving real sockets (stdlib unittest only).

The test runs this file again inside an unprivileged user and network
namespace. The router's guest addresses and the clients' addresses are local
addresses there; a stand-in for the caching dnsmasq listens where the responder
sends every name it does not answer itself.
"""

import json
import os
import random
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(os.path.dirname(HERE), "debian", "opt", "cloud", "bin")
RESPONDER = os.path.join(BIN, "guest_dns.py")

ROUTER = ["10.1.1.1", "10.1.1.2", "fd00:1::1"]
CLIENTS = ["10.1.1.10", "10.1.1.12", "10.1.1.20", "10.1.1.99", "10.2.2.2", "fd00:1::10"]


def namespaces_available():
    try:
        return subprocess.run(["unshare", "-rn", "ip", "link", "set", "lo", "up"], capture_output=True).returncode == 0
    except OSError:
        return False


def sh(command):
    subprocess.run(command.split(), check=True)


def wait_for(predicate, seconds=10.0):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


def exchange(src, dst, message, tcp=False, timeout=1.5):
    family = socket.AF_INET6 if ":" in src else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM if tcp else socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.bind((src, 0))
        sock.connect((dst, 53))
        if not tcp:
            sock.send(message)
            return sock.recv(65535)
        sock.sendall(struct.pack("!H", len(message)) + message)
        data = b""
        while len(data) < 2 or len(data) < 2 + struct.unpack("!H", data[:2])[0]:
            chunk = sock.recv(65535)
            if not chunk:
                return None
            data += chunk
        return data[2:]
    except (socket.timeout, ConnectionError):
        return None
    finally:
        sock.close()


def run_cache(workdir):
    """Stand-in cache: answers www.example.org, truncates big.example.org over UDP without EDNS, fails fail.example.org."""
    import guest_dns
    from TestGuestDns import parse, A, TXT
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(guest_dns.CACHE)
    tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp.bind(guest_dns.CACHE)
    tcp.listen(8)
    log = open(os.path.join(workdir, "cache.log"), "a")

    def answer(query, over_tcp):
        q = parse(query)
        name, qtype = q["question"][0], q["question"][1]
        log.write(name + "\n")
        log.flush()
        end = 12 + len(name) + 2 + 4 if name else 17
        header = bytearray(query[:12])
        header[2] |= 0x80
        header[3] = 0x80
        question = query[12:end]
        records = b""
        count = 0
        if name == "www.example.org" and qtype == A:
            records, count = b"\xc0\x0c" + struct.pack("!HHIH", A, 1, 60, 4) + socket.inet_aton("192.0.2.80"), 1
        elif name == "big.example.org" and qtype == TXT:
            if not over_tcp and q["opt"] is None:
                header[2] |= 0x02
            else:
                text = b"\xc8" + b"t" * 200
                records = (b"\xc0\x0c" + struct.pack("!HHIH", TXT, 1, 60, len(text)) + text) * 5
                count = 5
        elif name == "fail.example.org":
            header[3] |= 2
        else:
            header[3] |= 3
        header[4:12] = struct.pack("!4H", 1, count, 0, 0)
        return bytes(header) + question + records

    with open(os.path.join(workdir, "cache.ready"), "w"):
        pass
    import selectors
    selector = selectors.DefaultSelector()
    selector.register(udp, selectors.EVENT_READ)
    selector.register(tcp, selectors.EVENT_READ)
    while True:
        for key, _ in selector.select():
            if key.fileobj is udp:
                data, addr = udp.recvfrom(65535)
                udp.sendto(answer(data, False), addr)
            else:
                conn, _ = tcp.accept()
                conn.settimeout(2)
                length = struct.unpack("!H", conn.recv(2))[0]
                data = b""
                while len(data) < length:
                    data += conn.recv(length - len(data))
                reply = answer(data, True)
                conn.sendall(struct.pack("!H", len(reply)) + reply)
                conn.close()


def run_inside(workdir):
    sys.path[:0] = [BIN, HERE]
    import guest_dns
    from TestGuestDns import make_query, parse, CMDLINE, ENTRIES, DOMAIN, A, AAAA, PTR, TXT, NS

    sh("ip link set lo up")
    for address in ROUTER + CLIENTS:
        if ":" in address:
            sh("ip -6 addr add %s/128 dev lo nodad" % address)
        else:
            sh("ip addr add %s/32 dev lo" % address)

    children = []
    try:
        children.append(subprocess.Popen([sys.executable, "-B", os.path.abspath(__file__), "--cache", workdir]))
        if not wait_for(lambda: os.path.exists(os.path.join(workdir, "cache.ready"))):
            raise RuntimeError("the stand-in cache did not start")

        cmdline = dict(CMDLINE, loip6="fd00:1::1", loip6prelen="64")
        devices = [{"ip": "10.1.1.1/24", "dev": "lo", "dnsmasq": True, "gateway": "10.1.1.254"},
                   {"ip": "10.1.1.2/24", "dev": "lo:1", "dnsmasq": True, "gateway": "10.1.1.254"}]
        conf = guest_dns.config_from_dhcp(cmdline, ENTRIES, devices, False)
        conf_path = os.path.join(workdir, "guest_dns.json")
        guest_dns.write_config(conf_path, conf)

        log = open(os.path.join(workdir, "responder.log"), "w")
        children.append(subprocess.Popen([sys.executable, "-B", RESPONDER, conf_path], stdout=log, stderr=log))

        def ask(src, name, qtype, dst="10.1.1.1", tcp=False, edns=None):
            data = exchange(src, dst, make_query(name, qtype, qid=random.randint(0, 0xFFFF), edns=edns), tcp)
            return None if data is None else parse(data)

        if not wait_for(lambda: ask("10.1.1.10", "data-server", A) is not None):
            raise RuntimeError("responder did not answer")

        def short(r):
            if r is None:
                return None
            answers = [[n, t, len(v) if isinstance(v, bytes) else v] for n, t, v in r["answers"]]
            return {"rcode": r["rcode"], "tc": r["tc"], "answers": answers}

        alpha, beta, stranger, outsider, alpha6 = "10.1.1.10", "10.1.1.20", "10.1.1.99", "10.2.2.2", "fd00:1::10"
        alpha_secondary = "10.1.1.12"
        results = {
            "alpha_alpha": short(ask(alpha, "alpha." + DOMAIN, A)),
            "alpha_beta": short(ask(alpha, "beta." + DOMAIN, A)),
            "beta_beta": short(ask(beta, "beta." + DOMAIN, A)),
            "alpha_beta_ptr": short(ask(alpha, "20.1.1.10.in-addr.arpa", PTR)),
            "beta_beta_ptr": short(ask(beta, "20.1.1.10.in-addr.arpa", PTR)),
            "alpha_beta_bare": short(ask(alpha, "beta", A)),
            "alpha_alpha_bare": short(ask(alpha, "alpha2", A)),
            "alpha_secondary_alpha": short(ask(alpha_secondary, "alpha2." + DOMAIN, A)),
            "alpha_secondary_beta": short(ask(alpha_secondary, "beta." + DOMAIN, A)),
            "stranger_alpha": short(ask(stranger, "alpha." + DOMAIN, A)),
            "stranger_data_server": short(ask(stranger, "data-server", A)),
            "alias_data_server": short(ask(alpha, "data-server", A, dst="10.1.1.2")),
            "alpha_tcp_alpha": short(ask(alpha, "alpha." + DOMAIN, A, tcp=True)),
            "alpha_tcp_beta": short(ask(alpha, "beta." + DOMAIN, A, tcp=True)),
            "alpha6_alpha": short(ask(alpha6, "alpha." + DOMAIN, AAAA, dst="fd00:1::1")),
            "alpha6_beta": short(ask(alpha6, "beta." + DOMAIN, AAAA, dst="fd00:1::1")),
            "alpha6_tcp_beta": short(ask(alpha6, "beta." + DOMAIN, A, dst="fd00:1::1", tcp=True)),
            "alpha_external": short(ask(alpha, "www.example.org", A)),
            "alpha6_external": short(ask(alpha6, "www.example.org", A, dst="fd00:1::1")),
            "alpha_missing_external": short(ask(alpha, "missing.example.org", A)),
            "alpha_big_udp": short(ask(alpha, "big.example.org", TXT)),
            "alpha_big_tcp": short(ask(alpha, "big.example.org", TXT, tcp=True)),
            "alpha_fail": short(ask(alpha, "fail.example.org", A)),
            "alpha_tld_ns": short(ask(alpha, "com", NS)),
            "outsider": short(ask(outsider, "www.example.org", A)),
            "not_a_router_address": short(ask(alpha, "www.example.org", A, dst=stranger)),
        }

        moved = dict(conf, hosts=[dict(h, account="11") if h["name"] == "beta" else h for h in conf["hosts"]])
        guest_dns.write_config(conf_path, moved)
        wait_for(lambda: (short(ask(alpha, "beta." + DOMAIN, A)) or {}).get("rcode") == 0, 5)
        results["alpha_beta_after_move"] = short(ask(alpha, "beta." + DOMAIN, A))
        results["beta_beta_after_move"] = short(ask(beta, "beta." + DOMAIN, A))

        with open(os.path.join(workdir, "cache.log")) as f:
            results["cache_saw"] = sorted(set(f.read().split()))
        results["responder_alive"] = children[-1].poll() is None
        print(json.dumps(results))
    finally:
        for child in reversed(children):
            child.kill()
            child.wait()


@unittest.skipUnless(namespaces_available(), "needs unprivileged user and network namespaces")
class TestGuestDnsServer(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        workdir = tempfile.mkdtemp()
        try:
            run = subprocess.run(["unshare", "-rn", sys.executable, "-B", os.path.abspath(__file__), "--inside", workdir],
                                 capture_output=True, text=True, timeout=180)
            responder_log = ""
            if os.path.exists(os.path.join(workdir, "responder.log")):
                with open(os.path.join(workdir, "responder.log")) as f:
                    responder_log = f.read()
        finally:
            shutil.rmtree(workdir)
        if run.returncode != 0:
            raise AssertionError("namespace run failed:\n%s\n%s" % (run.stderr[-3000:], responder_log[-2000:]))
        cls.results = json.loads(run.stdout.strip().splitlines()[-1])

    def r(self, key):
        return self.results[key]

    def test_own_account_names_resolve_over_udp_tcp_and_ipv6(self):
        self.assertEqual([["alpha.vm.example.internal", 1, "10.1.1.10"]], self.r("alpha_alpha")["answers"])
        self.assertEqual([["beta.vm.example.internal", 1, "10.1.1.20"]], self.r("beta_beta")["answers"])
        self.assertEqual([["alpha2", 1, "10.1.1.11"]], self.r("alpha_alpha_bare")["answers"])
        self.assertEqual([["alpha.vm.example.internal", 1, "10.1.1.10"]], self.r("alpha_tcp_alpha")["answers"])
        self.assertEqual([["alpha.vm.example.internal", 28, "fd00:1::10"]], self.r("alpha6_alpha")["answers"])
        self.assertEqual([["20.1.1.10.in-addr.arpa", 12, "beta.vm.example.internal"]], self.r("beta_beta_ptr")["answers"])
        self.assertEqual([["alpha2.vm.example.internal", 1, "10.1.1.11"]], self.r("alpha_secondary_alpha")["answers"])

    def test_other_accounts_names_do_not_exist(self):
        for key in ("alpha_beta", "alpha_beta_ptr", "alpha_beta_bare", "stranger_alpha", "alpha_tcp_beta", "alpha6_beta", "alpha6_tcp_beta", "alpha_secondary_beta"):
            self.assertEqual({"rcode": 3, "tc": False, "answers": []}, self.r(key), key)

    def test_the_routers_names_resolve_for_every_client_from_the_address_asked(self):
        self.assertEqual([["data-server", 1, "10.1.1.1"]], self.r("stranger_data_server")["answers"])
        self.assertEqual([["data-server", 1, "10.1.1.2"]], self.r("alias_data_server")["answers"])

    def test_other_names_are_answered_through_the_local_cache(self):
        self.assertEqual([["www.example.org", 1, "192.0.2.80"]], self.r("alpha_external")["answers"])
        self.assertEqual([["www.example.org", 1, "192.0.2.80"]], self.r("alpha6_external")["answers"])
        self.assertEqual(3, self.r("alpha_missing_external")["rcode"])
        self.assertEqual(2, self.r("alpha_fail")["rcode"])
        self.assertTrue(self.r("alpha_big_udp")["tc"])
        self.assertEqual(5, len(self.r("alpha_big_tcp")["answers"]))

    def test_vm_names_never_leave_the_router(self):
        self.assertIn("www.example.org", self.r("cache_saw"))
        self.assertIn("com", self.r("cache_saw"))
        for name in self.r("cache_saw"):
            self.assertNotIn(name, ("alpha", "alpha2", "beta", "data-server"))
            self.assertFalse(name.endswith("vm.example.internal"), name)
            self.assertFalse(name.endswith(".in-addr.arpa"), name)

    def test_only_guest_clients_asking_a_router_address_get_an_answer(self):
        self.assertIsNone(self.r("outsider"))
        self.assertIsNone(self.r("not_a_router_address"))

    def test_a_changed_map_applies_without_a_restart(self):
        self.assertEqual([["beta.vm.example.internal", 1, "10.1.1.20"]], self.r("alpha_beta_after_move")["answers"])
        self.assertEqual([["beta.vm.example.internal", 1, "10.1.1.20"]], self.r("beta_beta_after_move")["answers"])
        self.assertTrue(self.r("responder_alive"))


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == "--inside":
        run_inside(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "--cache":
        sys.path[:0] = [BIN, HERE]
        run_cache(sys.argv[2])
    else:
        unittest.main()
