# Lab 1 — Concurrency in Practice (Java)

A Java implementation of the Lab 1 assignment: measuring how thread count
and work-division strategy affect execution time and peak memory of a real
workload, identifying CPU-bound vs I/O-bound behaviour, observing a race
condition and the cost of fixing it, and finding where parallel speed-up
stops.

---

## 1. Hardware and software

Fill in your machine's details (obtained with `lscpu` / `systeminfo` /
`About This Mac`, and `java -version`):

| Item | Value |
|---|---|
| CPU model | `<e.g. Intel Core i7-1165G7>` |
| Physical cores | `<e.g. 4>` |
| Logical processors | `<e.g. 8>` |
| RAM | `<e.g. 16 GB>` |
| Internal disk | `<e.g. NVMe SSD>` |
| USB drive | `<e.g. SanDisk Ultra 32 GB, USB 3.0>` |
| Operating system | `<e.g. Windows 11 23H2>` |
| Java runtime | `<e.g. OpenJDK 17.0.10>` |
| `javac` version | `<e.g. 17.0.10>` |

> **Note:** `javac` and `java` must be the same major version.
> If you get `UnsupportedClassVersionError`, see
> [Troubleshooting](#7-troubleshooting).

---

## 2. Project layout

```
lab1-concurrency/
├── src/
│   └── lab1/
│       ├── Utils.java            # shared helpers: Counts, timing, memory
│       ├── DataGenerator.java    # creates numbers.txt + parts/
│       ├── Task1a.java           # N threads, byte-range split
│       ├── Task1b.java           # 3 counting styles on shared array
│       ├── Task1c.java           # producer / consumers with blocking queue
│       └── Task2.java            # 8 files on USB, 1/2/4 threads
├── numbers.txt                   # generated (5 000 000 lines)
├── parts/                        # generated (8 × 625 000 lines)
├── out/                          # compiled .class files
└── README.md
```

---

## 3. Build

From the project root, in PowerShell:

### PowerShell

```powershell
# Clean previous build (optional)
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path out | Out-Null

# Compile
javac -d out src\lab1\*.java
```

### If your `java` is older than your `javac`

Compile for the runtime you actually have:

```bash
javac --release 8  -d out src/lab1/*.java     # Java 8 runtime
javac --release 11 -d out src/lab1/*.java     # Java 11 runtime
javac --release 17 -d out src/lab1/*.java     # Java 17 runtime
```

---

## 4. Run

Run each task **in order**. Tasks 1a–1c need `numbers.txt`;
Task 2 also needs `parts/` copied to the USB drive.

### 4.1 Generate the data (once)

```bash
java -cp out lab1.DataGenerator
```

Produces:

- `numbers.txt` — 5 000 000 random integers in `[-1 000 000, 1 000 000]`, one per line.
- `parts/part0.txt … part7.txt` — 8 files of 625 000 lines each, line-aligned so no number is split across files.

### 4.2 Task 1a — Counting with threads

```bash
java -Xmx2g -cp out lab1.Task1a
```

- Thread counts: 1, 2, 4, 8, 16, 32, 64.
- Each thread reads and parses only its own byte range of `numbers.txt`,
  snapped to `\n` boundaries so no line is duplicated or skipped.
- Prints time (ms) and peak memory delta (MB) per configuration.

### 4.3 Task 1b — Three ways to count

```bash
java -Xmx2g -cp out lab1.Task1b
```

- Loads all 5 000 000 numbers into an `int[]` (not measured).
- Runs with `Runtime.getRuntime().availableProcessors()` threads:
  1. **V1** — shared counters, no synchronisation (3 runs; totals should differ → race condition visible).
  2. **V2** — shared counters under a `ReentrantLock` (correct, but contended).
  3. **V3** — per-thread counters, reduced after join (correct and fast).

### 4.4 Task 1c — Producer / consumers

```bash
java -Xmx2g -cp out lab1.Task1c
```

- One reader thread puts lines into batches of 10 000 into a bounded
  `ArrayBlockingQueue` (capacity 100 batches).
- Worker threads take batches and count signs.
- Worker counts: 1, 2, 4, 8, 16, 32, 64.

### 4.5 Task 2 — Many files on slow storage

1. Copy `parts/` to the USB drive, e.g. `E:\parts\`.
2. **Before every run**, eject the drive, unplug it, plug it back in
   (so the OS file cache does not serve the data).
3. On macOS, also disable Spotlight indexing for the volume:
   `mdutil -i off /Volumes/YOURUSB`
4. Run:

```bash
java -Xmx2g -cp out lab1.Task2 E:\parts
# or on Linux/macOS:
java -Xmx2g -cp out lab1.Task2 /Volumes/YOURUSB/parts
```

- Thread counts: 1, 2, 4.
- Each file is read in a **single** `Files.readAllBytes` call (one byte
  buffer per file, not a list of lines).

---

## 5. Correctness check

For every task and every configuration, the totals must satisfy:

```
neg + pos + zero = 5 000 000
```

and be **identical** across all tasks. If a total differs between runs
of Task 1b version 1, that is expected — it is the race condition.

A quick manual sanity check with a tiny input:

```bash
printf '0\n-1\n5\n-7\n0\n' > tiny.txt
# (Task 1a/2 hard-code numbers.txt; either temporarily rename or use a
#  small script that calls Utils.countRange on tiny.txt)
```

Expected: `neg=2 pos=1 zero=2`.

---

## 6. Measurements to include in the report

For each task, produce three charts (execution time, speed-up relative
to 1 thread, peak memory) against number of threads. Suggested axes:

- X: number of threads (1, 2, 4, 8, 16, 32, 64; or 1, 2, 4 for Task 2).
- Y1: time in ms.
- Y2: speed-up = T(1) / T(n).
- Y3: peak memory in MB.

Then answer the lab's questions per task (see Section 8 below).

---

## 7. Troubleshooting

### `UnsupportedClassVersionError: … class file version 61.0 … up to 52.0`

`javac` is newer than `java`. Either:

- Compile for the old runtime: `javac --release 8 -d out src/lab1/*.java`, or
- Make `java` and `javac` the same version (preferred). Put the JDK's
  `bin` folder **before** the JRE's in `Path`, and re-open the terminal.

Verify with:

```bash
javac -version
java  -version
```

Both must print the same major version.

### `Remove-Item : A positional parameter cannot be found that accepts argument '/q'`

You are in PowerShell, not CMD. Use:

```powershell
Remove-Item -Recurse -Force src
```

### `Error: Could not find or load main class lab1.DataGenerator`

You forgot `-cp out`, or you are not in the project root. Run from the
folder that contains `out/`.

### `OutOfMemoryError` in Task 1b

Run with more heap:

```bash
java -Xmx2g -cp out lab1.Task1b
```

5 000 000 ints need ~20 MB, plus JVM overhead. 2 GB is plenty.

### Task 2 reads from cache, not from USB

You must eject / unplug / replug the USB drive before each run.
On macOS, also disable Spotlight for the volume.

---

## 8. Report checklist

1. Hardware description (Section 1 above).
2. Charts per task: time, speed-up, peak memory.
3. Answers to the task questions:
   - Task 1a: speed-up limit vs physical/logical CPUs; why 32/64 slow down; memory vs threads.
   - Task 1b: ranking and correctness of the three versions.
   - Task 1c: comparison with 1a; where speed-up stops; 1-line batches; unbounded queue.
   - Task 2: CPU- vs I/O-bound on USB; why more threads do not help.
4. Conclusion: which work was CPU-bound, which was I/O-bound, and how you know.
5. Source code + build/run instructions (this file).

---

## 9. Notes on the implementation

- **Line-safe splitting (Task 1a).** Byte ranges are snapped forward to
  the first `\n`, so no number is split and none is counted twice. Each
  thread memory-maps only its own range via `FileChannel.map`.
- **Race condition (Task 1b V1).** `shared.neg++` is a read-modify-write;
  without synchronisation, updates are lost. Running three times and
  comparing totals demonstrates the bug.
- **Bounded queue (Task 1c).** `ArrayBlockingQueue` of 100 batches.
  Producer blocks when full, consumers block when empty. At EOF the
  reader inserts one empty-list poison pill per worker.
- **Single-read file (Task 2).** `Files.readAllBytes` reads the whole
  file into one byte buffer, then a single pass parses it. This is the
  "one thread reads it in a single operation" requirement.

---

## 10. License / authorship

Coursework for *Laboratory Work 1 — Concurrency in Practice*.
Author: Bulimar Rodion, group FAF-242, 13.10.2026.
