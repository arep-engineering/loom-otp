defmodule BenchElixir do
  @moduledoc """
  Benchmarks for Elixir/BEAM process concurrency.

  Equivalent to the otplike and loom-otp benchmarks in Clojure.

  Run with:
    cd bench_elixir && mix run -e 'BenchElixir.Runner.main()'

  Or specific benchmarks:
    mix run -e 'BenchElixir.Runner.main(["spawn"])'
    mix run -e 'BenchElixir.Runner.main(["messaging"])'
    mix run -e 'BenchElixir.Runner.main(["memory"])'
    mix run -e 'BenchElixir.Runner.main(["scaling"])'
  """

  defdelegate run(args \\ []), to: BenchElixir.Runner, as: :main
end
