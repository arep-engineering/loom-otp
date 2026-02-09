#!/usr/bin/env bash

# Benchmark comparison script for Elixir/BEAM, loom-otp, otplike-compat, and otplike
#
# Usage:
#   ./bench-compare.sh [OPTIONS] [BENCHMARK]
#
# BENCHMARK: spawn, messaging, memory, scaling, leaks, or empty for all
#
# OPTIONS:
#   --gc=G1GC|ZGC|Shenandoah   Select garbage collector (default: G1GC)
#   --impl=IMPL                Run only specific implementation:
#                              elixir, loom-otp, otplike-compat, otplike, all (default: all)
#   --help                     Show this help message
#
# Examples:
#   ./bench-compare.sh spawn                    # Run spawn benchmarks with G1GC on all implementations
#   ./bench-compare.sh --gc=ZGC spawn           # Run spawn benchmarks with ZGC
#   ./bench-compare.sh --gc=Shenandoah          # Run all benchmarks with Shenandoah
#   ./bench-compare.sh --impl=loom-otp spawn    # Run spawn benchmarks only on loom-otp
#   ./bench-compare.sh --impl=elixir messaging  # Run messaging benchmarks only on Elixir

set -e

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Defaults
GC="G1GC"
IMPL="all"
BENCHMARK=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --gc=*)
            GC="${1#*=}"
            shift
            ;;
        --impl=*)
            IMPL="${1#*=}"
            shift
            ;;
        --help)
            head -24 "$0" | tail -22
            exit 0
            ;;
        *)
            BENCHMARK="$1"
            shift
            ;;
    esac
done

# Map GC to lein profile suffix
case $GC in
    G1GC)
        LOOM_PROFILE="bench"
        OTPLIKE_COMPAT_PROFILE="otplike-bench"
        OTPLIKE_PROFILE="bench"
        ;;
    ZGC)
        LOOM_PROFILE="bench-zgc"
        OTPLIKE_COMPAT_PROFILE="otplike-bench-zgc"
        OTPLIKE_PROFILE="bench-zgc"
        ;;
    Shenandoah)
        LOOM_PROFILE="bench-shenandoah"
        OTPLIKE_COMPAT_PROFILE="otplike-bench-shenandoah"
        OTPLIKE_PROFILE="bench-shenandoah"
        ;;
    *)
        echo "Unknown GC: $GC (valid options: G1GC, ZGC, Shenandoah)"
        exit 1
        ;;
esac

# Convert benchmark argument to Elixir list format
ELIXIR_ARGS="[]"
if [ -n "$BENCHMARK" ]; then
    ELIXIR_ARGS="[\"$BENCHMARK\"]"
fi

echo "=============================================="
echo "Benchmark Configuration"
echo "=============================================="
echo "GC:         $GC"
echo "Impl:       $IMPL"
echo "Benchmark:  ${BENCHMARK:-all}"
echo "=============================================="
echo ""

# Run Elixir benchmarks
if [ "$IMPL" = "all" ] || [ "$IMPL" = "elixir" ]; then
    echo "=== ELIXIR/BEAM BENCHMARKS ==="
    cd "$SCRIPT_DIR/bench_elixir" && mix run -e "BenchElixir.Runner.main($ELIXIR_ARGS)"
    echo ""
fi

# Run loom-otp benchmarks
if [ "$IMPL" = "all" ] || [ "$IMPL" = "loom-otp" ]; then
    echo "=== LOOM-OTP BENCHMARKS (GC: $GC) ==="
    cd "$SCRIPT_DIR/loom-otp" && lein with-profile "$LOOM_PROFILE" run "$BENCHMARK"
    echo ""
fi

# Run otplike-compat benchmarks
if [ "$IMPL" = "all" ] || [ "$IMPL" = "otplike-compat" ]; then
    echo "=== OTPLIKE VIA LOOM-OTP COMPAT LAYER BENCHMARKS (GC: $GC) ==="
    cd "$SCRIPT_DIR/loom-otp" && lein with-profile "$OTPLIKE_COMPAT_PROFILE" run "$BENCHMARK"
    echo ""
fi

# Run otplike benchmarks
if [ "$IMPL" = "all" ] || [ "$IMPL" = "otplike" ]; then
    echo "=== OTPLIKE BENCHMARKS (GC: $GC) ==="
    cd "$SCRIPT_DIR/otplike" && lein with-profile "$OTPLIKE_PROFILE" run "$BENCHMARK"
    echo ""
fi

echo "=============================================="
echo "Benchmarks complete"
echo "=============================================="
