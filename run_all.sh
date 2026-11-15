#!/usr/bin/env bash
# CSA09 Assignment 5 - compiles and runs every program. Each console output is saved to
# TaskXX/output/<program>.txt (the commands are at the top of each file); measurements go to TaskXX/data/*.csv.
#
#   bash run_all.sh                 everything (about 15 minutes; the database must be running)
#   bash run_all.sh TaskB1 TaskA4   only the listed tasks
#
# Database: db.properties (see README). The JDBC driver jar must be in lib/ - the MySQL Connector/J jar for MySQL,
# or lib/minipg-jdbc.jar (built below from tools/minipg) for the PostgreSQL test setup used for the report.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
unset JAVA_TOOL_OPTIONS 2>/dev/null || true
ONLY="${*:-TaskA1 TaskA2 TaskA3 TaskA4 TaskA5 TaskB1 TaskB2 TaskB3 TaskB4}"
CP='out:../lib/*'
DIALECT=$(grep -E '^dialect=' "$ROOT/db.properties" | cut -d= -f2 | tr -d '[:space:]')

run() {   # run <folder> <output-name> <compile> <run>
  case " $ONLY " in *" $1 "*) ;; *) return ;; esac
  cd "$ROOT/$1" || exit 1
  mkdir -p output
  { echo "\$ cd $1"; echo "\$ $3"; eval "$3" 2>&1; echo "\$ $4"; eval "$4" 2>&1; echo "(exit code $?)"; } > "output/$2.txt"
  echo "  $1/output/$2.txt"
}

echo "Java: $(java -version 2>&1 | head -1)"
# 0. the PostgreSQL test driver (skipped when it is already built) and the schema
if [ ! -f "$ROOT/lib/minipg-jdbc.jar" ]; then
  (cd "$ROOT/tools/minipg" && rm -rf build && mkdir build && javac -d build $(find src -name "*.java") && cp -r src/META-INF build/ && jar cf ../../lib/minipg-jdbc.jar -C build .) && echo "  built lib/minipg-jdbc.jar"
fi
(cd "$ROOT" && javac -d tools/out tools/Db.java tools/RunSql.java && java -cp "tools/out:lib/*" RunSql "sql/$DIALECT/01_schema.sql")
# the attendance logs (1, 10, 50 MB) used by TaskA2 and TaskB1 are generated, not committed
[ -f "$ROOT/TaskB1/work/attendance_50MB.log" ] || (cd "$ROOT/TaskB1" && javac -d out MakeLogs.java && java -cp out MakeLogs work)

# ---------------------------------------------------------------- Part A (supporting experiments)
run TaskA1 A1Benchmark        "javac -d out *.java"                                   "java -cp out A1Benchmark work"
run TaskA2 ReadAllLines_1g    "javac -d out ReadAllLinesVsBuffered.java"              "java -Xmx1g -cp out ReadAllLinesVsBuffered ../TaskB1/work/attendance_50MB.log"
run TaskA2 ReadAllLines_small_heap "javac -d out ReadAllLinesVsBuffered.java"         "java -Xmx64m -cp out ReadAllLinesVsBuffered ../TaskB1/work/attendance_50MB.log readAllLines; java -Xmx16m -cp out ReadAllLinesVsBuffered ../TaskB1/work/attendance_50MB.log bufferedReader"
run TaskA3 A3Demo             "javac -d out \$(find src -name '*.java')"             "java -cp '$CP' A3Demo"
run TaskA3 RenameExperiment   "javac -d out \$(find src -name '*.java')"             "java -cp '$CP' RenameExperiment before; java -Dstudent.email.column=email_id -cp '$CP' RenameExperiment after"
run TaskA3 OpTiming           "javac -d out \$(find src -name '*.java')"             "java -cp '$CP' OpTiming"
run TaskA4 GraphDemo          "javac -d out \$(find src -name '*.java')"             "java -cp '$CP' GraphDemo"
run TaskA4 VersionDemo        "for v in uid_v1 uid_v2 nouid_v1 nouid_v2; do javac -d out_\$v versions/\$v/hostel/*.java VersionDemo.java; done; mkdir -p work" \
                              "java -cp out_uid_v1 VersionDemo write work/v1_uid.ser; java -cp out_uid_v2 VersionDemo read work/v1_uid.ser; java -cp out_uid_v2 VersionDemo write work/v2_uid.ser; java -cp out_uid_v1 VersionDemo read work/v2_uid.ser; java -cp out_nouid_v1 VersionDemo write work/v1_nouid.ser; java -cp out_nouid_v2 VersionDemo read work/v1_nouid.ser"
run TaskA4 FormatBenchmark    "javac -d out \$(find src -name '*.java')"             "java -Xmx2g -cp out FormatBenchmark"
run TaskA5 A5Experiment       "javac -d out src/*.java"                               "java -cp '$CP' A5Experiment"

# ---------------------------------------------------------------- Part B
run TaskB1 PassStore_CompleteTheCode "javac -d out PassStore.java"                    "java -cp out PassStore"
run TaskB1 PassFileStoreTest  "javac -d out *.java"                                   "java -cp out PassFileStoreTest"
run TaskB1 MakeLogs           "javac -d out *.java"                                   "java -cp out MakeLogs work"
run TaskB1 SyscallCount_1MB   "javac -d out *.java"                                   "bash count_syscalls.sh work/attendance_1MB.log"
run TaskB1 BufferingBenchmark "javac -d out *.java"                                   "java -cp out BufferingBenchmark work"
run TaskB1 GrowthBenchmark    "javac -d out *.java"                                   "java -Xmx1g -cp out GrowthBenchmark work"
run TaskB2 DamagedFileLab     "javac -d out src/*.java"                               "java -cp out MakeDamagedFiles && java -cp out DamagedFileLab"
run TaskB2 InjectionLab       "javac -d out src/*.java"                               "java -cp '$CP' InjectionLab"
run TaskB3 B3Lab              "javac -d out -cp '../lib/*' src/*.java"                "java -Xmx2g -cp '$CP' B3Lab"
run TaskB4 Migration_run1     "javac -d out -cp '../lib/*' src/*.java"                "java -cp '$CP' MigratePasses input/passes_campus1_2025.csv 1 output/migration_failures_run1.csv fresh"
run TaskB4 Migration_run2_rerun "javac -d out -cp '../lib/*' src/*.java"              "java -cp '$CP' MigratePasses input/passes_campus1_2025.csv 1 output/migration_failures_run2.csv"
run TaskB4 InspectDatabase    "javac -d out -cp '../lib/*' src/*.java"                "java -cp '$CP' InspectDatabase"
run TaskB4 ScaleExperiment    "javac -d out -cp '../lib/*' src/*.java"                "java -Xmx3g -cp '$CP' ScaleExperiment"

echo "Done. Graphs: python3 tools/make_graphs.py   Screenshots: python3 tools/render_screenshots.py"
