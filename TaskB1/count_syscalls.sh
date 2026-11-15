#!/usr/bin/env bash
# Task A2.1 / B1.3 - counts the read() system calls made for the FILE, using strace.
# reads(file) = reads(counting the log) - reads(counting an empty file)   (removes the JVM's own reads)
# Usage: bash count_syscalls.sh work/attendance_1MB.log
set -u
unset JAVA_TOOL_OPTIONS
f="$1"; empty=$(mktemp); trap 'rm -f "$empty" /tmp/st.$$' EXIT
reads() { strace -f -qq -e trace=read -o /tmp/st.$$ java -Xshare:auto -cp out LineCounter "$1" "$2" > /dev/null; grep -c ' read(' /tmp/st.$$; }
printf "%-12s %14s %16s %18s\n" "mode" "file bytes" "read() syscalls" "bytes per syscall"
for m in unbuffered buffered array reader; do
  a=$(reads $m "$f"); b=$(reads $m "$empty"); n=$((a - b)); bytes=$(stat -c %s "$f")
  printf "%-12s %14s %16s %18s\n" "$m" "$bytes" "$n" "$(awk -v b=$bytes -v n=$n 'BEGIN{printf "%.1f", (n>0? b/n : 0)}')"
done
