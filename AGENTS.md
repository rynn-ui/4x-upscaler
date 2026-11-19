# Agent Instructions

## Graphify is read-only for agents

This repository ships a prebuilt Graphify knowledge graph in `graphify-out/`
(`graph.json`, `GRAPH_REPORT.md`, `graph.html`). The human maintainer owns it and
rebuilds it by hand. Agents may **only read** from it.

**Allowed** — query the existing graph instead of grepping the repo:

```bash
graphify query "how does the Android app talk to the FastAPI backend?"
graphify path "UpscalerRepository" "FastAPI" --undirected
graphify explain "UpscalerApiService"
graphify section "Heading"          # Markdown docs
```

Read `graphify-out/GRAPH_REPORT.md` for god nodes, communities and suggested
questions.

**Forbidden** — never rebuild, refresh or register anything:

- `/graphify .`, `/graphify . --update`, `--force`, `--deep`, `--cluster-only`, `--watch`
- `graphify extract`, `graphify update`, `graphify cluster-only`, `graphify watch`
- `graphify install`, `graphify agents install`, `graphify hook install`
- `graphify add`, `graphify merge-graphs`, or any command that writes to `graphify-out/`

`graphify agents install` is deliberately not used: it writes a section telling
agents to rebuild the graph after code changes, which this project does not want.

If the graph is missing or stale, report that and stop. The maintainer refreshes
it with `graphify update .` when they choose to.

To stop the CLI from refreshing its own registered skill copies, export
`GRAPHIFY_NO_AUTO_REFRESH=1`.
