defmodule BenchElixirTest do
  use ExUnit.Case
  doctest BenchElixir

  test "greets the world" do
    assert BenchElixir.hello() == :world
  end
end
