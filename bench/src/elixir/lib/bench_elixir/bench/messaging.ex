defmodule BenchElixir.Bench.Messaging do
  @moduledoc """
  Messaging benchmarks for Elixir/BEAM: send, receive, selective-receive.
  """

  alias BenchElixir.Bench.Common, as: C

  # =============================================================================
  # Basic Messaging Benchmarks
  # =============================================================================

  @doc """
  N round-trips between two processes.
  Measures message passing latency.
  """
  def ping_pong(n_roundtrips) do
    pong =
      spawn(fn ->
        pong_loop()
      end)

    time_ms =
      C.time_ms(fn ->
        me = self()

        Enum.each(1..n_roundtrips, fn _ ->
          send(pong, {:ping, me})

          receive do
            :pong -> :ok
          after
            60_000 -> :timeout
          end
        end)

        send(pong, :stop)
      end)

    msgs_per_sec = n_roundtrips * 2 / (time_ms / 1000.0)
    latency_us = time_ms * 1000 / (n_roundtrips * 2)

    C.println_result(
      "ping-pong n=#{n_roundtrips}",
      latency_us,
      "us/msg (#{round(msgs_per_sec)} msg/sec)"
    )
  end

  defp pong_loop do
    receive do
      {:ping, from} ->
        send(from, :pong)
        pong_loop()

      :stop ->
        :done
    end
  end

  @doc """
  Send N messages to one process without waiting for receive.
  Measures raw send rate.
  """
  def send_throughput(n_messages) do
    receiver =
      spawn(fn ->
        receive do
          :done -> :ok
        end
      end)

    # Time only the sending
    start = System.monotonic_time(:nanosecond)

    Enum.each(1..n_messages, fn i ->
      send(receiver, {:msg, i})
    end)

    elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0

    send(receiver, :done)

    rate = n_messages / (elapsed_ms / 1000.0)
    C.println_result("send-throughput n=#{n_messages}", rate, "msg/sec")
  end

  @doc """
  Pre-fill queue with N messages, then receive all.
  Measures raw receive rate.
  """
  def receive_throughput(n_messages) do
    parent = self()

    # Spawn receiver that waits for signal to start receiving
    receiver =
      spawn(fn ->
        receive do
          :start -> :ok
        end

        start = System.monotonic_time(:nanosecond)

        Enum.each(1..n_messages, fn _ ->
          receive do
            {:msg, _} -> :ok
          end
        end)

        elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0
        send(parent, {:elapsed, elapsed_ms})
      end)

    # Fill the queue
    Enum.each(1..n_messages, fn i ->
      send(receiver, {:msg, i})
    end)

    # Signal receiver to start and wait for result
    send(receiver, :start)

    time_ms =
      receive do
        {:elapsed, ms} -> ms
      after
        60_000 -> :timeout
      end

    rate = n_messages / (time_ms / 1000.0)
    C.println_result("receive-throughput n=#{n_messages}", rate, "msg/sec")
  end

  # =============================================================================
  # Selective Receive Benchmarks
  # =============================================================================

  @doc """
  Selective receive where match is first in queue.
  Best-case scenario for selective receive.
  """
  def selective_receive_first(queue_size) do
    parent = self()

    receiver =
      spawn(fn ->
        receive do
          :ready -> :ok
        end

        start = System.monotonic_time(:nanosecond)

        receive do
          :target -> :found
        after
          5000 -> :timeout
        end

        elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0
        send(parent, {:elapsed, elapsed_ms})
      end)

    # Fill queue: target first, then filler messages
    send(receiver, :target)

    Enum.each(1..queue_size, fn i ->
      send(receiver, {:filler, i})
    end)

    send(receiver, :ready)

    time_ms =
      receive do
        {:elapsed, ms} -> ms
      after
        10_000 -> :timeout
      end

    C.println_result("selective-recv-first q=#{queue_size}", time_ms, "ms")
  end

  @doc """
  Selective receive where match is last in queue.
  Worst-case scenario requiring full queue scan.
  """
  def selective_receive_last(queue_size) do
    parent = self()

    receiver =
      spawn(fn ->
        receive do
          :ready -> :ok
        end

        start = System.monotonic_time(:nanosecond)

        receive do
          :target -> :found
        after
          5000 -> :timeout
        end

        elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0
        send(parent, {:elapsed, elapsed_ms})
      end)

    # Fill queue: filler messages first, then target last
    Enum.each(1..queue_size, fn i ->
      send(receiver, {:filler, i})
    end)

    send(receiver, :target)
    send(receiver, :ready)

    time_ms =
      receive do
        {:elapsed, ms} -> ms
      after
        10_000 -> :timeout
      end

    C.println_result("selective-recv-last q=#{queue_size}", time_ms, "ms")
  end

  @doc """
  Selective receive with no match (timeout).
  Measures scan overhead without match.
  """
  def selective_receive_miss(queue_size) do
    parent = self()

    receiver =
      spawn(fn ->
        receive do
          :ready -> :ok
        end

        start = System.monotonic_time(:nanosecond)

        receive do
          :nonexistent -> :found
        after
          100 -> :timeout
        end

        elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0
        send(parent, {:elapsed, elapsed_ms})
      end)

    # Fill queue with non-matching messages
    Enum.each(1..queue_size, fn i ->
      send(receiver, {:filler, i})
    end)

    send(receiver, :ready)

    time_ms =
      receive do
        {:elapsed, ms} -> ms
      after
        5000 -> :timeout
      end

    C.println_result("selective-recv-miss q=#{queue_size} (timeout=100ms)", time_ms, "ms")
  end

  @doc """
  Repeated selective receives on a stable queue.
  Measures overhead of multiple scans.
  """
  def selective_receive_repeated(queue_size, n_receives) do
    parent = self()

    receiver =
      spawn(fn ->
        receive do
          :ready -> :ok
        end

        start = System.monotonic_time(:nanosecond)

        Enum.each(0..(n_receives - 1), fn i ->
          receive do
            {:target, ^i} -> :found
          after
            5000 -> :timeout
          end
        end)

        elapsed_ms = (System.monotonic_time(:nanosecond) - start) / 1_000_000.0
        send(parent, {:elapsed, elapsed_ms})
      end)

    # Add all targets first
    Enum.each(0..(n_receives - 1), fn i ->
      send(receiver, {:target, i})
    end)

    # Then add filler
    Enum.each(1..queue_size, fn i ->
      send(receiver, {:filler, i})
    end)

    send(receiver, :ready)

    time_ms =
      receive do
        {:elapsed, ms} -> ms
      after
        60_000 -> :timeout
      end

    avg_ms = time_ms / n_receives
    C.println_result("selective-recv-repeated q=#{queue_size} n=#{n_receives}", avg_ms, "ms/recv")
  end

  # =============================================================================
  # Concurrency Benchmarks
  # =============================================================================

  @doc """
  N senders each send M messages to 1 receiver.
  Measures mailbox contention.
  """
  def many_to_one(n_senders, msgs_each) do
    parent = self()
    total_msgs = n_senders * msgs_each

    receiver =
      spawn(fn ->
        receive_n_messages(total_msgs)
        send(parent, :receiver_done)
      end)

    time_ms =
      C.time_ms(fn ->
        Enum.each(1..n_senders, fn sender_id ->
          spawn(fn ->
            Enum.each(1..msgs_each, fn msg_id ->
              send(receiver, {:msg, sender_id, msg_id})
            end)
          end)
        end)

        receive do
          :receiver_done -> :ok
        after
          120_000 -> :timeout
        end
      end)

    rate = total_msgs / (time_ms / 1000.0)
    C.println_result("many-to-one senders=#{n_senders} msgs-each=#{msgs_each}", rate, "msg/sec")
  end

  defp receive_n_messages(0), do: :ok

  defp receive_n_messages(n) do
    receive do
      {:msg, _, _} -> receive_n_messages(n - 1)
    end
  end

  @doc """
  1 sender sends M messages to each of N receivers.
  Measures fan-out performance.
  """
  def one_to_many(n_receivers, msgs_each) do
    parent = self()
    total_msgs = n_receivers * msgs_each

    # Spawn receivers that each wait for msgs_each messages
    receivers =
      Enum.map(1..n_receivers, fn _ ->
        spawn(fn ->
          Enum.each(1..msgs_each, fn _ ->
            receive do
              {:msg, _} -> :ok
            end
          end)

          send(parent, :receiver_done)
        end)
      end)

    time_ms =
      C.time_ms(fn ->
        # Single sender sends to all receivers
        Enum.each(1..msgs_each, fn msg_id ->
          Enum.each(receivers, fn r ->
            send(r, {:msg, msg_id})
          end)
        end)

        # Wait for all receivers to complete
        Enum.each(1..n_receivers, fn _ ->
          receive do
            :receiver_done -> :ok
          after
            120_000 -> :timeout
          end
        end)
      end)

    rate = total_msgs / (time_ms / 1000.0)

    C.println_result(
      "one-to-many receivers=#{n_receivers} msgs-each=#{msgs_each}",
      rate,
      "msg/sec"
    )
  end

  # =============================================================================
  # Runner
  # =============================================================================

  def run_all do
    C.println_header("MESSAGING BENCHMARKS")

    C.println_subheader("Ping-Pong (latency)")
    Enum.each([1_000, 10_000, 100_000], &ping_pong/1)

    C.println_subheader("Send Throughput")
    Enum.each([10_000, 100_000, 1_000_000], &send_throughput/1)

    C.println_subheader("Receive Throughput")
    Enum.each([10_000, 100_000, 1_000_000], &receive_throughput/1)

    C.println_subheader("Selective Receive - Best Case (first)")
    Enum.each([100, 1_000, 10_000], &selective_receive_first/1)

    C.println_subheader("Selective Receive - Worst Case (last)")
    Enum.each([100, 1_000, 10_000], &selective_receive_last/1)

    C.println_subheader("Selective Receive - Miss (timeout)")
    Enum.each([100, 1_000, 10_000], &selective_receive_miss/1)

    C.println_subheader("Selective Receive - Repeated")
    selective_receive_repeated(1_000, 100)

    C.println_subheader("Many-to-One")
    many_to_one(10, 10_000)
    many_to_one(100, 1_000)

    C.println_subheader("One-to-Many")
    one_to_many(10, 10_000)
    one_to_many(100, 1_000)
  end
end
