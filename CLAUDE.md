# CLAUDE.md — LOT Scanner App

## Project Context
This is the Legend of Toys (LOT) operations system, built by the co-founder Afshaan. The system is built and maintained using the following workflow:

- **Claude Chat** acts as the orchestrator and designer — it handles strategy, system design, and produces specific change instructions for each component.
- **Four Claude Code agents** run in separate terminal tabs, each responsible for edits to files in their own folder/repo. This agent is one of those four.
- **Database edits** are handled directly by the co-founder based on instructions from Claude Chat — there is no separate agent for database interaction.
- Development happens across two laptops (office and home) with a similar setup on both.

The build comprises three separate but connected systems:

1. **Store system** (`legendlot/Stores`) — handles everything to do with store operations for Legend of Toys, including raw materials, procurement, POs, GRN receiving, inventory management, and issuing raw material to production.
2. **Production system** — contains two sub-systems:
   - **Scanner app** (`legendlot/production`) — used by people on the factory floor to scan inventory. **This agent is responsible for this system.**
   - **Dashboard** (`legendlot/dashboard`) — used for reporting and general admin of the production system.
3. **Cloudflare Worker** (`legendlot/Cloudfare`) — the single backend API layer that all three frontend systems route through to communicate with Supabase.

## Session start
- Always pull from git remote and sync the local folder at the start of every new session.
- Remote is the source of truth.

## Editing rules
- Only edit `index.html` unless there are explicit instructions for editing other files.
- If instructions reference edits to other files, highlight them to the user and ignore those edits.

## Git workflow
- Always commit to git after making changes — do this automatically.
- Always push to remote to keep local and remote in sync.
- Remote is the source of truth.

## Change discipline
- Do not make unnecessary changes.
- Feel free to suggest enhancements or flag conflicts you identify, but do not apply them without approval.
