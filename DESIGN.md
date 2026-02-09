# loom-otp Design Document

Erlang/OTP-style actor concurrency for Clojure using JVM virtual threads (Project Loom).

## Overview

loom-otp provides processes, message passing, links, monitors, gen_server, and supervisors. Unlike [otplike](https://github.com/suprematic/otplike) which uses core.async, loom-otp uses virtual threads where blocking is cheap and natural.

**Key differences from otplike:**
- Virtual threads instead of go blocks
- Single `receive!` (always blocking) instead of `receive!`/`receive!!`
- No `async`/`await!` - just block directly
- Links only via `spawn-link` (no explicit `link`/`unlink` API)
- Selective receive supported via `selective-receive!`

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      APPLICATION LAYER                          │
│  supervisor - Supervision trees, restart strategies             │
├─────────────────────────────────────────────────────────────────┤
│                      BEHAVIOR LAYER                             │
│  gen-server - Generic server pattern                            │
│  timer - Delayed/periodic operations                            │
├─────────────────────────────────────────────────────────────────┤
│                      CORE PROCESS LAYER                         │
│  process - Spawning, messaging, monitors, receive               │
│  process.spawn - Process lifecycle (control + user threads)     │
│  process.link - Bidirectional links (stored per-process)        │
│  process.monitor - Unidirectional monitors                      │
│  process.exit - Exit handling                                   │
├─────────────────────────────────────────────────────────────────┤
│                      FOUNDATION LAYER                           │
│  mailbox - Message queue with watch-based notification          │
│  context-mailbox - [ctx msg] pairs for distributed tracing      │
│  registry - Process registration by name                        │
│  state - Global state management (mount.lite)                   │
│  types - Pid, TRef wrapper types                                │
│  trace - Event tracing infrastructure                           │
└─────────────────────────────────────────────────────────────────┘
```

## Process Model

Each process has two virtual threads:

```
┌─────────────────── Process ───────────────────┐
│                                               │
│  ┌─────────────────┐  ┌───────────────────┐   │
│  │ Control Thread  │  │   User Thread     │   │
│  │                 │  │                   │   │
│  │ • Sets up links │  │ • Runs user code  │   │
│  │ • Starts user   │  │ • Calls receive!  │   │
│  │ • Handles exits │  │ • Pattern matches │   │
│  │ • Runs cleanup  │  │                   │   │
│  │                 │  │ Notifies control  │   │
│  │ Orchestrates    │  │ on termination    │   │
│  │ lifecycle       │  │                   │   │
│  └─────────────────┘  └───────────────────┘   │
│                                               │
│  Shared: mailbox, control, flags, exit-reason │
└───────────────────────────────────────────────┘
```

### Process Lifecycle

```
spawn-process (caller's thread):
  1. Create process with promises for coordination
  2. Add to process table, register name
  3. Start control thread (virtual)
  4. Wait for :spawned promise
  5. Return pid

control-thread:
  1. Deliver :control-thread promise
  2. Set up link if requested (may fail with :noproc)
  3. Start user thread (virtual)
  4. Wait for :user-thread promise
  5. Deliver :spawned promise
  6. Loop: handle signals via cmb/receive!
     - [:exit from reason] → handle-exit-signal!, maybe interrupt user
     - [:user-terminated-return value] → set exit-reason
     - [:user-terminated-throw exception] → set exit-reason
     - [:user-terminated-interrupted] → exit-reason already set
  7. Cleanup: notify links/monitors, unregister, remove from table

user-thread:
  1. Deliver :user-thread promise
  2. Run user function
  3. Send termination signal to control thread
```

### Process State

```clojure
{:pid             Pid                    ; Unique identifier
 :mailbox         Atom<Queue>            ; User messages [ctx msg]
 :control         Atom<Queue>            ; Control signals [ctx signal]
 :exit-reason     Promise                ; Delivered on exit (write-once)
 :message-context Atom<Map>              ; Context for distributed tracing
 :last-control-ctx Atom<Map>             ; Context from last exit signal
 :flags           Atom<{:trap-exit bool}>
 :links           Atom<#{pid-ids}>       ; Linked process ids
 :user-thread     Promise<Thread>        ; For interrupt
 :control-thread  Promise<Thread>
 :spawned         Promise<bool>}         ; Coordination
```

## Exit Reasons

| Scenario | Exit Reason |
|----------|-------------|
| User function returns `value` | `[:normal value]` |
| User calls `(exit/exit reason)` | `reason` |
| User throws exception | `[:exception (Throwable->map e)]` |
| spawn-link to non-existent process | `:noproc` |
| Exit signal with reason R (not trapped) | `R` |
| Exit signal with `:kill` | `:killed` |

Normal exits are `[:normal value]` or bare `:normal`. The `normal-exit-reason?` helper checks both forms.

## Links

Bidirectional relationships for fault propagation. Stored in each process's `:links` atom.

**Key properties:**
- Created only via `spawn-link` or `spawn-opt {:link true}`
- No explicit `link`/`unlink` public API
- Bidirectional: if A links to B, both have each other in `:links`
- On exit: linked processes receive exit signals

**Exit signal handling:**

| Receiver's trap-exit | Reason = :normal | Reason = :kill | Reason = other |
|---------------------|------------------|----------------|----------------|
| false | Ignored | Dies with :killed | Dies with reason |
| true | Receives [:EXIT pid reason] | Dies with :killed | Receives [:EXIT pid reason] |

## Monitors

Unidirectional observation without coupling.

**Key properties:**
- One-shot: fires once, then removed
- Always delivers `:DOWN` message (no trap flag needed)
- Safe for non-existent targets (immediate `:noproc`)
- Stackable: multiple monitors to same target allowed

**Message format:**
```clojure
[:DOWN ref :process target reason]
```

## Message Passing

### Send (`send`)

```clojure
(send dest message)  ; Returns true if delivered, false if process not found
```

Messages are wrapped with sender's context for distributed tracing.

### Receive (`receive!`)

Pattern-matching receive with optional timeout:

```clojure
(receive!
  [:hello name] (println "Hello" name)
  [:exit reason] (handle-exit reason)
  (after 1000 :timeout))
```

### Selective Receive (`selective-receive!`)

Scans mailbox for first matching message:

```clojure
(selective-receive!
  [:priority msg] (handle-priority msg)
  (after 500 :no-priority))
```

## State Management

Uses [mount.lite](https://github.com/aroemers/mount-lite) for lifecycle management.

```clojure
(require '[mount.lite :as mount])

(mount/start)  ; Initialize system
;; ... use processes ...
(mount/stop)   ; Terminate all processes
```

### Global State

```clojure
{:processes   Atom<{pid-id -> process-map}>
 :monitors    Atom<{ref-id -> monitor-info}>
 :registry    Atom<{:forward {name -> pid}, :reverse {pid-id -> name}}>
 :trace-fn    Atom<handler-fn>
 :pid-counter AtomicLong
 :ref-counter AtomicLong}
```

Note: Links are stored per-process, not globally.

### Parallel Testing

```clojure
(use-fixtures :each
  (fn [f]
    (mount/start)
    (try (f) (finally (mount/stop)))))
```

## Public API

### loom-otp.process

| Function | Description |
|----------|-------------|
| `spawn`, `spawn-link`, `spawn-opt` | Create processes |
| `spawn!`, `spawn-link!`, `spawn-opt!` | Macro versions (wrap body in fn) |
| `spawn-trap`, `spawn-trap!` | Spawn with trap-exit enabled |
| `self` | Current process pid |
| `send` | Send message |
| `exit` | Exit self or send exit signal to another |
| `monitor`, `demonitor` | Manage monitors |
| `register` | Register name for process |
| `alive?`, `processes`, `process-info` | Introspection |
| `receive!`, `selective-receive!` | Receive messages (see process.match) |

### loom-otp.process.match

| Macro | Description |
|-------|-------------|
| `receive!` | Pattern-matching receive with optional timeout |
| `selective-receive!` | Scan mailbox for matching message |

### loom-otp.gen-server

| Function | Description |
|----------|-------------|
| `start`, `start-link` | Start server |
| `call`, `cast` | Send requests |
| `reply` | Explicit reply from handle-call |
| `stop` | Stop server |

### loom-otp.supervisor

| Function | Description |
|----------|-------------|
| `start-link` | Start supervisor |
| `start-child` | Add child dynamically |
| `terminate-child` | Stop a child |
| `which-children` | List children |

### loom-otp.timer

| Function | Description |
|----------|-------------|
| `send-after` | Send message after delay |
| `exit-after`, `kill-after` | Send exit signal after delay |
| `apply-after` | Call function after delay |
| `send-interval`, `apply-interval` | Periodic operations |
| `cancel` | Cancel timer |
| `read-timer` | Get remaining time |

### loom-otp.trace

| Function | Description |
|----------|-------------|
| `trace` | Set trace handler |
| `untrace` | Remove trace handler |

## Tracing

```clojure
(trace/trace (fn [event]
               (println (:event event) (:pid event))))

;; Events: :spawn, :exit, :exit-signal, :send
```

## Types

```clojure
(require '[loom-otp.types :as t])

(t/pid? x)      ; Check if Pid
(t/ref? x)      ; Check if TRef
(t/->pid n)     ; Create Pid from number
(t/->ref n)     ; Create TRef from number
(t/pid->id pid) ; Extract number from Pid (or return number if already)
```

## Dependencies

- Clojure 1.12+
- JDK 21+ (for virtual threads)
- `org.clojure/core.match` - Pattern matching in receive
- `functionalbytes/mount-lite` - State lifecycle management
