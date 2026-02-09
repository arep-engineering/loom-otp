defmodule BenchElixir.Runner do
  @moduledoc """
  Main entry point for Elixir benchmarks.
  """

  alias BenchElixir.Bench.{Common, Spawn, Messaging, Memory}

  def main(args \\ []) do
    # Start os_mon for memory info
    Application.ensure_all_started(:os_mon)

    Common.print_system_info("Elixir/BEAM")
    IO.puts("")

    case args do
      ["spawn"] ->
        Spawn.run_all()

      ["memory"] ->
        Memory.run_all()

      ["messaging"] ->
        Messaging.run_all()

      ["scaling"] ->
        Memory.run_scaling()

      [] ->
        # Default: run all
        Spawn.run_all()
        IO.puts("")
        Memory.run_all()
        IO.puts("")
        Messaging.run_all()

      [other] ->
        IO.puts("Unknown mode: #{other}")
        IO.puts("Usage: mix run -e 'BenchElixir.Runner.main([\"mode\"])'")
        IO.puts("Modes: spawn, memory, messaging, scaling")
        IO.puts("Or run without args for all benchmarks")
    end

    IO.puts("")
    IO.puts("========================================")
    IO.puts("Elixir/BEAM benchmarks complete")
    IO.puts("========================================")
  end
end
