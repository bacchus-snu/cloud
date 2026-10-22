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

"""CsDhcp keeps the per-account guest DNS map current (stdlib unittest only).

The data bags live in a temporary directory; where netaddr is not installed a
stand-in provides the names the router modules import.
"""

import ipaddress
import json
import logging
import os
import shutil
import sys
import tempfile
import types
import unittest

logging.basicConfig(level=logging.CRITICAL)

try:
    import netaddr  # noqa: F401
except ImportError:
    stand_in = types.ModuleType("netaddr")
    stand_in.IPAddress = ipaddress.ip_address
    stand_in.IPNetwork = ipaddress.ip_interface
    sys.modules["netaddr"] = stand_in

import merge  # noqa: E402
import netaddr  # noqa: E402

DATABAGS = tempfile.mkdtemp()
merge.DataBag.DPATH = DATABAGS

import cs_dhcp  # noqa: E402
import guest_dns  # noqa: E402
from cs import CsHelper  # noqa: E402
from cs.CsConfig import CsConfig  # noqa: E402
from cs.CsDhcp import CsDhcp  # noqa: E402
from cs.CsFile import CsFile  # noqa: E402
from TestGuestDns import CMDLINE, ENTRIES, DEVICES, entry  # noqa: E402


class GuestAddress(object):

    def get_guest_ip(self):
        return "10.1.1.1"


class TestCsDhcpGuestDns(unittest.TestCase):

    def setUp(self):
        merge.DataBag.DPATH = DATABAGS
        self.map = os.path.join(DATABAGS, "guest_dns.json")
        self.resolv = os.path.join(DATABAGS, "guest_dns.resolv")
        self.saved = guest_dns.CONFIG, guest_dns.RESOLV, CsHelper.start_if_stopped
        self.started = []
        guest_dns.CONFIG, guest_dns.RESOLV = self.map, self.resolv
        CsHelper.start_if_stopped = self.started.append
        CsConfig.cl = None
        bag = {"id": "dhcpentry"}
        for e in ENTRIES:
            bag[e["ipv4_address"]] = e
        with open(os.path.join(DATABAGS, "dhcpentry.json"), "w") as f:
            json.dump(bag, f)

    def tearDown(self):
        guest_dns.CONFIG, guest_dns.RESOLV, CsHelper.start_if_stopped = self.saved
        CsConfig.cl = None
        for name in os.listdir(DATABAGS):
            os.unlink(os.path.join(DATABAGS, name))

    def router(self, cmdline):
        with open(os.path.join(DATABAGS, "cmdline.json"), "w") as f:
            json.dump({"id": "cmdline", "config": cmdline}, f)
        CsConfig.cl = None
        config = CsConfig()
        config.ips = GuestAddress()
        return CsDhcp("dhcpentry", config)

    def apply(self, cmdline):
        dhcp = self.router(cmdline)
        dhcp.devinfo = [dict(d) for d in DEVICES]
        dhcp.configure_guest_dns()

    def dns_option(self, cmdline):
        dhcp = self.router(cmdline)
        dhcp.hosts = {}
        dhcp.conf = CsFile(os.path.join(DATABAGS, "cloud.conf"))
        dhcp.devinfo = [{"ip": "10.1.1.1/24", "dev": "eth0", "dnsmasq": True, "gateway": "10.1.1.254",
                         "network": netaddr.IPNetwork("10.1.1.1/24"), "mtu": "1450"}]
        dhcp.configure_server()
        return [line.strip() for line in dhcp.conf.new_config if line.startswith("dhcp-option=tag:interface-eth0-0,6,")]

    def test_the_router_is_the_only_resolver_dhcp_names_on_a_network_shared_by_accounts(self):
        self.assertEqual(["dhcp-option=tag:interface-eth0-0,6,10.1.1.1"], self.dns_option(CMDLINE))
        redundant = dict(CMDLINE, redundant_router="true", guestgw="10.1.1.254")
        self.assertEqual(["dhcp-option=tag:interface-eth0-0,6,10.1.1.254"], self.dns_option(redundant))
        stock = dict(CMDLINE)
        del stock["dnsperaccount"]
        self.assertEqual(["dhcp-option=tag:interface-eth0-0,6,10.1.1.1,10.9.9.9,10.9.9.10"], self.dns_option(stock))

    def test_entries_and_their_accounts_reach_the_map_and_the_responder_runs(self):
        self.apply(CMDLINE)
        self.assertTrue(os.path.exists(self.map), "the router wrote no per-account map")
        with open(self.map) as f:
            conf = json.load(f)
        self.assertEqual(guest_dns.config_from_dhcp(CMDLINE, ENTRIES, DEVICES, False), conf)
        self.assertEqual({"alpha": "11", "beta": "22"}, {h["name"]: h["account"] for h in conf["hosts"] if h["name"] in ("alpha", "beta")})
        with open(self.resolv) as f:
            self.assertEqual("nameserver 10.9.9.9\nnameserver 10.9.9.10\nnameserver fd00:9::9\n", f.read())
        self.assertEqual([guest_dns.CACHE_SERVICE, guest_dns.SERVICE], self.started)

    def test_a_failing_map_never_stops_dhcp_from_being_applied(self):
        def broken(path, conf):
            raise OSError("no space left on device")
        saved = guest_dns.write_config
        guest_dns.write_config = broken
        try:
            self.apply(CMDLINE)
        finally:
            guest_dns.write_config = saved
        self.assertEqual([guest_dns.CACHE_SERVICE, guest_dns.SERVICE], self.started)

    def test_other_networks_keep_dnsmasq_alone(self):
        stock = dict(CMDLINE)
        del stock["dnsperaccount"]
        self.apply(stock)
        self.assertFalse(os.path.exists(self.map))
        self.assertFalse(os.path.exists(self.resolv))
        self.assertEqual([], self.started)



class TestDhcpEntryMerge(unittest.TestCase):
    """A removal takes away the entry of its own MAC address only."""

    def test_a_late_removal_leaves_the_entry_that_now_holds_the_address(self):
        newer = entry("newer", 22, "10.1.1.50", mac="02:00:00:00:00:b2")
        bag = cs_dhcp.merge({"id": "dhcpentry"}, newer)
        bag = cs_dhcp.merge(bag, entry("older", 11, "10.1.1.50", mac="02:00:00:00:00:a1", remove=True))
        self.assertEqual(newer, bag.get("10.1.1.50"))

    def test_a_removal_takes_away_its_macs_entry(self):
        bag = cs_dhcp.merge({"id": "dhcpentry"}, entry("older", 11, "10.1.1.50", mac="02:00:00:00:00:a1"))
        bag = cs_dhcp.merge(bag, entry("older", 11, "10.1.1.50", mac="02:00:00:00:00:a1", remove=True))
        self.assertEqual({"id": "dhcpentry"}, bag)

    def test_a_new_address_replaces_the_macs_old_entry(self):
        bag = cs_dhcp.merge({"id": "dhcpentry"}, entry("vm", 11, "10.1.1.50", mac="02:00:00:00:00:a1"))
        moved = entry("vm", 11, "10.1.1.51", mac="02:00:00:00:00:a1")
        bag = cs_dhcp.merge(bag, moved)
        self.assertEqual({"id": "dhcpentry", "10.1.1.51": moved}, bag)


def tearDownModule():
    shutil.rmtree(DATABAGS)


if __name__ == '__main__':
    unittest.main()
