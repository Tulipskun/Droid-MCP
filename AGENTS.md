# AGENTS.md

## Shared agent coordination

This project uses the private central coordination repository [Tulipskun/Agents](https://github.com/Tulipskun/Agents).

Each agent must install or update it in its own environment at the start of a task:
- Clone to `$HOME/.agents/Agents` if absent; otherwise fetch and fast-forward.
- Remote: `https://github.com/Tulipskun/Agents.git`
- Check GitHub authentication with `gh auth status`; never copy credentials from another machine.
- Read the central `AGENTS.md` and this project's coordination state before making changes.
- Agents may improve shared rules under the central repository's rule-evolution policy.

Keep project-specific instructions in this file below this section. Central coordination rules govern cross-session state and handoff; project-specific rules govern this repository.
