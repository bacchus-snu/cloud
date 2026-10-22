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

# Contributing to SNUCSE Cloud

SNUCSE Cloud is maintained by Bacchus. Issues and pull requests are welcome in
this repository; for anything else, write to <contact@bacchus.snucse.org>.

This is our own line of CloudStack 4.23. Changes made here stay here: they are
not proposed to Apache CloudStack, and questions about this repository go to
us, not to the Apache CloudStack mailing lists or issue tracker.

## Where changes go

- Changes to CloudStack are made in place in its tree, in upstream's style.
  Checkstyle runs in Maven's `validate` phase; new source files carry the same
  license header as their neighbours.
- Our own files (manual, images, packaging) go under `snucse/` only.
- A change and the manual pages it affects (`snucse/docs/ko`) may go in one
  commit, so the manual never describes a behaviour the code does not have.
- Database schema changes go into the newest upgrade step that has not yet
  run on the production database, as idempotent SQL (the `IDEMPOTENT_*`
  procedures under `engine/schema/src/main/resources/META-INF/db/procedures`).
  Once a step has run there it is frozen: the next change adds a new step and
  bumps the code version (see Versions in [README.md](README.md)).
- A code version bump is one mechanical change: `mvn versions:set
  -DnewVersion=<version> -DgenerateBackupPoms=false -DprocessAllModules`, the
  non-POM files `tools/build/setnextversion.sh` edits except `debian/changelog`,
  and a new top entry in `debian/changelog` (the script would rewrite the old
  entries' versions).

## Before you send a change

- Run the tests of the modules you touched, for example
  `mvn -pl engine/schema -am test -Dtest=<Test> -Dsurefire.failIfNoSpecifiedTests=false`.
- For the manual, check the book builds: `mdbook build snucse/docs/ko`.
- Write the commit message as what the change does and why.

## Commits and releases

Maintainers review pull requests and make the commits on `main`; commits on
`main` are GPG-signed. Releases are tags `snucse-<pom>-<N>`.
