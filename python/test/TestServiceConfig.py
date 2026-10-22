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

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
LIB = os.path.join(REPO, "python", "lib")
SETUP_AGENT = os.path.join(REPO, "agent", "bindir", "cloud-setup-agent.in")
sys.path.insert(0, LIB)

from cloudutils import serviceConfig  # noqa: E402

# the defaults file as the libvirt-daemon-system package installs it
DEBIAN_DEFAULTS = """# Defaults for libvirtd initscript (/etc/init.d/libvirtd)
# This is a POSIX shell fragment

# Start libvirtd to handle qemu/kvm:
start_libvirtd="yes"

# options passed to libvirtd, add "-l" to listen on tcp
#libvirtd_opts=""

# The command line arguments passed to libvirtd when running under systemd
#LIBVIRTD_ARGS="--timeout 120"
"""

SETUP_DRIVER = """
import json, sys
sys.path.insert(0, sys.argv[1])
from cloudutils import serviceConfig
calls = []
serviceConfig.bash = lambda command: calls.append(" ".join(command.split()))
serviceConfig.configureLibvirtConfig = lambda tls_enabled=True, cfg=None: None
class Env:
    secure = sys.argv[2] == "secure"
class SysCfg:
    env = Env()
serviceConfig.libvirtConfigUbuntu(SysCfg()).setupLiveMigration()
print(json.dumps({"defaults": open("/etc/default/libvirtd").read(), "calls": calls}))
"""


def mount_namespaces_available():
    try:
        return subprocess.run(["unshare", "-rm", "true"], capture_output=True).returncode == 0
    except OSError:
        return False


def libvirtd_args(defaults):
    return re.findall(r"^LIBVIRTD_ARGS=(.*)$", defaults, re.MULTILINE)


class TestLibvirtdListener(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.defaults = os.path.join(self.root, "libvirtd")
        self.units = os.path.join(self.root, "units")
        os.mkdir(self.units)
        for unit in ("libvirtd.socket", "virtqemud.socket"):
            open(os.path.join(self.units, unit), "w").close()
        self.calls = []
        self.bash = serviceConfig.bash
        serviceConfig.bash = lambda command: self.calls.append(" ".join(command.split()))

    def tearDown(self):
        serviceConfig.bash = self.bash
        shutil.rmtree(self.root)

    def write_defaults(self, text):
        with open(self.defaults, "w") as f:
            f.write(text)

    def read_defaults(self):
        with open(self.defaults) as f:
            return f.read()

    def test_listener_on_the_systemd_unit_arguments(self):
        self.write_defaults(DEBIAN_DEFAULTS)

        serviceConfig.configureLibvirtdListener(defaultsFile=self.defaults, unitDir=self.units)

        self.assertEqual(libvirtd_args(self.read_defaults()), ['"--listen"'])
        self.assertIn('start_libvirtd="yes"', self.read_defaults())
        self.assertTrue(any(c.startswith("/bin/systemctl mask libvirtd.socket ") for c in self.calls), self.calls)
        self.assertTrue(any(c.startswith("/bin/systemctl mask virtqemud.socket ") for c in self.calls), self.calls)

    def test_other_arguments_stay_and_the_idle_timeout_goes(self):
        self.write_defaults('LIBVIRTD_ARGS="--timeout 120 --config /etc/libvirt/libvirtd.conf"\n')

        serviceConfig.configureLibvirtdListener(defaultsFile=self.defaults, unitDir=self.units)
        serviceConfig.configureLibvirtdListener(defaultsFile=self.defaults, unitDir=self.units)

        self.assertEqual(libvirtd_args(self.read_defaults()), ['"--config /etc/libvirt/libvirtd.conf --listen"'])

    def test_quoted_arguments_stay_whole_and_a_trailing_comment_is_no_argument(self):
        self.write_defaults('LIBVIRTD_ARGS="--timeout 120 --config \'/etc/libvirt/my libvirtd.conf\'"  # set by the operator\n')

        serviceConfig.configureLibvirtdListener(defaultsFile=self.defaults, unitDir=self.units)
        serviceConfig.configureLibvirtdListener(defaultsFile=self.defaults, unitDir=self.units)

        self.assertEqual(libvirtd_args(self.read_defaults()), ['"--config \'/etc/libvirt/my libvirtd.conf\' --listen"'])

    def test_setup_agent_certificate_path_turns_the_listener_on(self):
        with open(SETUP_AGENT) as f:
            script = f.read()
        secure_path = re.search(r"if not options\.auto and options\.secure:\n(.*?)sys\.exit\(0\)", script, re.DOTALL)
        self.assertIsNotNone(secure_path)
        self.assertIn("configureLibvirtdListener()", secure_path.group(1))

    def run_agent_setup(self, mode):
        etc_default = os.path.join(self.root, "etc-default")
        os.mkdir(etc_default)
        with open(os.path.join(etc_default, "libvirtd"), "w") as f:
            f.write(DEBIAN_DEFAULTS)
        driver = os.path.join(self.root, "driver.py")
        with open(driver, "w") as f:
            f.write(SETUP_DRIVER)

        result = subprocess.run(["unshare", "-rm", "sh", "-c",
                                 'mount --bind "$1" /etc/default && mount --bind "$2" /lib/systemd/system && exec "$3" -I -B "$4" "$5" "$6"',
                                 "sh", etc_default, self.units, sys.executable, driver, LIB, mode],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    @unittest.skipUnless(mount_namespaces_available(), "needs unprivileged user and mount namespaces")
    def test_agent_setup_turns_the_listener_on(self):
        output = self.run_agent_setup("secure")

        self.assertEqual(libvirtd_args(output["defaults"]), ['"--listen"'])
        self.assertTrue(any(c.startswith("/bin/systemctl mask libvirtd.socket ") for c in output["calls"]), output["calls"])

    @unittest.skipUnless(mount_namespaces_available(), "needs unprivileged user and mount namespaces")
    def test_agent_setup_without_tls_opens_no_listener(self):
        output = self.run_agent_setup("insecure")

        self.assertEqual(libvirtd_args(output["defaults"]), [])
        self.assertIn('#LIBVIRTD_ARGS="--timeout 120"', output["defaults"])
        self.assertTrue(any(c.startswith("/bin/systemctl mask libvirtd.socket ") for c in output["calls"]), output["calls"])


if __name__ == "__main__":
    unittest.main()
