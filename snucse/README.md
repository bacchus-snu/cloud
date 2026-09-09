# cloudstack

Apache CloudStack on the waiter cluster: container images, user documentation
and tooling that are not part of the cluster manifests (see
`bacchus-snu/cd-manifests`).

```
images/<name>/   Dockerfile for ghcr.io/bacchus-snu/cloudstack/<name>
docs/<lang>/     user documentation (mdBook), published at docs.cloud.snucse.org
```

## Building an image

Push a tag named `<image>/<version>`; GitHub Actions builds
`images/<image>` and publishes `ghcr.io/bacchus-snu/cloudstack/<image>:<version>`.

```console
$ git tag management/4.23.0.0 -m 'management/4.23.0.0'
$ git push origin main management/4.23.0.0
```

## Images

- `management`: CloudStack management server 4.23.0.0 (Ubuntu 24.04, upstream
  packages pinned by SHA256, runs the server in the foreground without systemd).

## Documentation

`docs/ko/` is the Korean book. Preview it locally with mdBook:

```console
$ mdbook serve docs/ko
```

Pushing changes under `docs/` to `main` publishes the book to GitHub Pages
(`.github/workflows/docs.yaml`), served as <https://docs.cloud.snucse.org>
(CNAME in `bacchus-snu/infra`, custom domain in the repository's Pages settings).
