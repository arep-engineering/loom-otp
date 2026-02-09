#!/usr/bin/env bash

# Run all benchmarks for all implementations and GC combinations
# Results are saved to the results/ directory
#
# Usage:
#   ./run-all-benchmarks.sh           # Run all benchmarks (skips existing results)
#   ./run-all-benchmarks.sh --force   # Run all benchmarks (overwrites existing results)
#   ./run-all-benchmarks.sh --dry-run # Show what would be run without executing

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"

DRY_RUN=false
FORCE=false

for arg in "$@"; do
    case $arg in
        --dry-run)
            DRY_RUN=true
            ;;
        --force)
            FORCE=true
            ;;
    esac
done

# Create results directory
if [ "$DRY_RUN" = true ]; then
    echo "[DRY RUN] Would create directory: $RESULTS_DIR"
else
    mkdir -p "$RESULTS_DIR"
fi

run_benchmark() {
    local impl="$1"
    local gc="$2"
    local output_file="$3"
    local start_time
    local end_time
    local duration
    
    echo "=============================================="
    echo "Running: $impl with $gc"
    echo "Output:  $output_file"
    echo "=============================================="
    
    # Check if result already exists
    if [ "$FORCE" = false ] && [ -f "$output_file" ] && [ -s "$output_file" ]; then
        echo "SKIPPED: Result file already exists (use --force to overwrite)"
        echo ""
        return 0
    fi
    
    if [ "$DRY_RUN" = true ]; then
        echo "[DRY RUN] Would execute: ./bench-compare.sh --impl=$impl --gc=$gc"
        echo ""
    else
        start_time=$(date +%s)
        
        # Run benchmark and save output
        "$SCRIPT_DIR/bench-compare.sh" --impl="$impl" --gc="$gc" > "$output_file" 2>&1
        
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        echo "Completed in ${duration}s"
        echo ""
    fi
}

run_elixir_benchmark() {
    local output_file="$1"
    local start_time
    local end_time
    local duration
    
    echo "=============================================="
    echo "Running: Elixir/BEAM"
    echo "Output:  $output_file"
    echo "=============================================="
    
    # Check if result already exists
    if [ "$FORCE" = false ] && [ -f "$output_file" ] && [ -s "$output_file" ]; then
        echo "SKIPPED: Result file already exists (use --force to overwrite)"
        echo ""
        return 0
    fi
    
    if [ "$DRY_RUN" = true ]; then
        echo "[DRY RUN] Would execute: ./bench-compare.sh --impl=elixir"
        echo ""
    else
        start_time=$(date +%s)
        
        # Run benchmark and save output
        "$SCRIPT_DIR/bench-compare.sh" --impl=elixir > "$output_file" 2>&1
        
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        echo "Completed in ${duration}s"
        echo ""
    fi
}

echo "=============================================="
echo "Benchmark Suite - Full Run"
echo "Started: $(date)"
echo "Results directory: $RESULTS_DIR"
if [ "$FORCE" = true ]; then
    echo "Mode: FORCE (overwriting existing results)"
else
    echo "Mode: Normal (skipping existing results)"
fi
echo "=============================================="
echo ""

TOTAL_START=$(date +%s)

# Elixir (only one GC - BEAM's)
run_elixir_benchmark "$RESULTS_DIR/elixir.txt"

# loom-otp with each GC
run_benchmark "loom-otp" "G1GC" "$RESULTS_DIR/loom-otp-g1gc.txt"
run_benchmark "loom-otp" "ZGC" "$RESULTS_DIR/loom-otp-zgc.txt"
run_benchmark "loom-otp" "Shenandoah" "$RESULTS_DIR/loom-otp-shenandoah.txt"

# otplike-compat with each GC
run_benchmark "otplike-compat" "G1GC" "$RESULTS_DIR/otplike-compat-g1gc.txt"
run_benchmark "otplike-compat" "ZGC" "$RESULTS_DIR/otplike-compat-zgc.txt"
run_benchmark "otplike-compat" "Shenandoah" "$RESULTS_DIR/otplike-compat-shenandoah.txt"

# otplike with each GC
run_benchmark "otplike" "G1GC" "$RESULTS_DIR/otplike-g1gc.txt"
run_benchmark "otplike" "ZGC" "$RESULTS_DIR/otplike-zgc.txt"
run_benchmark "otplike" "Shenandoah" "$RESULTS_DIR/otplike-shenandoah.txt"

TOTAL_END=$(date +%s)
TOTAL_DURATION=$((TOTAL_END - TOTAL_START))

echo "=============================================="
echo "Benchmark Suite Complete"
echo "Finished: $(date)"
echo "Total duration: ${TOTAL_DURATION}s ($((TOTAL_DURATION / 60))m $((TOTAL_DURATION % 60))s)"
echo "Results saved to: $RESULTS_DIR"
echo "=============================================="

if [ "$DRY_RUN" = false ]; then
    # List generated files
    echo ""
    echo "Generated files:"
    ls -la "$RESULTS_DIR"/*.txt
    push "benchmark complete"
fi
