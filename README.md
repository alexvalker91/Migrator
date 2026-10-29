# Migrator

AI engine that migrates Appium/TestNG autotests from Android to iOS, and a sandbox project (`migrator`) to try it on.
The design is in `ARCHITECTURE_SPEC.md`, the state of the work in `PROGRESS.md`.

## Build

```
./mvnw -pl migration-engine verify   # engine: unit tests, ArchUnit layer rules (no device needed)
./mvnw verify                        # also builds the sandbox; its Appium tests are skipped without -Pe2e
```

Modules: `migration-engine` (`com.project.migration`, the engine) and `migrator` (the sandbox target project; on a real
project the engine is pointed at it with `--project-root`).

## Engine configuration

`engine-defaults.json` holds the defaults; a project file passed with `--config` is merged over it (objects merge key by
key, arrays and scalars are replaced). Secrets (`DIAL_API_KEY`, `MOBITRU_AUTH_TOKEN`) are read from the environment only.

| Section | What it controls |
|---|---|
| `tiers`, `model_pricing` | DIAL deployments per LLM tier, failover chains and prices |
| `mcp_servers` | per platform: command, `env` (`${VAR}` is taken from the engine's environment), `startup_calls`, `app_id` (needed to restart the app between passes) |
| `agent_loop` | limits of LLM tool-calling runs and the MCP tool whitelist |
| `maven` | `executable` (default `./mvnw`, else `mvn`), `extra_args` (`-Pe2e`), `test_module`, timeouts of compile, one test, one class |
| `gate` | per test: `max_test_minutes`, `max_test_tokens`, `environment_retries`, `max_consecutive_environment_failures` |
| `global_components` | cross-screen components: `{"BottomNavBar": "com.app.pages.BottomNav"}`; empty by default |
| `stability_runs`, `max_healing_attempts` | the 3/3 gate and the number of self-healing attempts |

The default `mcp_servers` env needs `MIGRATION_ANDROID_CAPABILITIES` and `MIGRATION_IOS_CAPABILITIES` (paths of the
Appium capabilities files). Set `mcp_servers.<PLATFORM>.app_id` in your project file.

## Contract with the target project

The engine drives the project through Maven and expects:

- `-Pe2e -Dtest=Class#method -Dplatform=iOS|Android` runs one test; `BaseTest` reads `platform`.
- TestNG groups `android-only`, `ios-only` and `ios-needs-fix`. `-Dtest=...` bypasses the suite XML, so class runs pass
  `-DexcludedGroups` themselves: iOS excludes `android-only,ios-needs-fix`, Android excludes `ios-only`.
- `BasePage` offers the cross-platform helpers listed in `FrameworkContract.HELPER_SIGNATURES`
  (`scrollToElementByText`, `navigateBack`, `safeHideKeyboard`, ...).

### Failure evidence

When a test fails, the project's TestNG listener must save, next to the test results:

- `target/screenshots/<testMethod>_<timestamp>.png`
- `target/page-source/<testMethod>_<timestamp>.xml` (raw `pageSource`)

`MavenTestNgRunnerAdapter` picks the files written during the run, and self-healing uses them. Without them healing still
works from the stack trace and the stored snapshots, with a warning. The sandbox's `ScreenshotListener` does both; on a
real project this is one extra line in the existing listener.

## Result of a class migration

| Exit code | Status | Meaning |
|---|---|---|
| 0 | `SUCCESS` | every test migrated, final iOS run and Android regression guard green |
| 1 | `COMPLETED_WITH_MANUAL_FIX` | some tests are tagged `ios-needs-fix`, the rest is green |
| 2 | `STAGE5_FAILED` | final iOS class run or Android regression guard is red; not pushed unless push-on-regression |
| 3 | `ABORTED` | dirty working tree, uncompilable generated code, or the environment is down |

The knowledge base `.migration/` and the reports `migration_reports/` are git-ignored. The migration branch is
`migrate/ios-<TestClass>`; a re-run continues it from the last commit, and refuses to start on uncommitted changes.
