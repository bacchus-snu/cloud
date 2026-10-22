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

"""The router's boot-time DNS and DHCP setup (setup/common.sh) and its guest DNS units (stdlib unittest only).

parse_cmd_line, setup_dnsmasq and setup_radvd are taken from common.sh with
their absolute paths moved under a temporary root; dnsmasq, chpasswd and
openssl are stand-ins.
"""

import os
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

import guest_dns

DEBIAN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "debian")
COMMON_SH = os.path.join(DEBIAN, "opt", "cloud", "bin", "setup", "common.sh")
UNITS = os.path.join(DEBIAN, "etc", "systemd", "system")

CMDLINE = ("template=domP name=r-7-VM eth0ip=10.1.1.1 eth0mask=255.255.255.0 gateway=10.1.1.254 type=dhcpsrvr "
           "domain=vm.example.internal dns1=10.9.9.9 dns2=10.9.9.10 cidrsize=24 dhcprange=10.1.1.1")
IPV6 = " eth0ip6=fd00:1::1 eth0ip6prelen=64 ip6dns1=fd00:9::9"
PER_ACCOUNT = " dnsperaccount=true"
UNIT_NAMES = ["cloud-guest-dns", "cloud-guest-dns-cache"]

STUBS = {
    "dnsmasq": "#!/bin/sh\necho 'Dnsmasq version 2.89  Copyright (c) 2000-2022 Simon Kelley'\n",
    "chpasswd": "#!/bin/sh\ncat >/dev/null\n",
    "openssl": "#!/bin/sh\necho stand-in\n",
}


def shell_function(text, name):
    lines = text.splitlines()
    if name + "() {" not in lines:
        return ""
    start = lines.index(name + "() {")
    end = lines.index("}", start)
    return "\n".join(lines[start:end + 1]) + "\n"


def resolvers(conf):
    return [line for line in conf if line.startswith(("dhcp-option=6,", "dhcp-option=option6:dns-server,"))]


class TestSetupDnsmasq(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        for d in ("etc/dnsmasq.d", "var/cache/cloud", "tmp", "bin"):
            os.makedirs(os.path.join(self.root, d))
        for template in ("dnsmasq.conf.tmpl", "radvd.conf.tmpl"):
            shutil.copy(os.path.join(DEBIAN, "etc", template), os.path.join(self.root, "etc", template))
        with open(os.path.join(self.root, "etc", "hosts"), "w") as f:
            f.write("127.0.0.1\tlocalhost\n")
        for name, body in STUBS.items():
            path = os.path.join(self.root, "bin", name)
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)

    def tearDown(self):
        shutil.rmtree(self.root)

    def path(self, *parts):
        return os.path.join(self.root, *parts)

    def run_setup(self, cmdline, steps):
        with open(self.path("var", "cache", "cloud", "cmdline"), "w") as f:
            f.write(cmdline + "\n")
        with open(COMMON_SH) as f:
            common = f.read()
        script = "log_it() { :; }\nenable_radvd() { :; }\n"
        for function in ("parse_cmd_line", "dns_per_account", "setup_dnsmasq", "setup_radvd"):
            script += shell_function(common, function)
        script = re.sub("/var/cache/cloud/|/etc/|/tmp/", lambda m: self.root + m.group(0), script)
        script += "parse_cmd_line\n" + steps
        env = dict(os.environ, PATH=self.path("bin") + os.pathsep + os.environ.get("PATH", ""))
        run = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True)
        self.assertEqual(0, run.returncode, run.stderr)

    def setup_dnsmasq(self, cmdline):
        self.run_setup(cmdline, "setup_dnsmasq\n")
        with open(self.path("etc", "dnsmasq.conf")) as f:
            conf = [line.rstrip("\n") for line in f]
        services = {}
        for name in ("enabled_svcs", "disabled_svcs"):
            path = self.path("var", "cache", "cloud", name)
            services[name] = []
            if os.path.exists(path):
                with open(path) as f:
                    services[name] = f.read().split()
        return conf, services

    def radvd(self, cmdline):
        if os.path.exists(self.path("etc", "radvd.conf")):
            os.unlink(self.path("etc", "radvd.conf"))
        self.run_setup(cmdline, "setup_radvd 0 fd00:1::1 64 false\n")
        with open(self.path("etc", "radvd.conf")) as f:
            return [line.strip() for line in f if line.strip().startswith("RDNSS ")]

    def test_a_restarted_router_starts_without_the_previous_boots_names(self):
        stale = [self.path("var", "cache", "cloud", name) for name in ("guest_dns.json", "guest_dns.resolv")]
        for path in stale:
            with open(path, "w") as f:
                f.write("{}")
        self.setup_dnsmasq(CMDLINE + PER_ACCOUNT)
        for path in stale:
            self.assertFalse(os.path.exists(path), path)

    def test_dnsmasq_keeps_dhcp_only_on_a_network_shared_by_accounts(self):
        conf, services = self.setup_dnsmasq(CMDLINE + PER_ACCOUNT)
        self.assertTrue("port=0" in conf, "dnsmasq.conf leaves dnsmasq's DNS on: it answers every account's names from /etc/hosts and its leases")
        for unit in UNIT_NAMES:
            self.assertIn(unit, services["enabled_svcs"])
            self.assertNotIn(unit, services["disabled_svcs"])

    def test_the_router_is_the_only_resolver_dhcp_names_on_a_network_shared_by_accounts(self):
        conf, _ = self.setup_dnsmasq(CMDLINE + IPV6 + PER_ACCOUNT)
        self.assertEqual(["dhcp-option=6,10.1.1.1", "dhcp-option=option6:dns-server,[::]"], resolvers(conf))
        conf, _ = self.setup_dnsmasq(CMDLINE + " redundant_router=1 guestgw=10.1.1.254" + PER_ACCOUNT)
        self.assertEqual(["dhcp-option=6,10.1.1.254"], resolvers(conf))
        conf, _ = self.setup_dnsmasq(CMDLINE + IPV6)
        self.assertEqual(["dhcp-option=6,10.1.1.1,10.9.9.9,10.9.9.10", "dhcp-option=option6:dns-server,[::],[fd00:9::9]"], resolvers(conf))

    def test_router_advertisements_name_the_router_as_the_only_resolver(self):
        self.assertEqual(["RDNSS fd00:1::1"], self.radvd(CMDLINE + IPV6 + PER_ACCOUNT))
        self.assertEqual(["RDNSS fd00:9::9"], self.radvd(CMDLINE + IPV6))

    def test_dhcp_is_otherwise_the_same_for_every_kind_of_network(self):
        per_account, _ = self.setup_dnsmasq(CMDLINE + IPV6 + PER_ACCOUNT)
        stock, _ = self.setup_dnsmasq(CMDLINE + IPV6)
        self.assertFalse("port=0" in stock)
        self.assertEqual([line for line in stock if line not in resolvers(stock)],
                         [line for line in per_account if line != "port=0" and line not in resolvers(per_account)])

    def test_the_per_account_dns_stays_off_elsewhere_and_its_files_are_dropped(self):
        stale = [self.path("var", "cache", "cloud", name) for name in ("guest_dns.json", "guest_dns.resolv")]
        for path in stale:
            with open(path, "w") as f:
                f.write("{}")
        conf, services = self.setup_dnsmasq(CMDLINE)
        for unit in UNIT_NAMES:
            self.assertIn(unit, services["disabled_svcs"])
            self.assertNotIn(unit, services["enabled_svcs"])
        for path in stale:
            self.assertFalse(os.path.exists(path), path)

    def test_no_router_dns_when_the_network_uses_external_dns(self):
        conf, services = self.setup_dnsmasq(CMDLINE + PER_ACCOUNT + " useextdns=true")
        self.assertTrue("port=0" in conf)
        self.assertEqual(["dhcp-option=6,10.9.9.9,10.9.9.10"], resolvers(conf))
        for unit in UNIT_NAMES:
            self.assertIn(unit, services["disabled_svcs"])
            self.assertNotIn(unit, services["enabled_svcs"])


