#!/bin/bash
# Stops the services started by start-dev.sh. Run from the project root.

if [ -f logs/pids.txt ]; then
    while read -r pid; do
        kill "$pid" 2>/dev/null && echo "Stopped process $pid"
    done < logs/pids.txt
    rm logs/pids.txt
else
    echo "No PID file found, stopping by port..."
    for port in 8080 8081 8082 8083 9000; do
        lsof -ti:"$port" | xargs kill 2>/dev/null || true
    done
fi

echo "All services stopped."
