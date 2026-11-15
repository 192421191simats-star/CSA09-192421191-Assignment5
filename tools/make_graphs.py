"""
Builds the graphs for the Assignment 5 report from TaskXX/data/*.csv (written by run_all.sh).
Usage: python3 tools/make_graphs.py      (from the repository root)
"""
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
SLOTS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
MARKERS = ["o", "s", "^", "D", "v", "P", "X", "*"]
INK, MUTED, GRID = "#2b2b29", "#6b6a64", "#e4e3de"


def rows(path):
    with open(ROOT / path) as f:
        return list(csv.DictReader(f))


def setup(ax, title, xlabel, ylabel, logx=False, logy=False):
    ax.set_title(title, fontsize=10.5, color=INK, loc="left", fontweight="bold")
    ax.set_xlabel(xlabel, color=MUTED, fontsize=9)
    ax.set_ylabel(ylabel, color=MUTED, fontsize=9)
    if logx: ax.set_xscale("log")
    if logy: ax.set_yscale("log")
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ["top", "right"]: ax.spines[s].set_visible(False)
    for s in ["left", "bottom"]: ax.spines[s].set_color(GRID)
    ax.tick_params(colors=MUTED, labelsize=8)


def line(ax, x, y, i, label, end_label=True):
    ax.plot(x, y, color=SLOTS[i], marker=MARKERS[i], markersize=8, linewidth=2, label=label, markeredgecolor="white", markeredgewidth=1.5)
    if end_label:
        ax.annotate(label, (x[-1], y[-1]), xytext=(6, 0), textcoords="offset points", fontsize=8, color=INK, va="center")


def bars(ax, labels, values, colors, fmt="{:.0f}"):
    b = ax.bar(labels, values, color=colors, width=0.62, edgecolor="white", linewidth=2)
    for r, v in zip(b, values):
        ax.annotate(fmt.format(v), (r.get_x() + r.get_width() / 2, v), xytext=(0, 3), textcoords="offset points", ha="center", fontsize=8, color=INK)
    ax.tick_params(axis="x", labelsize=7.5)


def save(fig, task, name):
    out = ROOT / task / "graphs"
    out.mkdir(exist_ok=True)
    fig.tight_layout()
    fig.savefig(out / name, dpi=160)
    plt.close(fig)
    print("wrote", (out / name).relative_to(ROOT))


# A1 - text scan vs RandomAccessFile seek
d = rows("TaskA1/data/a1_find.csv")
n = [int(r["records"]) for r in d]
fig, ax = plt.subplots(figsize=(7.2, 3.6))
setup(ax, "A1 - find one pass by ID", "records in the file (log scale)", "ms per find (log scale)", True, True)
line(ax, n, [float(r["text_scan_ms"]) for r in d], 0, "text file: linear scan")
line(ax, n, [float(r["raf_seek_ms"]) for r in d], 1, "RandomAccessFile: seek")
ax.legend(fontsize=8, frameon=False, loc="center left"); ax.set_xlim(600, 400000)
save(fig, "TaskA1", "a1_find.png")

# A2 - readAllLines vs streaming (two panels, one measure each)
d = rows("TaskA2/data/a2_readall.csv")
lab = [r["method"].replace("BufferedReader.readLine()", "BufferedReader\nreadLine()").replace("Files.readAllLines()", "Files.\nreadAllLines()")
       .replace("Files.lines() stream", "Files.lines()\nstream").replace("read(byte[64 KB])", "read(byte[\n64 KB])") for r in d]
fig, axes = plt.subplots(1, 2, figsize=(9.2, 3.6))
setup(axes[0], "A2 - time to count 50 MB of lines", "", "ms")
bars(axes[0], lab, [float(r["median_ms"]) for r in d], [SLOTS[1], SLOTS[0], SLOTS[2], SLOTS[3]])
setup(axes[1], "Peak extra heap during the call", "", "MB")
bars(axes[1], lab, [float(r["peak_heap_mb"]) for r in d], [SLOTS[1], SLOTS[0], SLOTS[2], SLOTS[3]], "{:.1f}")
save(fig, "TaskA2", "a2_readall.png")

# A4 - formats at 100,000 residents (three panels)
d = [r for r in rows("TaskA4/data/a4_formats.csv") if r["residents"] == "100000"]
lab = ["Java\nserialization", "CSV", "JSON"]
fig, axes = plt.subplots(1, 3, figsize=(9.6, 3.4))
for ax, key, title, unit, f in [(axes[0], "bytes", "File size", "MB", lambda v: v / 1048576), (axes[1], "save_ms", "Save", "ms", float), (axes[2], "load_ms", "Load", "ms", float)]:
    setup(ax, title + " - 100,000 residents", "", unit)
    bars(ax, lab, [f(float(r[key])) for r in d], [SLOTS[0], SLOTS[1], SLOTS[2]], "{:.1f}" if unit == "MB" else "{:.0f}")
save(fig, "TaskA4", "a4_formats.png")

