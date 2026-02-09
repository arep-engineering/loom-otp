defmodule BenchElixir.Bench.Memory do
  @moduledoc """
  Memory and GC benchmarks for Elixir/BEAM.
  """

  alias BenchElixir.Bench.Common, as: C

  # =============================================================================
  # Memory Benchmarks
  # =============================================================================

  @doc """
  Measure baseline memory after system start (no processes).
  """
  def memory_baseline do
    C.force_gc!()
    mem = C.measure_memory_after_gc()
    C.println_result("baseline (system started, no procs)", mem, "MB")
    mem
  end

  @doc """
  Spawn N processes that block on receive, measure memory.
  Returns memory per process in KB.
  """
  def memory_n_idle(n, baseline_mb) do
    # Spawn N processes that just wait for :stop message
    pids =
      Enum.map(1..n, fn _ ->
        spawn(fn ->
          receive do
            :stop -> :done
          end
        end)
      end)

    # Let them all start
    Process.sleep(500)

    mem_after = C.measure_memory_after_gc()
    mem_per_proc_kb = (mem_after - baseline_mb) / n * 1024

    C.println_result(
      "idle-procs n=#{n}",
      mem_after,
      "MB (#{Float.round(mem_per_proc_kb, 2)} KB/proc)"
    )

    # Stop all processes
    Enum.each(pids, fn pid ->
      send(pid, :stop)
    end)

    Process.sleep(500)

    mem_per_proc_kb
  end

  @doc """
  Spawn/exit N processes, repeat for M cycles, measure memory after each.
  Detects memory leaks - memory should return to baseline after GC.
  """
  def memory_spawn_exit_cycles(n, cycles) do
    C.println_subheader("Spawn/Exit Cycles (n=#{n})")
    baseline = C.measure_memory_after_gc()
    C.println_result("baseline before cycles", baseline, "MB")

    Enum.each(1..cycles, fn cycle ->
      parent = self()

      # Spawn and wait for all to exit
      Enum.each(1..n, fn _ ->
        spawn(fn -> send(parent, :done) end)
      end)

      # Wait for all completions
      Enum.each(1..n, fn _ ->
        receive do
          :done -> :ok
        after
          30_000 -> :timeout
        end
      end)

      # Force GC and measure
      mem = C.measure_memory_after_gc()
      delta = mem - baseline
      C.println_result("after cycle #{cycle}", mem, "MB (delta: #{Float.round(delta, 2)})")
    end)
  end

  @doc """
  Send N messages to one process without receiving.
  Measures mailbox memory growth.
  """
  def memory_message_queue_growth(n_messages) do
    baseline = C.measure_memory_after_gc()

    receiver =
      spawn(fn ->
        receive do
          :done -> :done
        end
      end)

    # Send messages with some payload
    Enum.each(1..n_messages, fn i ->
      send(receiver, {:msg, i, :binary.copy(<<0>>, 100)})
    end)

    # Wait for sends to complete
    Process.sleep(1000)

    mem_after = C.measure_memory_after_gc()
    mem_per_msg_bytes = (mem_after - baseline) / n_messages * 1024 * 1024

    C.println_result(
      "queued-messages n=#{n_messages}",
      mem_after,
      "MB (#{round(mem_per_msg_bytes)} bytes/msg)"
    )

    # Cleanup
    send(receiver, :done)
  end

  @doc """
  Send messages with context, check if context accumulates in receiver.
  Each message carries context that might be retained.
  """
  def memory_context_accumulation(n_messages) do
    baseline = C.measure_memory_after_gc()
    parent = self()

    receiver =
      spawn(fn ->
        count = receive_with_context(0, n_messages)
        send(parent, {:done, count})
      end)

    # Send messages with unique context data
    Enum.each(1..n_messages, fn i ->
      send(receiver, {:msg, %{context_id: i, data: "context-#{i}"}})
    end)

    send(receiver, :done)

    receive do
      {:done, _count} -> :ok
    after
      30_000 -> :timeout
    end

    mem_after = C.measure_memory_after_gc()
    delta = mem_after - baseline

    C.println_result(
      "context-accumulation n=#{n_messages}",
      mem_after,
      "MB (delta: #{Float.round(delta, 2)})"
    )
  end

  defp receive_with_context(count, max) when count >= max, do: count

  defp receive_with_context(count, max) do
    receive do
      {:msg, _context} -> receive_with_context(count + 1, max)
      :done -> count
    end
  end

  @doc """
  Rapid spawn/exit to measure GC pressure.
  Reports time spent and estimates GC overhead.
  """
  def gc_pressure_test(n_spawns) do
    parent = self()
    start_time = System.monotonic_time(:millisecond)

    # Spawn/exit rapidly
    Enum.each(1..n_spawns, fn _ ->
      spawn(fn -> send(parent, :done) end)

      receive do
        :done -> :ok
      after
        1000 -> :timeout
      end
    end)

    elapsed_ms = System.monotonic_time(:millisecond) - start_time

    # Force GC and measure final state
    C.force_gc!()
    final_mem = C.used_memory_mb()

    C.println_result(
      "gc-pressure n=#{n_spawns}",
      elapsed_ms,
      "ms total (#{Float.round(final_mem, 2)} MB final)"
    )
  end

  @doc """
  Test memory scaling with increasing process counts.
  Shows if memory per process is constant or grows.
  """
  def memory_scaling_test do
    C.println_subheader("Memory Scaling")
    baseline = memory_baseline()

    scales = [100, 500, 1_000, 5_000, 10_000, 25_000, 50_000]

    Enum.each(scales, fn n ->
      try do
        memory_n_idle(n, baseline)
        # Cleanup between scales
        C.force_gc!()
        Process.sleep(500)
      rescue
        e ->
          IO.puts("FAILED at n=#{n}: #{Exception.message(e)}")
          reraise e, __STACKTRACE__
      end
    end)
  end

  # =============================================================================
  # Runner
  # =============================================================================

  def run_all do
    C.println_header("MEMORY BENCHMARKS")

    baseline = memory_baseline()

    C.println_subheader("Idle Processes")

    Enum.each(C.memory_scales(), fn n ->
      memory_n_idle(n, baseline)
      C.force_gc!()
      Process.sleep(200)
    end)

    memory_spawn_exit_cycles(10_000, 5)

    C.println_subheader("Message Queue Growth")

    Enum.each([1_000, 10_000, 100_000], fn n ->
      memory_message_queue_growth(n)
    end)

    C.println_subheader("Context Accumulation")
    memory_context_accumulation(10_000)

    C.println_subheader("GC Pressure")
    Enum.each([1_000, 10_000, 50_000], &gc_pressure_test/1)
  end

  def run_scaling do
    C.println_header("MEMORY SCALING TEST")
    memory_scaling_test()
  end
end
