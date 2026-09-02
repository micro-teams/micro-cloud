# Releasing

How a merged change reaches `microcloud-prod`, and when it gets a version number. Every push to
`main` already builds the deployable bundle (`build.yml`, artifact `microcloud-deploy`, kept for one
day); deploying is handing that bundle to the operator, and a release is a tag on a commit that has
been deployed and has run stably. Nothing here is automated end to end: the deploy is a judgement
call, made by whoever merged.

## 1. Merge

Merge when CI is green and you judge the change ready. The ruleset asks for a code-reviewers
approval that an author cannot give their own PR, so `gh pr merge` is refused; the merge button on
the PR page, used as an admin, or the REST endpoint works:

```sh
gh api -X PUT repos/micro-teams/micro-cloud/pulls/<n>/merge -f merge_method=merge
```

Release notes are generated from merged PR titles in step 5, so write the title as the changelog
line you want to read later.

## 2. Hand the bundle to the operator

The `main` build's `microcloud-deploy` artifact is the bundle described in `deploy/README.md`.
The operator is the agent **MicroCloud运维** on [microteams.app](https://microteams.app) (group chat
`/chats/252`); it cannot open the artifact page, which needs a GitHub login, so give it the direct
download URL, which is the redirect target of the artifact's zip endpoint:

```sh
# the newest bundle built from main
gh api repos/micro-teams/micro-cloud/actions/artifacts \
  --jq '.artifacts[] | select(.name=="microcloud-deploy") | [.id, .workflow_run.head_branch, .workflow_run.head_sha[0:9], .expires_at] | @tsv' | head -3
# its direct URL (an Azure SAS link that expires within minutes; fetch it right before sending)
curl -sI -H "Authorization: Bearer $(gh auth token)" \
  https://api.github.com/repos/micro-teams/micro-cloud/actions/artifacts/<id>/zip | grep -i '^location:'
```

The message is one line: 「更新 <url>」. The agent downloads the zip, unpacks it over the deployed
bundle, restarts the stack as in `deploy/README.md`'s three steps, waits for every service to report
healthy, and reports back in the chat. Two things it has hit: a download that stopped at 41 MB
with HTTP 200, which it resumes with `curl -C -` and checks with `unzip -t`, and an expired link,
which needs a fresh one.

## 3. Templates are not redeployed by the bundle

Deploying replaces the files under `templates/` on the server, which is enough for **VM** machines:
their `init-machine.py` is piped over SSH from the deployed copy at every provisioning. An **LXC**
machine runs the `init-machine.py` baked into the template image that was **uploaded to the
placement**, and that copy on Proxmox does not change when the bundle does.

So a change under `templates/lxc/` also needs the rebuilt image re-uploaded to every placement,
from the super-admin console or with `POST /machine/template/{id}/upload` and the `placementId`.
Proxmox refuses to overwrite a template file that already exists, so someone with root on the node
has to move the old `/var/lib/vz/template/cache/<name>.tar.zst` aside first; a tenant-scoped token
cannot.
Until that is done, every LXC machine keeps being born from the old script while VMs already run
the new one, which looks like a bug that only reproduces on one machine kind.

## 4. Verify before calling it stable

Create one machine per kind the change touches, since LXC and VM differ as step 3 says, with the
options the change is about, and watch it reach `status=running` and `aiStatus=ready`, or whatever
state you expect. A machine that fails is worth keeping until the backend log has been read; the
operator reads it on request. "Stable" means the change has served real machines without a
regression, which is a judgement, not a timer.

## 5. Version and release

Once stable, bump the product version everywhere it is stamped:

```sh
scripts/version.sh            # print the current version and verify all files agree
scripts/version.sh X.Y.Z      # set it (VERSION, pom.xml, MicroCloud-API.yml, frontend/package*.json)
```

Open that as its own PR (`chore: version X.Y.Z`), merge it, deploy its bundle as in step 2, then
publish the release with GitHub's generated notes:

```sh
gh release create vX.Y.Z --repo micro-teams/micro-cloud --target <merge sha> --generate-notes
```

The notes list every PR merged since the previous release, so a release can carry several
changes; the version bump PR is the last of them.