# B1 - buffering
d = rows("TaskB1/data/b1_buffering.csv")
fig, ax = plt.subplots(figsize=(7.2, 3.8))
setup(ax, "B1.3 - time to count lines", "file size (MB, log scale)", "ms (log scale)", True, True)
names = {"unbuffered": "FileInputStream.read() per byte", "buffered": "BufferedInputStream (8 KB)", "reader": "BufferedReader.readLine()", "array": "read(byte[64 KB])"}
for i, m in enumerate(["unbuffered", "buffered", "reader", "array"]):
    pts = [r for r in d if r["mode"] == m]
    line(ax, [int(r["file_mb"]) for r in pts], [float(r["median_ms"]) for r in pts], i, names[m])
ax.set_xlim(0.7, 300)
save(fig, "TaskB1", "b1_buffering.png")

# B1 - growth + HashMap alternative (two panels)
g = rows("TaskB1/data/b1_growth.csv")
h = [r for r in rows("TaskB1/data/b1_hashmap.csv") if r["records"] == "100000"]
fig, axes = plt.subplots(1, 2, figsize=(9.6, 3.6))
setup(axes[0], "B1.4 - find(passId) by scan", "records", "ms per find")
x = [int(r["records"]) for r in g]
line(axes[0], x, [float(r["missing_ms"]) for r in g], 0, "ID not in file", False)
line(axes[0], x, [float(r["middle_ms"]) for r in g], 1, "ID in the middle", False)
line(axes[0], x, [float(r["first_ms"]) for r in g], 2, "first record", False)
axes[0].legend(fontsize=8, frameon=False, loc="upper left")
setup(axes[1], "q searches on 100,000 passes", "searches q (log scale)", "total ms (log scale)", True, True)
q = [int(r["q"]) for r in h]
line(axes[1], q, [float(r["scan_total_ms"]) for r in h], 0, "scan the file q times")
line(axes[1], q, [float(r["map_build_ms"]) + float(r["map_lookup_total_ms"]) for r in h], 1, "HashMap: load once + q gets")
axes[1].set_xlim(0.7, 8000)
save(fig, "TaskB1", "b1_growth.png")

# B3 - insert times
d = rows("TaskB3/data/b3_insert_times.csv")
fig, ax = plt.subplots(figsize=(7.6, 4.0))
setup(ax, "B3.2 - time to insert n passes", "rows (log scale)", "ms (log scale)", True, True)
order = ["(a) Statement", "(b) PreparedStatement", "(c) batch, 1,000 per executeBatch", "(a) Statement - inside 1 transaction", "(b) PreparedStatement - inside 1 transaction"]
short = ["Statement", "PreparedStatement", "batch (auto-commit)", "Statement in 1 tx", "PreparedStatement in 1 tx"]
for i, a in enumerate(order):
    pts = [r for r in d if r["approach"] == a]
    if pts:
        line(ax, [int(r["rows"]) for r in pts], [float(r["ms"]) for r in pts], i, short[i], False)
ax.legend(fontsize=8, frameon=False, loc="upper left")
save(fig, "TaskB3", "b3_insert_times.png")

d = rows("TaskB3/data/b3_alternatives.csv")
fig, axes = plt.subplots(1, 2, figsize=(10, 3.8))
lab = [r["approach"].replace("PreparedStatement batch, ", "batch\n").replace("multi-row INSERT, ", "multi-row\n").replace(" rows/statement", "").replace("COPY FROM STDIN (streamed)", "COPY\nstreamed").replace("LOAD DATA LOCAL INFILE (streamed)", "LOAD DATA\nstreamed") for r in d]
cols = [SLOTS[0]] * 3 + [SLOTS[1]] * 2 + [SLOTS[2]]
setup(axes[0], "Alternatives - rows per second (one transaction)", "", "rows / s")
bars(axes[0], lab, [float(r["rows_per_s"]) for r in d], cols, "{:,.0f}")
setup(axes[1], "Client memory held for one batch", "", "KB (log scale)", logy=True)
bars(axes[1], lab, [int(r["client_bytes_per_batch"]) / 1024 for r in d], cols, "{:.0f}")
save(fig, "TaskB3", "b3_alternatives.png")

# B4 - decision evidence (two panels)
m = {r["metric"]: float(r["value"]) for r in rows("TaskB4/data/b4_scale.csv")}
fig, axes = plt.subplots(1, 2, figsize=(9.6, 3.6))
setup(axes[0], "Find one pass among ~800,000", "", "ms (log scale)", logy=True)
bars(axes[0], ["database\n(PK index)", "12 .ser files\n(load + scan)", "12 lists in\nmemory (scan)"],
     [m["db_find_us"] / 1000, m["ser_find_cold_ms"], m["ser_find_warm_ms"]], [SLOTS[2], SLOTS[1], SLOTS[0]], "{:.2f}")
setup(axes[1], "Issue one pass while others issue too", "", "ms per pass (log scale)", logy=True)
bars(axes[1], ["database\n(12 counters)", "shared .ser file\nwith lock", "shared .ser file\nno lock"],
     [m["db_concurrent_ms_per_pass"], m["file_locked_ms_per_pass"], m["file_nolock_ms_per_pass"]], [SLOTS[2], SLOTS[0], SLOTS[7]], "{:.1f}")
axes[1].annotate(f"{int(m['file_nolock_lost'])} of 40 lost", (2, m["file_nolock_ms_per_pass"]), xytext=(0, 16), textcoords="offset points", ha="center", fontsize=8, color=SLOTS[7])
save(fig, "TaskB4", "b4_decision.png")
