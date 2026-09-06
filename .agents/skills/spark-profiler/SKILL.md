---
name: spark-profiler
description: Analyze Minecraft server spark profiler files (.sparkprofile, .sparkheap, .sparkhealth) or spark.lucko.me URLs using spark-profiler-mcp to diagnose lag, MSPT spikes, memory leaks, and entity/chunk/plugin hogs.
---

# Spark Profiler MCP Skill

This skill provides direct integration with `spark-profiler-mcp` (located at `/data/data/com.termux/files/home/spark-profiler-mcp`).

## Usage Instructions

When asked to analyze a Spark profile URL (e.g. `https://spark.lucko.me/<key>`) or a local Spark file (`.sparkprofile`, `.sparkheap`, `.sparkhealth`):

1. Run the smoke/CLI analysis script or execute `spark-profiler-mcp` via Node IPC:
   ```bash
   node /data/data/com.termux/files/home/spark-profiler-mcp/dist/index.js
   ```
2. Alternatively, invoke the CLI test / smoke runner or run an inline node script importing `/data/data/com.termux/files/home/spark-profiler-mcp/dist/index.js` to parse the profile and extract:
   - **Summary**: Version, TPS, MSPT, heap, GC %, entities, hot methods, top plugins.
   - **Diagnose**: Ranked findings with root cause evidence and concrete tuning fixes.
   - **Platform & System Stats**: CPU/RAM, JVM args, Aikar flags, view/sim distance.
