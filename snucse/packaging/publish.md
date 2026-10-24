# Publishing the agent packages

The KVM hosts install `cloudstack-agent` and `cloudstack-common` from
<https://repo.snucse.org/debian>, distribution `trixie`, component `main`, at the
version `cloudstack_version` pins in `bacchus-snu/cd-manifests`
(`cloudstack/hosts/inventory.yaml`). The management server does not use the
repository; it runs from the image the release workflow pushes.

The repository is reprepro and Caddy in one pod, `bacchus-repo-0` in the
`bacchus-repo` namespace of atrium (`argocd/atrium/bacchus-repo` in
cd-manifests); the Bacchus howto "repo.snucse.org 관리" describes it.
Publishing is manual, and so is signing: the repository's GPG key is kept in
Vault and imported only for the time of the publish.

Publish a release after its workflow run has finished, and before the hosts'
`cloudstack_version` moves to it. When a release changes what the agent and
the management server exchange, the agents are updated first.

## Steps

The release, its Debian version and a kubectl for atrium:

```console
$ tag=snucse-4.23.0.100-1
$ v=4.23.0.100+snucse.1
$ k="kubectl --context bacchus-atrium -n bacchus-repo"
```

1. Download the two packages and check them against the release's checksums:

   ```console
   $ mkdir debs-$tag && cd debs-$tag
   $ gh release download $tag -R bacchus-snu/cloud -p "cloudstack-agent_${v}_all.deb" -p "cloudstack-common_${v}_all.deb" -p SHA256SUMS
   $ sha256sum -c --ignore-missing SHA256SUMS
   ```

2. Copy them into the reprepro container:

   ```console
   $ $k exec bacchus-repo-0 -c reprepro -- mkdir -p /deb
   $ $k cp cloudstack-agent_${v}_all.deb bacchus-repo-0:/deb/cloudstack-agent_${v}_all.deb -c reprepro
   $ $k cp cloudstack-common_${v}_all.deb bacchus-repo-0:/deb/cloudstack-common_${v}_all.deb -c reprepro
   ```

3. Open a shell there and import the key, which step 6 removed after the last
   publish: run `gpg --import`, paste the value of "repo.snucse.org GPG key"
   from Vault `passwords/regular-members` and end the input with Ctrl-D. Then
   `gpg -K` lists it.

   ```console
   $ $k exec -it bacchus-repo-0 -c reprepro -- bash
   # gpg --import
   # gpg -K
   ```

4. On the first publish, and whenever the repository configuration may have
   changed, check the `trixie` stanza:

   ```console
   # cat /srv/repos/debian/conf/distributions
   ```

   - `Components` includes `main` and `Architectures` includes `amd64`; the
     packages are `all`, which reprepro adds to every listed architecture.
   - `SignWith` is set, to the key `gpg -K` shows (or `yes` for gpg's default
     key). Without it the index is unsigned and the hosts' `apt-get update`
     refuses it.
   - `Limit` is absent or `1` (the default): one version of a package per
     distribution, so including a new version drops the previous one from the
     index and the pool. This page assumes that. Any other value keeps older
     versions as well; then the hosts can go back with apt alone, and the
     volume grows by a release each time.

5. Include both packages. reprepro signs the new index; when gpg asks for the
   passphrase (also when step 3 imports the key), it is "deprecated/New 리눅스
   서버" in the same Vault entry.

   ```console
   # v=4.23.0.100+snucse.1
   # reprepro -b /srv/repos/debian includedeb trixie /deb/cloudstack-common_${v}_all.deb /deb/cloudstack-agent_${v}_all.deb
   # reprepro -b /srv/repos/debian list trixie cloudstack-agent
   # reprepro -b /srv/repos/debian list trixie cloudstack-common
   ```

   Both lists show `$v`, and only `$v`. The lists read reprepro's database,
   not the published index: when signing fails, `includedeb` still records the
   packages, ends with "There have been errors!", and leaves the previous index
   and the previous packages in place. Running `includedeb` again then only
   skips the packages it already has. With the key imported, export the index
   and drop the packages it no longer names:

   ```console
   # reprepro -b /srv/repos/debian export trixie
   # reprepro -b /srv/repos/debian deleteunreferenced
   ```

6. Clean up: remove the copied packages and the secret key (it stays in
   Vault).

   ```console
   # rm /deb/cloudstack-agent_${v}_all.deb /deb/cloudstack-common_${v}_all.deb
   # gpg --delete-secret-keys <fingerprint from gpg -K>
   # exit
   ```

7. Check the published index from outside:

   ```console
   $ curl -fsS https://repo.snucse.org/debian/dists/trixie/main/binary-amd64/Packages | grep -A2 -E '^Package: cloudstack-(agent|common)$'
   ```

   On a host, `apt-get update` succeeds (the signature checks) and
   `apt-cache policy cloudstack-agent` lists `$v`.

## Going back to an earlier release

With one version per package, reprepro does not replace a package by an older
version. To put the previous release back, remove both packages, then do steps
1–7 with the previous release's tag and version:

```console
# reprepro -b /srv/repos/debian remove trixie cloudstack-agent cloudstack-common
```

The packages of every release stay downloadable from its GitHub release.

## Space

The repository volume (`packages-bacchus-repo-0`) holds the current agent and
common packages, about 200 MB per release, and briefly two releases during
step 5. Check its use with
`$k exec bacchus-repo-0 -c reprepro -- df -h /srv/repos`.
