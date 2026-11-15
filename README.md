# CSA09 – Programming in Java · Assignment 5 (CO5): Data Persistence Engineering – Files and Databases

| | |
|---|---|
| **Name** | `<YOUR NAME>` |
| **Register Number** | `<REGISTER NUMBER>` |
| **Java Version** | OpenJDK 21 (compiles on JDK 17+) – check yours with `java -version` |
| **Report** | [`report/Assignment5_Report.pdf`](report/Assignment5_Report.pdf) (also `.docx` and `.md`) |

## Repository layout

| Path | Contents |
|---|---|
| `TaskA1` … `TaskA5` | Part A – the code and measurements behind each design (the designs and pseudocode are in the report) |
| `TaskB1` … `TaskB4` | Part B – implementations and experiments |
| `TaskXX/output/*.txt` | console output of every run, with the exact commands at the top |
| `TaskXX/screenshots/*.png` | the same outputs rendered as terminal screenshots |
| `TaskXX/data/*.csv` | raw measurements |
| `TaskXX/graphs/*.png` | graphs |
| `TaskXX/input/` | input files (bus-pass files, damaged/adversarial files) |
| `sql/mysql/`, `sql/postgresql/` | **SQL scripts to create the test database** (setup + schema) |
| `db.properties` | the JDBC connection every program uses (`url`, `user`, `password`, `dialect`) |
| `db-mysql.properties` | ready-made settings for MySQL 8 |
| `lib/` | JDBC driver jars (see below) |
| `tools/` | `Db.java` (connection helper, copied into each JDBC task), `RunSql.java`, `minipg/` (test driver source), graph and screenshot scripts |
| `run_all.sh` | compiles and runs everything and writes all outputs |
| `git_commit_task.sh` | commits task by task |

## 1. Create the test database

The tests need a local test database created only for this assignment.

**MySQL 8 (recommended for submission)**

```bash
mysql -u root -p < sql/mysql/00_setup_as_root.sql          # database csa09_a5 + user csa09
cp db-mysql.properties db.properties                        # or run Java with -Ddb=../db-mysql.properties
# download MySQL Connector/J (platform-independent .jar) into lib/, e.g. lib/mysql-connector-j-9.1.0.jar
```

