defmodule BenchElixir.Bench.Spawn do
  @moduledoc """
  Spawn benchmarks for Elixir/BEAM.
  """

  alias BenchElixir.Bench.Common, as: C

  # =============================================================================
  # Spawn Benchmarks
  # =============================================================================

  @doc """
  Spawn N processes that exit immediately.
  Measures raw spawn throughput without synchronization.
  """
  def spawn_n_immediate_exit(n) do
    time_ms =
      C.time_ms(fn ->
        Enum.each(1..n, fn _ ->
          spawn(fn -> :done end)
        end)
      end)

    rate = n / (time_ms / 1000.0)
    C.println_result("spawn-immediate-exit n=#{n}", rate, "proc/sec")
  end

  @doc """
  Spawn N processes, wait for all to exit using counter.
  Measures spawn + exit coordination throughput.
  """
  def spawn_n_wait_exit(n) do
    parent = self()

    time_ms =
      C.time_ms(fn ->
        Enum.each(1..n, fn _ ->
          spawn(fn -> send(parent, :done) end)
        end)

        # Wait for all processes to signal completion
        Enum.each(1..n, fn _ ->
          receive do
            :done -> :ok
          after
            60_000 -> :timeout
          end
        end)
      end)

    rate = n / (time_ms / 1000.0)
    C.println_result("spawn-wait-exit n=#{n}", rate, "proc/sec")
  end

  @doc """
  Spawn one process, wait for it to exit, repeat N times.
  Measures spawn latency (round-trip time).
  """
  def spawn_sequential(n) do
    parent = self()

    time_ms =
      C.time_ms(fn ->
        Enum.each(1..n, fn _ ->
          spawn(fn -> send(parent, :done) end)

          receive do
            :done -> :ok
          after
            1_000 -> :timeout
          end
        end)
      end)

    latency = time_ms / n
    C.println_result("spawn-sequential n=#{n}", latency, "ms/spawn")
  end

  @doc """
  Create binary tree of processes, depth D -> 2^(D+1)-1 processes.
  Each node spawns two children, waits for results, reports sum to parent.
  """
  def spawn_tree(depth) do
    parent = self()

    time_ms =
      C.time_ms(fn ->
        spawn(fn ->
          node(parent, depth)
        end)

        receive do
          {:total, total} -> total
        after
          120_000 -> :timeout
        end
      end)

    total = round(:math.pow(2, depth + 1) - 1)
    rate = total / (time_ms / 1000.0)
    C.println_result("spawn-tree depth=#{depth} procs=#{total}", rate, "proc/sec")
  end

  defp node(parent, 0) do
    send(parent, {:total, 1})
  end

  defp node(parent, d) do
    me = self()
    spawn(fn -> node(me, d - 1) end)
    spawn(fn -> node(me, d - 1) end)

    n1 =
      receive do
        {:total, n} -> n
      after
        60_000 -> 0
      end

    n2 =
      receive do
        {:total, n} -> n
      after
        60_000 -> 0
      end

    send(parent, {:total, 1 + n1 + n2})
  end

  @doc """
  Same as spawn_tree but with linked processes.
  Measures link setup overhead.
  """
  def spawn_link_tree(depth) do
    parent = self()

    time_ms =
      C.time_ms(fn ->
        spawn(fn ->
          node_linked(parent, depth)
        end)

        receive do
          {:total, total} -> total
        after
          120_000 -> :timeout
        end
      end)

    total = round(:math.pow(2, depth + 1) - 1)
    rate = total / (time_ms / 1000.0)
    C.println_result("spawn-link-tree depth=#{depth} procs=#{total}", rate, "proc/sec")
  end

  defp node_linked(parent, 0) do
    send(parent, {:total, 1})
  end

  defp node_linked(parent, d) do
    me = self()
    spawn_link(fn -> node_linked(me, d - 1) end)
    spawn_link(fn -> node_linked(me, d - 1) end)

    n1 =
      receive do
        {:total, n} -> n
      after
        60_000 -> 0
      end

    n2 =
      receive do
        {:total, n} -> n
      after
        60_000 -> 0
      end

    send(parent, {:total, 1 + n1 + n2})
  end

  @doc """
  Spawn N processes using spawn_monitor.
  Measures monitor setup overhead.
  """
  def spawn_n_monitored(n) do
    time_ms =
      C.time_ms(fn ->
        refs =
          Enum.map(1..n, fn _ ->
            {_pid, ref} = spawn_monitor(fn -> :done end)
            ref
          end)

        # Wait for all DOWN messages
        Enum.each(refs, fn ref ->
          receive do
            {:DOWN, ^ref, :process, _pid, _reason} -> :ok
          after
            60_000 -> :timeout
          end
        end)
      end)

    rate = n / (time_ms / 1000.0)
    C.println_result("spawn-monitored n=#{n}", rate, "proc/sec")
  end

  # =============================================================================
  # Runner
  # =============================================================================

  def run_all do
    C.println_header("SPAWN BENCHMARKS")

    C.println_subheader("Immediate Exit (fire-and-forget)")
    Enum.each(C.spawn_scales(), &spawn_n_immediate_exit/1)

    C.println_subheader("Wait for Exit (synchronized)")
    Enum.each(C.spawn_scales(), &spawn_n_wait_exit/1)

    C.println_subheader("Sequential (latency)")
    Enum.each(C.spawn_scales_small(), &spawn_sequential/1)

    C.println_subheader("Process Tree")
    Enum.each(C.tree_depths(), &spawn_tree/1)

    C.println_subheader("Linked Process Tree")
    Enum.each(C.tree_depths(), &spawn_link_tree/1)

    C.println_subheader("Monitored Spawns")
    Enum.each(C.spawn_scales(), &spawn_n_monitored/1)
  end
end
