#!/usr/bin/env bash
# Usage: tools/forget-flows.sh [--all]
# Makes the assistant forget everything it has been taught, so a new flow can be taught from scratch.
#   default: deletes every saved flow; the run history (what the metrics dashboard counts) is kept.
#   --all:   also clears the run history, for a completely fresh start.
# A backup of the flows is written to your home folder first (outside the repo), so nothing is lost by mistake.
set -u
cd "$(dirname "$0")/.."
mongo() { docker compose exec -T mongo mongosh tva --quiet --eval "$1"; }

docker compose ps --status running --services 2>/dev/null | grep -qx mongo || { echo "The database is not running. Run tools/up.sh first."; exit 1; }
count=$(mongo 'print(db.flows.countDocuments())' | tr -d '\r')
backup="$HOME/Teachable-voice-flows-backup-$(date +%Y-%m-%d-%H%M%S).json"
mongo 'print(JSON.stringify(db.flows.find().toArray()))' > "$backup"
echo "Backed up $count flow(s) to $backup"
mongo 'db.flows.find({}, {description: 1}).forEach((f) => print("  forgetting: " + f.description))'
mongo 'print("Deleted " + db.flows.deleteMany({}).deletedCount + " flow(s).")'
if [ "${1:-}" = "--all" ]; then
  mongo 'print("Cleared " + db.runlogs.deleteMany({}).deletedCount + " run log(s).")'
fi
echo "Done. Say a new command on the phone: it will say it hasn't learned that yet, and ask you to show it once."
