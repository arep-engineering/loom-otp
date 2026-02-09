(defproject loom-otp "0.1.0-SNAPSHOT"
  :description "Erlang/OTP-style actor concurrency for Clojure using Project Loom virtual threads"
  :url "https://github.com/your-org/loom-otp"
  :license {:name "EPL-1.0"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.match "1.1.0"]
                 [functionalbytes/mount-lite "2.3.2"]]
  :java-source-paths ["src/java"]
  :javac-options ["--release" "21"]
  :repl-options {:init-ns loom-otp.process}
  :test-selectors {:parallel :parallel
                   :exhaustive :exhaustive
                   :all (constantly true)
                   :default (complement :exhaustive)}
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]
                                  [org.clojure/math.combinatorics "0.1.6"]
                                  [org.clojure/core.async "1.6.681"]]}
             
             ;; G1GC benchmarks (default)
             :bench {:source-paths ["bench"]
                     :main loom-otp.bench.runner
                     :jvm-opts ["-Xmx8g" "-Xms8g"
                                "-Xss512k"
                                "-XX:+UseG1GC"
                                "-XX:MaxGCPauseMillis=10"
                                "-XX:+AlwaysPreTouch"
                                "-XX:+ParallelRefProcEnabled"
                                "-Djdk.virtualThreadScheduler.parallelism=22"
                                "-Djdk.virtualThreadScheduler.maxPoolSize=256"]}
             
             ;; ZGC benchmarks (ZGC is generational by default since JDK 23)
             :bench-zgc {:source-paths ["bench"]
                         :main loom-otp.bench.runner
                         :jvm-opts ["-Xmx8g" "-Xms8g"
                                    "-Xss512k"
                                    "-XX:+UseZGC"
                                    "-XX:+AlwaysPreTouch"
                                    "-Djdk.virtualThreadScheduler.parallelism=22"
                                    "-Djdk.virtualThreadScheduler.maxPoolSize=256"]}
             
             ;; Shenandoah benchmarks
             :bench-shenandoah {:source-paths ["bench"]
                                :main loom-otp.bench.runner
                                :jvm-opts ["-Xmx8g" "-Xms8g"
                                           "-Xss512k"
                                           "-XX:+UseShenandoahGC"
                                           "-XX:+AlwaysPreTouch"
                                           "-Djdk.virtualThreadScheduler.parallelism=22"
                                           "-Djdk.virtualThreadScheduler.maxPoolSize=256"]}
             
             ;; otplike compat layer - G1GC (default)
             :otplike-bench {:source-paths ["bench"]
                             :main otplike.bench.runner
                             :jvm-opts ["-Xmx8g" "-Xms8g"
                                        "-Xss512k"
                                        "-XX:+UseG1GC"
                                        "-XX:MaxGCPauseMillis=10"
                                        "-XX:+AlwaysPreTouch"
                                        "-XX:+ParallelRefProcEnabled"
                                        "-Djdk.virtualThreadScheduler.parallelism=22"
                                        "-Djdk.virtualThreadScheduler.maxPoolSize=256"]}
             
             ;; otplike compat layer - ZGC
             :otplike-bench-zgc {:source-paths ["bench"]
                                 :main otplike.bench.runner
                                 :jvm-opts ["-Xmx8g" "-Xms8g"
                                            "-Xss512k"
                                            "-XX:+UseZGC"
                                            "-XX:+AlwaysPreTouch"
                                            "-Djdk.virtualThreadScheduler.parallelism=22"
                                            "-Djdk.virtualThreadScheduler.maxPoolSize=256"]}
             
             ;; otplike compat layer - Shenandoah
             :otplike-bench-shenandoah {:source-paths ["bench"]
                                        :main otplike.bench.runner
                                        :jvm-opts ["-Xmx8g" "-Xms8g"
                                                   "-Xss512k"
                                                   "-XX:+UseShenandoahGC"
                                                   "-XX:+AlwaysPreTouch"
                                                   "-Djdk.virtualThreadScheduler.parallelism=22"
                                                   "-Djdk.virtualThreadScheduler.maxPoolSize=256"]}})