**PostgreSQL 16 (what the report's evidence was produced on)**

```bash
sudo -u postgres psql -f sql/postgresql/00_setup_as_superuser.sql
# db.properties already points at it, using lib/minipg-jdbc.jar
```

`run_all.sh` (re)creates the tables each time from `sql/<dialect>/01_schema.sql` using `tools/RunSql.java`.

> **About `lib/minipg-jdbc.jar`.** No MySQL server and no downloadable JDBC driver were available where the evidence was produced, so a small JDBC driver for PostgreSQL was written (source: `tools/minipg`). It talks to a **real** PostgreSQL server, supporting SCRAM login, prepared statements, batches, transactions, metadata and COPY. It is a test tool only. With MySQL Connector/J in `lib/` and `db-mysql.properties`, every program runs unchanged.

## 2. Run everything

```bash
bash run_all.sh                 # about 15 minutes (B3 inserts 100,000 rows several ways; B4 loads ~800,000)
bash run_all.sh TaskB1 TaskA4   # only some tasks
python3 tools/make_graphs.py && python3 tools/render_screenshots.py
```

On Windows, run it in Git Bash or WSL, or run the commands below by hand; use `;` instead of `:` in `-cp`. `TaskB1/count_syscalls.sh` needs Linux with `strace`.

## 3. Compile and run each task (from inside the task folder)

JDBC tasks use the classpath `out:../lib/*`, which picks up whichever driver jar is in `lib/`.

### Part A – designs and the experiments behind them

| Task | Commands | Result |
|---|---|---|
| **A1** File format | `javac -d out *.java` · `java -cp out A1Benchmark work` | Both layouts round-trip, including `Rao, "AK" Arun`; a record cut short is detected. RandomAccessFile seek vs text scan: 299× faster at 100,000 records, but 50.1 % of the fixed-record file is padding. |
| **A2** Buffering | `javac -d out ReadAllLinesVsBuffered.java` · `java -Xmx1g -cp out ReadAllLinesVsBuffered ../TaskB1/work/attendance_50MB.log` · `java -Xmx64m … readAllLines` | `readAllLines` needs 127.5 MB of heap for a 50 MB file and fails with a 64 MB heap; `BufferedReader` works with 16 MB. Wrapping 64 KB reads in a buffer gives nothing (32.3 vs 32.7 ms). |
| **A3** Generic DAO | `javac -d out $(find src -name '*.java')` · `java -cp 'out:../lib/*' A3Demo` · `… RenameExperiment before` · `java -Dstudent.email.column=email_id … RenameExperiment after` · `… OpTiming` | `Dao<T,K>` + `JdbcDao` replace three duplicated classes. A column rename touches 1 line instead of 10 (7 of 8 inline operations break). The cost per call is the same (119 vs 120 µs). |
| **A4** Serialization | `java -cp 'out:../lib/*' GraphDemo` · VersionDemo (4 compiled versions, see `run_all.sh`) · `java -Xmx2g -cp out FormatBenchmark` | password and Connection are transient; shared objects and cycles survive. A fixed serialVersionUID reads old files; without it, `InvalidClassException`. CSV vs serialization: 5× faster save, 17 % larger. |
| **A5** Transactions | `javac -d out src/*.java` · `java -cp 'out:../lib/*' A5Experiment` | The transaction conserves money after an exception, a killed JVM and 4 concurrent threads. Auto-commit loses ₹500 per failure. The transaction is also faster (699 vs 983 µs). |

### Part B – implementation and measurements

| Task | Commands | Result |
|---|---|---|
| **B1** File store (**Complete the Code: `PassStore.java`**) | `javac -d out PassStore.java && java -cp out PassStore` · `javac -d out *.java` · `java -cp out PassFileStoreTest` · `java -cp out MakeLogs work` · `bash count_syscalls.sh work/attendance_1MB.log` · `java -cp out BufferingBenchmark work` · `java -Xmx1g -cp out GrowthBenchmark work` | Output equals the expected output. 17/17 store tests pass (normal, missing ID, damaged line, checksum, power failure, missing file). strace: 1,048,541 read() calls for 1 MB unbuffered vs 128 buffered. 50 MB: 17.8 s vs 41.5 ms. find grows at about 0.1 µs/record. HashMap pays off after about 100 searches (53 MB). |
| **B2** Adversarial inputs | `javac -d out src/*.java` · `java -cp out MakeDamagedFiles && java -cp out DamagedFileLab` · `java -cp 'out:../lib/*' InjectionLab` | Concatenated SQL: login bypass, admin password theft through UNION, data changed by a stacked statement. PreparedStatement rejects everything. Escaping is beaten by `\'`; the regex locks out real users. The damaged file gives 12 bad lines reported with reasons; the given 5-field store accepts `45OO` and loses `"Das, Priya"`. |
| **B3** Statement / PreparedStatement / batch (**Complete the Code: `PassLoader.java`**) | `javac -d out -cp '../lib/*' src/*.java` · `java -Xmx2g -cp 'out:../lib/*' B3Lab` (`B3Lab quick` for a short run) | 10 rows correct for all three, with ResultSetMetaData. 100,000 rows: 343 µs/row (Statement, auto-commit) vs 26 µs/row (batch in one transaction). A failure half-way leaves 4,999 rows with auto-commit and 0 in one transaction. COPY / LOAD DATA reaches 233,695 rows/s. |
| **B4** Migration and decision | `javac -d out -cp '../lib/*' src/*.java` · `java -cp 'out:../lib/*' MigratePasses input/passes_campus1_2025.csv 1 output/migration_failures_run1.csv fresh` · `… InspectDatabase` · `java -Xmx3g -cp 'out:../lib/*' ScaleExperiment` | 7,906 of 8,000 passes migrated; 94 failures reported with line and reason; a re-run inserts 0 (safe to repeat). DatabaseMetaData start-up check. At 12 campuses × 66,667: database search 33 µs vs 2.6 s over serialized files; 12 concurrent counters lose 0 passes vs 33 of 40 lost with a shared file. **Decision: a shared database.** |

## About the screenshots

The screenshots are each program's real console output (`output/*.txt`), rendered as terminal images by `tools/render_screenshots.py`. If your faculty wants screenshots of your own terminal, run the commands above and capture them, ideally against your MySQL database.

## Committing progressively (at least one commit per task)

```bash
bash git_commit_task.sh init https://github.com/<user>/CSA09-<RegisterNumber>-Assignment5.git
bash git_commit_task.sh A1      # then A2 ... A5, B1 ... B4 - on different days
bash git_commit_task.sh report
```
