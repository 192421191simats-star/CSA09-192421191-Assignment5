#!/usr/bin/env bash
# Helper for committing the assignment progressively (at least one commit per task).
#
# First time:   bash git_commit_task.sh init https://github.com/<user>/CSA09-<RegNo>-Assignment5.git
# Each task:    bash git_commit_task.sh A1     (commits TaskA1 and pushes)
#               bash git_commit_task.sh A2 ... A5, B1 ... B4 - ideally on different days
# Report:       bash git_commit_task.sh report
set -e
cd "$(dirname "$0")"

case "${1:-}" in
  init)
    [ -n "${2:-}" ] || { echo "usage: bash git_commit_task.sh init <repo-url>"; exit 1; }
    git init -b main
    git add README.md .gitignore run_all.sh git_commit_task.sh db.properties db-mysql.properties sql tools lib
    git commit -m "Set up repository: README, run script, SQL scripts, JDBC test driver and tools"
    git remote add origin "$2"
    git push -u origin main
    ;;
  report)
    git add report README.md
    git commit -m "Add assignment report: record layouts, pseudocode, diagrams, measurements and decisions"
    git push
    ;;
  A[1-5]|B[1-4])
    git add "Task$1"
    git commit -m "Task$1: source, input files, outputs, measurements and screenshots"
    git push
    ;;
  *)
    echo "usage: bash git_commit_task.sh init <repo-url> | A1..A5 | B1..B4 | report"; exit 1 ;;
esac
