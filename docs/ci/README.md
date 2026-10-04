# Full-stack validation workflow

`full-stack-validation.workflow.yml` is a reviewed candidate for `.github/workflows/android-ci.yml`; GitHub does not execute workflows in this documentation directory.

It runs the web build and tests, Android unit tests and APK build, shared Kotlin modules, PostgreSQL integration tests, and a real Kotlin → browser → Kotlin ZIP exchange. The existing active workflow still runs the Android unit job.

The current GitHub OAuth credential can push code and create PRs, but GitHub rejected the workflow-file update because it lacks the `workflow` scope. The candidate is kept here for review without changing account permissions. Activate it by copying it to `.github/workflows/android-ci.yml` using a credential already authorized to update repository workflows.
