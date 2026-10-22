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

"""gpudiscovery.sh against a stand-in PCI sysfs tree (stdlib unittest only).

The script reads /sys/bus/pci/devices directly, so each run happens in its own
user and mount namespace with a fixture tree mounted there; lspci, virsh and
xmlstarlet are stand-ins on PATH.
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

KVM_SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(KVM_SCRIPTS, "gpudiscovery.sh")
CONTROL = os.path.join(KVM_SCRIPTS, "..", "..", "..", "..", "debian", "control")

LSPCI = """\
00:02.0 "VGA compatible controller [0300]" "Intel Corporation [8086]" "AlderLake-S GT1 [4680]" -r0c "Intel Corporation [8086]" "Device [7d1a]"
01:00.0 "VGA compatible controller [0300]" "NVIDIA Corporation [10de]" "GA102GL [RTX A6000] [2230]" -ra1 "NVIDIA Corporation [10de]" "Device [1459]"
02:00.0 "VGA compatible controller [0300]" "ASPEED Technology, Inc. [1a03]" "ASPEED Graphics Family [2000]" -r52 "ASPEED Technology, Inc. [1a03]" "ASPEED Graphics Family [2000]"
03:00.0 "3D controller [0302]" "NVIDIA Corporation [10de]" "GA100 [A100 SXM4 40GB] [20b0]" -ra1 "NVIDIA Corporation [10de]" "Device [134f]"
04:00.0 "VGA compatible controller [0300]" "NVIDIA Corporation [10de]" "GA102GL [A40] [2235]" -ra1 "NVIDIA Corporation [10de]" "Device [145a]"
05:00.0 "Ethernet controller [0200]" "Intel Corporation [8086]" "Ethernet Controller X710 [1572]" -r02 "Intel Corporation [8086]" "Device [0000]"
06:00.0 "VGA compatible controller [0300]" "NVIDIA Corporation [10de]" "AD102GL [L40] [26b5]" -ra1 "NVIDIA Corporation [10de]" "Device [169d]"
"""

# device -> (driver, has mediated device types, SR-IOV virtual functions supported, enabled)
DEVICES = {
    "0000:00:02.0": ("i915", False, 7, 0),
    "0000:01:00.0": ("vfio-pci", False, 0, 0),
    "0000:02:00.0": ("ast", False, 0, 0),
    "0000:03:00.0": ("nvidia", False, 0, 0),
    "0000:04:00.0": ("nvidia", True, 0, 0),
    "0000:05:00.0": ("i40e", False, 64, 0),
    "0000:06:00.0": ("nvidia", False, 32, 32),
}

STUBS = {
    "lspci": '#!/bin/sh\ncat "$(dirname "$0")/lspci.out"\n',
    "virsh": '#!/bin/sh\n[ "$1" = list ] && exit 0\nexit 1\n',
    "xmlstarlet": "#!/bin/sh\nexit 0\n",
}


def mount_namespaces_available():
    try:
        return subprocess.run(["unshare", "-rm", "true"], capture_output=True).returncode == 0
    except OSError:
        return False


class TestGpuDiscovery(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.devices = os.path.join(self.root, "devices")
        self.bin = os.path.join(self.root, "bin")
        os.mkdir(self.devices)
        os.mkdir(self.bin)
        for address, (driver, mdev, totalvfs, numvfs) in DEVICES.items():
            device = os.path.join(self.devices, address)
            os.mkdir(device)
            os.symlink("../../../bus/pci/drivers/" + driver, os.path.join(device, "driver"))
            if mdev:
                os.mkdir(os.path.join(device, "mdev_supported_types"))
            with open(os.path.join(device, "sriov_totalvfs"), "w") as f:
                f.write("%d\n" % totalvfs)
            with open(os.path.join(device, "sriov_numvfs"), "w") as f:
                f.write("%d\n" % numvfs)
        for name, body in STUBS.items():
            path = os.path.join(self.bin, name)
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)
        with open(os.path.join(self.bin, "lspci.out"), "w") as f:
            f.write(LSPCI)

    def tearDown(self):
        shutil.rmtree(self.root)

    @unittest.skipUnless(mount_namespaces_available(), "needs unprivileged user and mount namespaces")
    def test_vga_class_gpus_are_reported_only_when_reserved_for_guests(self):
        result = subprocess.run(["unshare", "-rm", "sh", "-c",
                                 'mount --bind "$1" /sys/bus/pci/devices && exec env PATH="$2:/usr/bin:/bin" bash "$3"',
                                 "sh", self.devices, self.bin, SCRIPT],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

        reported = [gpu["pci_address"] for gpu in json.loads(result.stdout)["gpus"]]

        self.assertEqual(reported, ["01:00.0", "03:00.0", "04:00.0", "06:00.0"])

    def test_agent_package_depends_on_the_tools_the_script_runs(self):
        with open(CONTROL) as f:
            stanza = re.search(r"^Package: cloudstack-agent\n(.*?)(?:\n\n|\Z)", f.read(), re.DOTALL | re.MULTILINE).group(1)
        depends = re.search(r"^Depends: (.*)$", stanza, re.MULTILINE).group(1)
        packages = {re.split(r"[\s(]", alternative.strip())[0] for alternative in re.split(r"[,|]", depends)}

        self.assertTrue({"pciutils", "xmlstarlet"} <= packages, depends)


if __name__ == "__main__":
    unittest.main()