def unit(name):
    sections = {}
    current = None
    with open(os.path.join(UNITS, name + ".service")) as f:
        for line in f:
            line = line.strip()
            if line.startswith("["):
                current = sections.setdefault(line.strip("[]"), {})
            elif "=" in line:
                key, value = line.split("=", 1)
                current.setdefault(key, []).append(value)
    return sections


class TestGuestDnsUnits(unittest.TestCase):

    def test_the_cache_is_a_dnsmasq_on_loopback_only_that_forwards_to_the_networks_resolvers(self):
        service = unit("cloud-guest-dns-cache")
        self.assertEqual([guest_dns.RESOLV], service["Unit"]["ConditionPathExists"])
        argv = shlex.split(service["Service"]["ExecStart"][0])
        self.assertEqual("/usr/sbin/dnsmasq", argv[0])
        for argument in ("--keep-in-foreground", "--conf-file=/dev/null", "--listen-address=%s" % guest_dns.CACHE[0], "--bind-interfaces",
                         "--port=%d" % guest_dns.CACHE[1], "--no-hosts", "--resolv-file=%s" % guest_dns.RESOLV):
            self.assertIn(argument, argv)
        self.assertEqual([], [a for a in argv if "dhcp" in a or a.startswith(("--interface", "--server", "--address"))])
        pid = [a for a in argv if a.startswith("--pid-file=")]
        self.assertEqual(["--pid-file=/run/%s/dnsmasq.pid" % service["Service"]["RuntimeDirectory"][0]], pid)

    def test_both_units_keep_restarting_and_the_responder_keeps_only_what_it_needs(self):
        for name in UNIT_NAMES:
            self.assertEqual(["0"], unit(name)["Unit"].get("StartLimitIntervalSec"), name)
            self.assertEqual(["on-failure"], unit(name)["Service"].get("Restart"), name)
            self.assertGreaterEqual(int(unit(name)["Service"]["RestartSec"][0]), 5, name)
        service = unit("cloud-guest-dns")["Service"]
        self.assertEqual(["CAP_NET_BIND_SERVICE"], service.get("CapabilityBoundingSet"))
        self.assertEqual(["AF_INET AF_INET6 AF_UNIX"], service.get("RestrictAddressFamilies"))

    def test_the_responder_starts_with_its_cache(self):
        service = unit("cloud-guest-dns")
        self.assertIn("cloud-guest-dns-cache.service", service["Unit"]["Wants"][0].split())
        self.assertIn("cloud-guest-dns-cache.service", service["Unit"]["After"][0].split())
        self.assertEqual([guest_dns.CONFIG], service["Unit"]["ConditionPathExists"])
        self.assertEqual(["/opt/cloud/bin/guest_dns.py", guest_dns.CONFIG], shlex.split(service["Service"]["ExecStart"][0]))


if __name__ == '__main__':
    unittest.main()
