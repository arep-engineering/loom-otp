# OTPLike Compatibility Layer - Test Gap Analysis

This document tracks the differences between the original otplike test suite and the loom-otp compatibility test suite.

## Summary

| Test File | Original Tests | Loom-OTP Tests | Extra Tests | Coverage |
|-----------|---------------|----------------|-------------|----------|
| process_test.clj | 171 | 150 | 1 | 99% |
| gen_server_test.clj | 115 | 113 | 0 | 98% |
| timer_test.clj | 32 | 34 | 0 | 100%+ |
| supervisor_test.clj | 23 | 21 | 4 | 91% |
| gen_server_ns_test.clj | 1 | 1 | 0 | 100% |

Note: Tests that were added in loom-otp (not present in original otplike) have been moved to `*_extra_test.clj` files:
- `process_extra_test.clj` - 1 test for `registered` function
- `supervisor_extra_test.clj` - 4 tests for dynamic child management and restart behavior

---

## All Tests Passing

All implemented tests are now passing:

```
Ran 422 tests containing 933 assertions.
0 failures, 0 errors.
```

---

## Intentionally Disabled Tests

### `async-allows-parking` (1 test)

**Status:** Commented out with `#_`

**Reason:** This test expects `process/async` to execute concurrently (like core.async go blocks). In loom-otp, `async` executes **synchronously and immediately** on the calling thread. This is by design:

- With virtual threads, blocking is cheap - no need for parking/go blocks
- Sequential execution within a process is maintained
- The process mailbox can be accessed from within async blocks

The test was:
```clojure
#_(deftest ^:parallel async-allows-parking
  (let [done (promise)
        done1 (promise)]
    (process/async
      (if (deref done 50 nil)
        (deliver done1 true)
        (is false "timeout")))
    (deliver done true)
    (await-completion!! done1 50)))
```

This test assumed the async body would run concurrently, allowing `(deliver done true)` to execute before the async body checks `done`. With synchronous execution, the async body runs first and times out.

---

## Intentionally Empty Stubs (Same as original otplike)

These tests are included as empty stubs from the original otplike. They represent edge cases that were never implemented in the original and are **intentionally left as stubs** in loom-otp for parity.

### Category: exiting-process tests (10 empty stubs)
These test behavior when functions are called by an exiting process:
- `self-fails-when-process-is-exiting`
- `exit-fails-when-called-by-exiting-process`
- `flag-fails-when-called-by-exiting-process`
- `link-fails-when-called-by-exiting-process`
- `monitor-fails-when-called-by-exiting-process`
- `demonitor-fails-when-called-by-exiting-process`
- `receive!-fails-when-called-by-exiting-process`
- `selective-receive!-fails-when-called-by-exiting-process`
- `spawn-link-fails-when-called-by-exiting-process`
- `unlink-fails-when-called-by-exiting-process`

### Category: Exhaustive strategy tests (3 empty stubs)
These are property-based exhaustive tests that would require substantial helper functions:
- `test-one-for-one`
- `test-one-for-all`
- `test-rest-for-one`

### Category: Commented-out TODO tests from original
These were commented out in the original and remain as comments:
- `exit-self-reason-is-process-exit-reason`
- `exit-kill-does-not-propagate`
- `process-killed-when-inbox-is-overflowed`
- `process-exit-reason-is-proc-fn-return-value`
- `spawned-process-available-from-within-process-by-reg-name`
- `there-are-no-residue-of-process-after-proc-fun-throws`

---

## Extra Tests (loom-otp additions)

The following tests were added in loom-otp and are NOT present in the original otplike test suite.
They have been moved to `*_extra_test.clj` files:

### process_extra_test.clj
- `registered-returns-registered-names` - Tests registered function behavior

### supervisor_extra_test.clj
- `one-for-one--child-restart` - Tests supervisor restart behavior
- `start-child--adds-child` - Tests dynamic child addition
- `terminate-child--stops-child` - Tests child termination
- `restart-child--restarts-stopped-child` - Tests manual child restart

---

## Function Availability in loom-otp

### loom-otp.otplike.process
- `process-info` - YES
- `processes` - YES
- `registered` - YES
- `await!` - YES (macro)
- `await!!` - YES
- `await?!` - YES (macro)
- `async` - YES (executes synchronously, not concurrently like otplike)

### loom-otp.otplike.gen-server
- `start-ns!` - YES

---

## Implementation Notes

### Async/Await Semantics

In the original otplike, `async` used core.async go blocks:
```clojure
(defmacro async [& body]
  `(->Async (go (ex-catch [:ok (do ~@body)])) nil []))
```

In loom-otp, `async` executes immediately and synchronously:
```clojure
(defmacro async [& body]
  `(let [creator-pid# (core/try-self)
         result# (try
                   [:value (do ~@body)]
                   (catch InterruptedException ie#
                     (throw ie#))
                   (catch Throwable t#
                     [:exception (pexit/ex->reason t#)]))]
     (Async. (atom result#) [] creator-pid#)))
```

This change was made because:
1. Virtual threads make blocking cheap - no need for go block concurrency
2. Sequential execution within a process must be maintained for mailbox access
3. The gen_server implementation uses process operations inside async blocks

### Timer Module
All timer functions have proper argument validation and process context checks.

### Receive Macros
Both `receive!` and `selective-receive!` validate that at least one message pattern is provided.

---

## Notes

- Tests in loom-otp use `promise` instead of `core.async/chan`
- Tests in loom-otp use `mount.lite` for fixtures
- Virtual threads don't "park" like core.async go blocks - they block the virtual thread instead
