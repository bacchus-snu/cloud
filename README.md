<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# SNUCSE Cloud

SNUCSE Cloud (<https://cloud.snucse.org>) provides virtual machines to members
of the SNU Department of Computer Science and Engineering. It is run by Bacchus
and is based on Apache CloudStack 4.23.0.0, customized for this cloud; it is not
a release of, or endorsed by, the Apache Software Foundation. Apache, Apache
CloudStack and CloudStack are trademarks of the Apache Software Foundation.

This repository holds the CloudStack source we build from with our changes,
and everything else the cloud needs that is not part of the cluster manifests
(see `bacchus-snu/cd-manifests`): the user manual and the container images.

## Layout

```
api/ server/ engine/ plugins/ ui/ debian/ ...
                      the CloudStack tree, laid out as upstream; our changes
                      to CloudStack are made in place
snucse/docs/<lang>/   user manual (mdBook), published at docs.cloud.snucse.org
snucse/images/<name>/ container images, ghcr.io/bacchus-snu/cloud/<name>
.github/workflows/    CI for this repository
```

Files of our own live only under `snucse/`; the CloudStack directories hold
CloudStack code and our patches to it.

## Versions

The code stays on the CloudStack 4.23 line. Its version is numeric, because
CloudStack compares it with the database version to decide which schema
upgrades to run.

| What | Form | First |
| --- | --- | --- |
| Code (POM) | `4.23.0.<n>`, `n` from 100 | `4.23.0.100` |
| Debian packages | `<pom>+snucse.<N>` | `4.23.0.100+snucse.1` |
| Container images | `<pom>-snucse.<N>` | `4.23.0.100-snucse.1` |
| Git tags | `snucse-<pom>-<N>` | `snucse-4.23.0.100-1` |

`N` counts the releases of one code version. The code version moves only with
a database schema step: `4.23.0.100` upgrades a 4.23.0.0 database, and once a
step has run on the production database it is frozen, so the next schema
change takes a new step and the next version (`4.23.0.101`, ...). Upstream
uses the fourth component for its security releases; starting at 100 keeps
ours apart from them.

## Building

Build and test the CloudStack tree as upstream does ([INSTALL.md](INSTALL.md));
it needs Java 17 and Maven 3. To run the tests of the modules a change
touches, for example:

```console
$ mvn -pl engine/schema -am test -Dtest=DatabaseUpgradeCheckerTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Documentation

`snucse/docs/ko/` is the Korean user manual. Preview it locally with mdBook:

```console
$ mdbook serve snucse/docs/ko
```

Pushing changes under `snucse/docs/` to `main` publishes the book to GitHub
Pages (`.github/workflows/docs.yaml`), served as <https://docs.cloud.snucse.org>
(CNAME in `bacchus-snu/infra`, custom domain in this repository's Pages
settings).

The full repository is large. To work on the manual only, use a shallow, sparse
clone; it fetches the files under `snucse/docs` and the top-level files, and
nothing else:

```console
$ git clone --depth 1 --filter=blob:none --sparse https://github.com/bacchus-snu/cloud.git
$ cd cloud
$ git sparse-checkout set snucse/docs
```

Leave out `--depth 1` if you need `git log` or `git blame` on the manual.

`snucse/` was imported with its history from the former
`bacchus-snu/cloudstack` repository, so `git log --follow` on its files reaches
back past the import.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues privately, as
[SECURITY.md](SECURITY.md) describes.

## License

Licensed under the Apache License, Version 2.0; see [LICENSE](LICENSE) and
[NOTICE](NOTICE).

## Notice of Cryptographic Software (from Apache CloudStack)

The notice below is Apache CloudStack's; its export classification statement is
the Apache Software Foundation's about its own distribution, not about builds
from this repository.

This distribution includes cryptographic software. The country in which you currently
reside may have restrictions on the import, possession, use, and/or re-export to another
country, of encryption software. BEFORE using any encryption software, please check your
country's laws, regulations and policies concerning the import, possession, or use, and
re-export of encryption software, to see if this is permitted. See [The Wassenaar Arrangement](http://www.wassenaar.org/)
for more information.

The U.S. Government Department of Commerce, Bureau of Industry and Security (BIS), has
classified this software as Export Commodity Control Number (ECCN) 5D002.C.1, which
includes information security software using or performing cryptographic functions with
asymmetric algorithms. The form and manner of this Apache Software Foundation distribution
makes it eligible for export under the License Exception ENC Technology Software
Unrestricted (TSU) exception (see the BIS Export Administration Regulations, Section
740.13) for both object code and source code.

The following provides more details on the included cryptographic software:

* CloudStack makes use of JaSypt cryptographic libraries.
* CloudStack requires a MySQL-compatible database (MariaDB or MySQL), and uses native database encryption functionality.
* CloudStack makes use of the Bouncy Castle general-purpose encryption library.
* CloudStack can optionally interact with and control OpenSwan-based VPNs.
* CloudStack has a dependency on and makes use of JSch - a java SSH2 implementation.
