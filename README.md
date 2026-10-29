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

## Run

```
./mvnw -pl migration-engine package -DskipTests      # builds migration-engine/target/migration-engine-<version>-cli.jar
export DIAL_API_KEY=...                              # EPAM AI DIAL; DIAL_HOST is optional
export MIGRATION_ANDROID_CAPABILITIES=/path/capabilities-android.json
export MIGRATION_IOS_CAPABILITIES=/path/capabilities-ios.json
java -jar migration-engine/target/migration-engine-1.0-SNAPSHOT-cli.jar CardsTest --project-root /path/to/project
```

`java -jar ... --help` lists the options (`--config`, `--show-reasoning`, `--no-push`, `--push-on-regression`). Several
classes can be given; they run one after the other and an aborted class stops the batch. A second engine on the same
project is refused (`.migration/engine.lock`). The first `package` downloads `maven-shade-plugin` from Maven Central.

### Trying it on the sandbox (manual smoke, needs a device and the DIAL key)

1. Set `mcp_servers.ANDROID.app_id` and `mcp_servers.IOS.app_id` for the sandbox app in a config file.
2. Commit or stash your changes: the engine refuses to start on a dirty working tree.
3. Run the command above with `LoginTest --project-root . --config my-config.json --no-push`.
4. Open `migration_reports/LoginTest/dashboard.html` (self-contained: Chart.js, CSS and failure screenshots are inlined;
   only the Inter font is fetched when online) and `report.md`. The migration is on branch `migrate/ios-LoginTest`.

## Reports

`migration_reports/<TestClass>/` holds `dashboard.html`, `report.md`, `execution.log`, `llm_calls.jsonl`, `walkthroughs/`
and `failures/<method>/` (screenshot, cleaned screen XML). The dashboard shows token and cost economics, time per
stage, per-test results with healing attempts and `[LLM REASONING]` blocks, the final iOS and Android checks, project
progress, and a **scaling projection** for the tests still to migrate: pessimistic (worst recent run), expected (average of
the last 5 runs, weighted by tests) and optimistic (best recent run without the first one, whose cache was empty), with
wall-clock time over 1, 2 and 4 devices and a learning curve of cost per test. The projection is computed from
`.migration/history/runs.jsonl` (one line per finished class) and is flagged as low confidence below 3 runs.

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
