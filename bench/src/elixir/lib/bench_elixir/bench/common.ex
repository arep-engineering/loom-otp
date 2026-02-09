defmodule BenchElixir.Bench.Common do
  @moduledoc """
  Shared benchmark utilities for timing, memory measurement, and output.
  """

  # =============================================================================
  # Memory Measurement
  # =============================================================================

  @doc """
  Current used memory in bytes.
  Returns total memory allocated by the BEAM.
  """
  def used_memory_bytes do
    :erlang.memory(:total)
  end

  @doc """
  Current used memory in megabytes.
  """
  def used_memory_mb do
    used_memory_bytes() / 1024.0 / 1024.0
  end

  @doc """
  Force garbage collection on all processes.
  """
  def force_gc! do
    :erlang.garbage_collect()
    # Give the system time to settle
    Process.sleep(100)
    # GC all processes
    for pid <- Process.list() do
      try do
        :erlang.garbage_collect(pid)
      catch
        _, _ -> :ok
      end
    end

    Process.sleep(100)
  end

  @doc """
  Force GC and return used memory in MB.
  """
  def measure_memory_after_gc do
    force_gc!()
    used_memory_mb()
  end

  # =============================================================================
  # Output Formatting
  # =============================================================================

  @doc """
  Print a benchmark result with consistent formatting.
  """
  def println_result(label, value, unit) do
    label_padded = String.pad_trailing(label, 50)
    value_formatted = :io_lib.format("~15.2f", [value * 1.0]) |> IO.iodata_to_binary()
    IO.puts("#{label_padded} #{value_formatted} #{unit}")
  end

  @doc """
  Print a section header.
  """
  def println_header(title) do
    IO.puts("")
    IO.puts("=== #{title} ===")
  end

  @doc """
  Print a subsection header.
  """
  def println_subheader(title) do
    IO.puts("")
    IO.puts("--- #{title} ---")
  end

  # =============================================================================
  # Timing
  # =============================================================================

  @doc """
  Execute function and return {result, elapsed_ms}.
  """
  def timed(fun) do
    start = System.monotonic_time(:nanosecond)
    result = fun.()
    elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0
    {result, elapsed_ms}
  end

  @doc """
  Execute function and return elapsed time in milliseconds.
  """
  def time_ms(fun) do
    {_result, elapsed_ms} = timed(fun)
    elapsed_ms
  end

  # =============================================================================
  # Benchmark Scales
  # =============================================================================

  def spawn_scales, do: [1_000, 10_000, 100_000]
  def spawn_scales_small, do: [100, 1_000, 10_000]
  def tree_depths, do: [10, 15, 18]
  def memory_scales, do: [1_000, 10_000, 50_000]
  def message_scales, do: [1_000, 10_000, 100_000, 1_000_000]

  # =============================================================================
  # System Info
  # =============================================================================

  @doc """
  Print BEAM and system information.
  """
  def print_system_info(name) do
    IO.puts("========================================")
    IO.puts("#{name} benchmarks")
    IO.puts("Erlang/OTP: #{:erlang.system_info(:otp_release)}")
    IO.puts("ERTS: #{:erlang.system_info(:version)}")
    IO.puts("Elixir: #{System.version()}")

    schedulers = :erlang.system_info(:schedulers_online)
    IO.puts("Schedulers: #{schedulers}")

    # Get memory info from erlang:memory
    total_mem_bytes = :erlang.memory(:total)
    total_mem_mb = div(total_mem_bytes, 1024 * 1024)
    IO.puts("BEAM memory: #{total_mem_mb} MB")

    word_size = :erlang.system_info(:wordsize) * 8
    IO.puts("Word size: #{word_size} bits")

    {os_family, os_name} = :os.type()
    IO.puts("OS: #{os_family} #{os_name}")
    IO.puts("========================================")
  end
end
