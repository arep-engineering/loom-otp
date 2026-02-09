# Benchmark Comparison: loom-otp vs otplike

Spawn Performance

| Benchmark               | loom-otp | otplike  | Winner        | Ratio |
| ----------------------- | -------- | -------- | ------------- | ----- |
| Immediate exit (100K)   | 69K/sec  | 208K/sec | otplike       | 3x    |
| Async spawn (100K)      | 207K/sec | N/A      | loom-otp only | -     |
| Wait for exit (100K)    | 81K/sec  | 252K/sec | otplike       | 3x    |
| Sequential latency      | 0.02 ms  | 0.03 ms  | loom-otp      | 1.5x  |
| Process tree (depth 15) | 199K/sec | 53K/sec  | loom-otp      | 3.7x  |
| Linked tree (depth 15)  | 48K/sec  | 41K/sec  | ~equal        | 1.2x  |

Key finding: With async spawn, loom-otp matches otplike's raw spawn throughput (~207K vs ~208K). For hierarchical spawning (tree pattern), loom-otp is 3.7x faster.

---

Memory Usage

| Benchmark              | loom-otp       | otplike        | Winner    | Ratio     |
| ---------------------- | -------------- | -------------- | --------- | --------- |
| Per idle process       | 9.2 KB         | 2.8 KB         | otplike   | 3.3x less |
| 50K idle processes     | 462 MB         | 153 MB         | otplike   | 3x less   |
| Memory after GC cycles | stable (Δ0.00) | stable (Δ0.01) | Both good | -         |
| Message queue overhead | ~0 bytes/msg   | 285 bytes/msg  | loom-otp  | ~∞        |

Key finding: otplike uses 3x less memory per process. However, loom-otp has near-zero message queue overhead vs 285 bytes/msg in otplike.

---

Messaging Performance

| Benchmark                    | loom-otp  | otplike   | Winner   | Ratio        |
| ---------------------------- | --------- | --------- | -------- | ------------ |
| Ping-pong latency            | 2.78 µs   | 9.62 µs   | loom-otp | 3.5x faster  |
| Send throughput (1M)         | 8.7M/sec  | 5.8M/sec  | loom-otp | 1.5x faster  |
| Receive throughput (1M)      | 8.3M/sec  | 0.66M/sec | loom-otp | 12.5x faster |
| Many-to-one (10 senders)     | 1.27M/sec | 0.12M/sec | loom-otp | 10.5x faster |
| One-to-many (10 receivers)   | 669K/sec  | 241K/sec  | loom-otp | 2.8x faster  |
| Selective recv worst (q=10K) | 6.7 ms    | 12.5 ms   | loom-otp | 1.9x faster  |

Key finding: loom-otp dominates messaging - especially receive throughput (12.5x faster) and concurrent patterns (10x faster).

---

Summary Table

| Category             | Winner   | Margin           |
| -------------------- | -------- | ---------------- |
| Spawn (sync)         | otplike  | 3x faster        |
| Spawn (async)        | ~equal   | loom-otp matches |
| Spawn (tree pattern) | loom-otp | 3.7x faster      |
| Memory per process   | otplike  | 3x less          |
| Message queue memory | loom-otp | 285x less        |
| Message latency      | loom-otp | 3.5x faster      |
| Message throughput   | loom-otp | 1.5-12x faster   |
| Concurrent messaging | loom-otp | 10x faster       |
| Selective receive    | loom-otp | 1.9x faster      |
| GC stability         | Both     | No leaks         |

---

Recommendations

Choose loom-otp if:
- Message passing is your primary workload (10x+ faster)
- Low latency matters (3.5x better)
- You use hierarchical process spawning (3.7x faster)
- You need to queue many messages (285x less overhead)
- Use spawn-async! for bulk spawning to match otplike's speed

Choose otplike if:
- Process density is critical (3x more processes per GB)
- You're constrained to older JVM (pre-21)
- Minimal per-process footprint is essential
