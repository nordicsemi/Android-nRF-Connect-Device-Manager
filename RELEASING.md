# Releasing nRF Connect Device Manager

A release is made by the **Release** GitHub Action. It runs the unit tests, generates the documentation
and the README, creates the tag, publishes the libraries to Maven Central and deploys the sample
app to Google Play (Internal track). The version of the release is the name of the tag, e.g.
`3.5.0-alpha01`.

## How to release

1. Merge everything that should be released to `main`.
2. Open **Actions → Release → Run workflow**.
3. Choose the branch in **Use workflow from**, e.g. `main`.
4. Enter the **version**, e.g. `3.5.0-alpha01`, and run the workflow. To try it first, tick
   **Dry run** (see [Dry run](#dry-run)).

The workflow runs in stages. Each one is a job that depends on the previous one, so the graph of the
run shows which stage failed:

1. **Check.** Checks that it runs on a branch, that the version looks like `3.5.0` or
   `3.5.0-alpha01`, and that the tag does not exist yet.
2. **Test.** Runs the unit tests (`./gradlew testDebugUnitTest`).
3. **Docs.** Runs `./gradlew dokkaGenerate` for the given version and generates `README.md` from
   `moustache/README.mo`. The README shows the version without the pre-release suffix (`3.5.0` for
   `3.5.0-alpha01`). The result is committed to the branch as `Docs for <version>`. The
   documentation is written to `docs/html`, which is published by GitHub Pages.
4. **Tag.** Creates the tag `<version>` on the commit with the documentation, and pushes it.
5. **Publish** and **Deploy**, which run in parallel:
   * **Publish** builds the tag, publishes all the libraries and releases the staging repository on
     Maven Central.
   * **Deploy** builds the signed app bundle from the tag and uploads it to the Internal track of
     Google Play using Fastlane (`bundle exec fastlane deployInternal`).

Pushing a tag does not start the **Release** workflow, and the only way to release is to run it.

After the workflow finished:

* check that **Publish** and **Deploy** succeeded,
* the artifacts appear on [Maven Central](https://central.sonatype.com/search?q=no.nordicsemi.android)
  within a few minutes to an hour,
* create a release on GitHub from the existing tag, with the release notes.

## Dry run

With **Dry run** ticked the workflow goes through all the stages, but nothing leaves the runner:

1. **Check** passes also for a version that was already released (it only warns that the tag exists).
2. **Test** runs the unit tests.
3. **Docs** generates the documentation and the README, and prints the number of changed files. It
   does not commit or push them.
4. **Tag** creates the tag locally, on the runner, and does not push it.
5. **Publish** builds and signs the libraries into the local Maven repository of the runner
   (`./gradlew publishToMavenLocal`). This checks the signing key and the secrets, but does not
   upload anything.
   **Deploy** builds and signs the app bundle, and Google Play only validates it
   (`validate_only`), nothing is uploaded.

Use it to check a change of the release workflow, or the secrets, before a real release. The default
is not a dry run.

## How the version is set

The Nordic Gradle plugins use the version name from (in this order):

1. the `releaseVersion` Gradle property, e.g. `-PreleaseVersion=3.5.0-alpha01`,
2. the `RELEASE_VERSION` environment variable,
3. the latest git tag,
4. `0.0.0` if none of the above is available.

The **Release** workflow sets `RELEASE_VERSION`, so the version does not depend on the tags. The
version code of the app is based on the number of commits, so **Deploy** checks out the whole history.

## Fastlane

The GitHub runners do not have Fastlane installed. **Deploy** installs it with `ruby/setup-ruby`
from `Gemfile` and `Gemfile.lock`, and runs it with `bundle exec`. To update Fastlane, run
`bundle update fastlane` and commit the `Gemfile.lock`.

## Troubleshooting

* **A stage failed.** Open the run, fix the cause (e.g. a failing test needs a pull request first),
  and use **Re-run failed jobs**. The stages that succeeded are not repeated: the tag of the version,
  and the documentation commit, stay. Only the failed stage and the following ones run.
  If the cause needs a change in the code, it is simpler to delete the tag and the documentation
  commit, if they were created, and run the workflow again.
* **Check: the tag already exists.** The version was released, or a previous run failed after it
  created the tag. Delete the tag if nothing was published (`git push origin :refs/tags/<version>`),
  or use a new version.
* **Docs: the push was rejected.** The branch changed during the release, or it is protected and
  does not allow pushes from GitHub Actions. Run the workflow again.
* **Publish or Deploy failed.** Use **Re-run failed jobs**. The tag is built again. The other one of
  the two is not repeated if it succeeded.
