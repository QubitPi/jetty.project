---
name: 'Release Process'
about: 'COMMITTER ONLY: Managing the Jetty release process'
title: 'Jetty Releases 9.4.x, 10.0.y, 11.0.y, 12.0.y'
assignees: ''
labels: Build

---

**Jetty Versions:**
This release process will produce releases:

**Target Date:**

**Tasks:**
- [x] Create the release(s) issue.
- [ ] Update the target Jetty version(s) in the issue.  
- [ ] Update the target release date in the issue.
- [ ] Link this issue to the target [GitHub Project(s)](https://github.com/jetty/jetty.project/projects).
- [ ] Assign this issue to a "release manager".
- [ ] Review [draft security advisories](https://github.com/jetty/jetty.project/security/advisories). Ensure that issues are created and assigned to GitHub Projects to capture any advisories that will be announced.
- [ ] Update [GitHub Project(s)](https://github.com/jetty/jetty.project/projects)
  - [ ] Create new project for the next releases (not this release).
  - [ ] Ensure new project is public (not private)
  - [ ] Freeze the target [GitHub Project(s)](https://github.com/jetty/jetty.project/projects) by editing their names to "Jetty X.Y.Z FROZEN"
  - [ ] Review the issues/PRs assigned to the target [GitHub Project(s)](https://github.com/jetty/jetty.project/projects).  Any tasks that are not-yet-started are moved to next releases.
- [ ] Review dependabot status. [Manually](https://github.com/jetty/jetty.project/network/updates) run dependabot if needed and review resulting PRs for inclusion.
      Such updates should only be included in the week before a release if there is a compelling security or stability reason to do so.
- [ ] Wait 24 hours from last change to the issues/PRs included in FROZEN GitHub Project(s).
- [ ] Verify target [project(s)](https://github.com/jetty/jetty.project/projects) are complete.
- [ ] Verify that branch `jetty-10.0.x` is merged to branch `jetty-11.0.x`.
- [ ] Assign issue to "build manager", who will stage the releases.
  - [ ] Create and use branches `release/<ver>` to perform version specific release work from.
  - [ ] Ensure `git fetch --tags` (as we potentially rewrite tag when re staging local tag can be out of sync and this command will fail and so fail the release script)
  - [ ] Ensure `VERSION.txt` additions for each release will be meaningful, descriptive, correct text.
  - [ ] Stage 9.4 release with Java 11.
  - [ ] Stage 10 release with Java 21.
  - [ ] Stage 11 release with Java 21.
  - [ ] Stage 12 release with Java 22. 
  - [ ] Push release branches `release/<ver>` to to https://github.com/jetty/jetty.project
  - [ ] Push release tags `jetty-<ver>` to https://github.com/jetty/jetty.project
  - [ ] Edit a draft release (for each Jetty release) in GitHub (https://github.com/jetty/jetty.project/releases). Content is generated with the "changelog tool".
- [ ] Assign issue to "test manager", who will oversee the testing of the staged releases.
  - [ ] Test [CometD](https://github.com/cometd/cometd).
  - [ ] Test [Reactive HttpClient](https://github.com/jetty-project/jetty-reactive-httpclient).
  - [ ] Test [Load Generator](https://github.com/jetty-project/jetty-load-generator).
  - [ ] Test [Jetty Docker images](https://github.com/jetty/jetty.docker).
  - [ ] Test other [Jetty OSS integrations](https://jenkins.webtide.net/job/external_oss).
  - [ ] Check [TCK CI](https://jenkins.webtide.net/job/tck).
  - [ ] Test sponsored integrations.
  - [ ] Check CI for performance regressions.
  - [ ] Assign issue back to "release manager".
- [ ] Collect release votes from committers.
- [ ] Promote staged releases.
- [ ] Merge release branches back to main branches and delete release branches.
- [ ] Verify release existence in Maven Central by triggering the Jenkins builds of CometD.
- [ ] Update Jetty versions on the website ( follow instructions in [jetty-website](https://github.com/jetty/jetty.website/blob/main/README.adoc) ).
  - [ ] Update (or check) [Download](https://jetty.org/download.html) page is updated.
  - [ ] Update (or check) documentation page(s) are updated.
- [ ] Publish GitHub Releases.
- [ ] Prepare release announcement for mailing lists.
- [ ] Send release announcement to mailing lists ([@jetty-announce](https://accounts.eclipse.org/mailing-list/jetty-announce), [@jetty-dev](https://accounts.eclipse.org/mailing-list/jetty-dev), and [@jetty-users](https://accounts.eclipse.org/mailing-list/jetty-users))
- [ ] Publish any [security advisories](https://github.com/jetty/jetty.project/security/advisories).
  - [ ] Edit `VERSION.txt` to include any actual CVE number next to correspondent issue.
  - [ ] Edit any issues for CVEs in github with their CVE number
- [ ] Upgrade versions in SDKMAN. [Example PR](https://github.com/sdkman/sdkman-db-migrations/pull/711)
- [ ] Notify downstream maintainers.
  - [ ] Eclipse p2 maintainer.
  - [ ] Docker maintainer.
  - [ ] Jenkins maintainer.
