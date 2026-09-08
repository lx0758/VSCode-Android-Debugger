## ADDED Requirements

### Requirement: Process Discovery Spawn-Failure Cleanup

The extension's JDWP process enumeration SHALL handle failure of the `adb jdwp` capture subprocess without resource leakage or dangling state. When the capture subprocess fails to spawn, enumeration SHALL fail with a clear error after releasing the capture timer and terminating any spawned process. An enumeration attempt SHALL settle exactly once.

#### Scenario: Capture subprocess fails to spawn

- **WHEN** the `adb jdwp` capture subprocess emits an `error` event (e.g., adb binary missing or spawn rejected)
- **THEN** the capture timer is cleared and any spawned process is terminated
- **AND** the enumeration promise rejects with a clear error and never resolves partial results afterwards

#### Scenario: Normal capture window completes

- **WHEN** the capture subprocess exits within the capture window
- **THEN** the PID list parsed from stdout is returned exactly once, with the timer cleared on exit

### Requirement: Adb Stdin Write Robustness

The adb runner SHALL guard stdin writes so that a child process exiting before consuming its stdin (EPIPE) never produces an unhandled error. The failed write SHALL be absorbed with a log entry, the pending command SHALL still settle via the child's exit event, and the extension host SHALL remain alive.

#### Scenario: Child exits before consuming stdin

- **WHEN** `execWithStdin` writes to a child whose stdin pipe has already closed
- **THEN** the resulting write error is logged rather than surfacing as an unhandled error
- **AND** the pending command promise still resolves with the child's exit result

#### Scenario: Stdin consumed normally

- **WHEN** the child consumes the written stdin and exits normally
- **THEN** command output is returned unchanged (the guard has no effect on the happy path)
